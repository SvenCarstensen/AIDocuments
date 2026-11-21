package de.aidocuments.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.aidocuments.config.AppProperties;
import de.aidocuments.document.PdfExtractor;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Die eingebaute Texterkennung. Abläufe (Seitenlimit, Fehler, fehlende Sprachen) laufen gegen einen Stellvertreter
 * von Tesseract; die echte Erkennung wird nur geprüft, wo Tesseract installiert ist.
 */
class BuiltinOcrServiceTest {

    @TempDir
    Path tmp;

    @Test
    void readsEveryPageAndJoinsTheText() throws Exception {
        Path tesseract = fakeTesseract("deu eng", "echo \"Seite\"; printf '\\f'");
        BuiltinOcrService service = service(tesseract, "deu+eng", 5);

        assertThat(service.isAvailable()).isTrue();
        BuiltinOcrService.Result result = service.transcribe(scan(2, null), 2);

        assertThat(result.text()).isEqualTo("Seite\n\nSeite");
        assertThat(result.notes()).isEmpty();
        // jede Seite geht einzeln als PNG an Tesseract, mit den gewünschten Sprachen
        assertThat(Files.readString(tmp.resolve("aufrufe.txt")).lines())
                .hasSize(2).allMatch(line -> line.equals("stdin stdout -l deu+eng --dpi 300"));
    }

    @Test
    void readsOnlyTheFirstPagesOfLongScansAndSaysSo() throws Exception {
        BuiltinOcrService service = service(fakeTesseract("deu", "echo Text"), "deu", 2);

        BuiltinOcrService.Result result = service.transcribe(scan(3, null), 3);

        assertThat(Files.readString(tmp.resolve("aufrufe.txt")).lines()).hasSize(2);
        assertThat(result.notes()).singleElement()
                .satisfies(note -> assertThat(note.key()).isEqualTo("ai.note.partialScan"));
    }

    @Test
    void usesOnlyInstalledLanguagesAndIsOffWithoutAny() throws Exception {
        Path tesseract = fakeTesseract("eng osd", "echo Text");
        BuiltinOcrService service = service(tesseract, "deu+eng", 5);
        service.transcribe(scan(1, null), 1);
        assertThat(Files.readString(tmp.resolve("aufrufe.txt"))).contains("-l eng ");

        assertThat(service(tesseract, "deu", 5).isAvailable()).isFalse();
        assertThat(service(tmp.resolve("gibt-es-nicht"), "deu", 5).isAvailable()).isFalse();
        assertThat(new BuiltinOcrService(properties(false, tesseract, "eng", 5), new PdfExtractor()).isAvailable())
                .isFalse();
    }

    @Test
    void reportsAFailingTesseract() throws Exception {
        BuiltinOcrService service = service(fakeTesseract("deu", "exit 3"), "deu", 5);
        Path pdf = scan(1, null);

        assertThatThrownBy(() -> service.transcribe(pdf, 1)).isInstanceOf(AiException.class);
    }

    @Test
    void recognizesARealScanWhereTesseractIsInstalled() throws Exception {
        BuiltinOcrService service = new BuiltinOcrService(
                properties(true, Path.of("tesseract"), "deu+eng", 5), new PdfExtractor());
        assumeTrue(service.isAvailable(), "Tesseract ist hier nicht installiert");

        String text = service.transcribe(scan(1, "Jahresabrechnung Strom 2025\nNachzahlung: 54,29 EUR"), 1).text();

        assertThat(text).contains("Jahresabrechnung Strom 2025").contains("Nachzahlung").contains("54,29");
    }

    private BuiltinOcrService service(Path command, String languages, int maxPages) {
        return new BuiltinOcrService(properties(true, command, languages, maxPages), new PdfExtractor());
    }

    private static AppProperties properties(boolean enabled, Path command, String languages, int maxPages) {
        return new AppProperties(Path.of("./data"), null, null,
                new AppProperties.Ocr(enabled, command.toString(), languages, maxPages, 300, Duration.ofSeconds(30)));
    }

    /** Ein Skript, das sich wie Tesseract verhält: Sprachliste, Aufrufe protokollieren, Eingabe lesen, Text ausgeben. */
    private Path fakeTesseract(String languages, String recognition) throws Exception {
        assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"), "braucht eine Unix-Shell");
        Path log = tmp.resolve("aufrufe.txt");
        Path script = tmp.resolve("tesseract-" + System.nanoTime());
        Files.writeString(script, """
                #!/bin/sh
                if [ "$1" = "--list-langs" ]; then
                  echo 'List of available languages in "/usr/share/tessdata/" (2):'
                  for l in %s; do echo "$l"; done
                  exit 0
                fi
                echo "$*" >> '%s'
                cat > /dev/null
                %s
                """.formatted(languages, log, recognition));
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    /** Ein Scan: Seiten, die nur aus einem Bild bestehen – ohne Textebene. */
    private Path scan(int pages, String text) throws Exception {
        Path file = Files.createTempFile(tmp, "scan", ".pdf");
        try (PDDocument pdf = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                pdf.addPage(page);
                BufferedImage image = new BufferedImage(1240, 1754, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = image.createGraphics();
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, image.getWidth(), image.getHeight());
                if (text != null) {
                    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g.setColor(Color.BLACK);
                    g.setFont(new Font(Font.SERIF, Font.PLAIN, 36));
                    int y = 200;
                    for (String line : text.split("\n")) {
                        g.drawString(line, 120, y);
                        y += 60;
                    }
                }
                g.dispose();
                try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                    content.drawImage(LosslessFactory.createFromImage(pdf, image), 0, 0,
                            PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
                }
            }
            pdf.save(file.toFile());
        }
        return file;
    }
}
