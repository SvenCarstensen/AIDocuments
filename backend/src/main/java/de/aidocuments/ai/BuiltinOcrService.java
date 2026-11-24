package de.aidocuments.ai;

import de.aidocuments.config.AppProperties;
import de.aidocuments.document.PdfExtractor;
import de.aidocuments.i18n.Msg;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Die eingebaute Texterkennung: Tesseract liest Scans ohne Textebene. Sie springt ein, solange keine externe KI
 * eingerichtet ist, und liefert nur den Text – Titel, Zusammenfassung und Schlagworte bleiben der KI vorbehalten.
 *
 * <p>Jede Seite wird mit 300 dpi in Graustufen gerendert und einzeln an Tesseract übergeben (über die
 * Standardein- und -ausgabe, ohne Zwischendateien). Ob Tesseract da ist und welche der gewünschten Sprachen es
 * kennt, wird einmal beim Start geprüft.
 */
@Service
public class BuiltinOcrService {

    private static final Logger log = LoggerFactory.getLogger(BuiltinOcrService.class);

    /** @param notes Hinweise für den Anwender, etwa dass nur ein Teil der Seiten gelesen wurde */
    public record Result(String text, List<Msg> notes) {
    }

    private final AppProperties.Ocr config;
    private final PdfExtractor pdfExtractor;
    /** Die Sprachen, die Tesseract tatsächlich kennt; leer = keine Texterkennung möglich. */
    private final String languages;

    public BuiltinOcrService(AppProperties properties, PdfExtractor pdfExtractor) {
        this.config = properties.ocr();
        this.pdfExtractor = pdfExtractor;
        this.languages = config.enabled() ? detectLanguages() : "";
    }

    /** Ob die eingebaute Texterkennung bereitsteht (eingeschaltet und Tesseract mit passender Sprache gefunden). */
    public boolean isAvailable() {
        return !languages.isEmpty();
    }

    public Result transcribe(Path pdf, int pageCount) throws AiException {
        if (!isAvailable()) {
            throw new AiException(Msg.of("ai.error.notConfigured"));
        }
        StringBuilder text = new StringBuilder();
        Instant started = Instant.now();
        int pages;
        try (PdfExtractor.PageImages images = pdfExtractor.openPageImages(pdf, config.dpi())) {
            pages = config.maxPages() > 0 ? Math.min(config.maxPages(), images.pageCount()) : images.pageCount();
            if (pages == 0) {
                throw new AiException(Msg.of("ai.error.emptyPdf"));
            }
            for (int page = 0; page < pages; page++) {
                String pageText = recognize(images.png(page));
                if (!pageText.isEmpty()) {
                    if (!text.isEmpty()) {
                        text.append("\n\n");
                    }
                    text.append(pageText);
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new AiException(Msg.of("ai.error.renderFailed"), e);
        }
        log.info("Texterkennung: {} Seite(n) von {} in {} s gelesen, {} Zeichen", pages, pdf.getFileName(),
                Duration.between(started, Instant.now()).toSeconds(), text.length());

        List<Msg> notes = new ArrayList<>();
        if (pageCount > pages) {
            notes.add(Msg.of("ai.note.partialScan", pages, pageCount));
        }
        return new Result(text.toString(), List.copyOf(notes));
    }

    /** Eine Seite durch Tesseract: Bild auf die Standardeingabe, Text von der Standardausgabe. */
    private String recognize(byte[] png) throws AiException {
        Process process;
        try {
            process = new ProcessBuilder(config.command(), "stdin", "stdout", "-l", languages,
                    "--dpi", String.valueOf(config.dpi()))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            throw new AiException(Msg.of("ai.error.unexpected", e.getMessage()), e);
        }
        // Ausgabe nebenher lesen, damit sich die Puffer der beiden Richtungen nicht gegenseitig blockieren
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));
        try (OutputStream in = process.getOutputStream()) {
            in.write(png);
        } catch (IOException e) {
            // Tesseract hat die Eingabe vorzeitig geschlossen – der Exit-Code unten sagt, warum
            log.debug("Texterkennung: Eingabe abgebrochen: {}", e.toString());
        }
        try {
            if (!process.waitFor(config.pageTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new AiException(Msg.of("ai.error.timeout"));
            }
            if (process.exitValue() != 0) {
                throw new AiException(Msg.of("ai.error.unexpected", "tesseract exit " + process.exitValue()));
            }
            return clean(new String(output.get(10, TimeUnit.SECONDS), StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new AiException(Msg.of("ai.error.aborted"), e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new AiException(Msg.of("ai.error.unexpected", e.toString()), e);
        }
    }

    private static byte[] readAll(InputStream in) {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Seitenvorschübe und Leerzeilenblöcke entfernen; Tesseract trennt Absätze mit einer Leerzeile. */
    static String clean(String raw) {
        return raw.replace("\f", "\n")
                .replace("\r\n", "\n")
                .replaceAll("[ \\t\\x0B\\u00A0]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    /**
     * Fragt Tesseract nach seinen Sprachen und behält von den gewünschten die vorhandenen. Fehlt Tesseract oder
     * kennt es keine davon, gibt es keine eingebaute Texterkennung – das ist kein Fehler, nur ein Hinweis im Protokoll.
     */
    private String detectLanguages() {
        List<String> wanted = Arrays.stream(config.languages().split("[+,\\s]+")).filter(s -> !s.isBlank()).toList();
        Set<String> installed = new LinkedHashSet<>();
        try {
            Process process = new ProcessBuilder(config.command(), "--list-langs").redirectErrorStream(true).start();
            String listing = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8);
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                log.info("Eingebaute Texterkennung aus: {} --list-langs schlug fehl", config.command());
                return "";
            }
            listing.lines().skip(1).map(String::strip).filter(s -> !s.isEmpty()).forEach(installed::add);
        } catch (IOException e) {
            log.info("Eingebaute Texterkennung aus: Tesseract nicht gefunden ({})", config.command());
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
        List<String> usable = wanted.stream().filter(installed::contains).toList();
        List<String> missing = wanted.stream().filter(lang -> !installed.contains(lang)).toList();
        if (!missing.isEmpty()) {
            log.warn("Texterkennung: Tesseract kennt die Sprache(n) {} nicht – sie werden übergangen", missing);
        }
        if (usable.isEmpty()) {
            log.warn("Eingebaute Texterkennung aus: keine der Sprachen {} ist für Tesseract installiert", wanted);
            return "";
        }
        log.info("Eingebaute Texterkennung bereit: Tesseract, Sprachen {}", String.join("+", usable));
        return String.join("+", usable);
    }
}
