package de.aidocuments.importer;

import de.aidocuments.document.DocumentService;
import de.aidocuments.document.DocumentService.IngestResult;
import de.aidocuments.document.DocumentService.Outcome;
import de.aidocuments.document.DocumentSource;
import de.aidocuments.i18n.Msg;
import de.aidocuments.i18n.MsgCodec;
import de.aidocuments.importer.ImportSettings.FolderMode;
import de.aidocuments.importer.ImportSettingsService.Effective;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Übernimmt neue PDF-Dateien aus dem überwachten Eingangsordner (einschließlich Unterordnern) –
 * z. B. das Zielverzeichnis eines Netzwerkscanners.
 */
@Service
public class FolderImportService {

    private static final Logger log = LoggerFactory.getLogger(FolderImportService.class);

    /** Unterordner für übernommene Dateien (Modus MOVE); wird selbst nie durchsucht. */
    static final String IMPORTED_DIR = "importiert";
    /** Dateien, die gerade noch geschrieben werden (Scanner, Kopiervorgang), erst im nächsten Lauf anfassen. */
    private static final long SETTLE_MILLIS = 10_000;
    private static final int MAX_DEPTH = 8;

    /**
     * @param message das Ergebnis des letzten Durchlaufs – hier sprachneutral (siehe {@link MsgCodec}), denn der
     *                Durchlauf hat keinen Leser; der Controller übersetzt es beim Ausliefern
     */
    public record Status(Instant lastScanAt, boolean ok, String message, boolean running) {

        static Status of(boolean ok, List<Msg> result) {
            return new Status(Instant.now(), ok, MsgCodec.encode(result), false);
        }

        public Status withMessage(String text) {
            return new Status(lastScanAt, ok, text, running);
        }
    }

    private final ImportSettingsService settings;
    private final DocumentService documentService;
    private final ImportedFileRepository importedFiles;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Status status = new Status(null, true, null, false);

    public FolderImportService(ImportSettingsService settings, DocumentService documentService,
                               ImportedFileRepository importedFiles) {
        this.settings = settings;
        this.documentService = documentService;
        this.importedFiles = importedFiles;
    }

    public Status status() {
        Status current = status;
        return new Status(current.lastScanAt(), current.ok(), current.message(), lock.isLocked());
    }

    /** Durchsucht den Eingangsordner. Läuft bereits ein Durchlauf, passiert nichts. */
    public void scan() {
        Effective config = settings.current();
        if (!config.folderEnabled() || !lock.tryLock()) {
            return;
        }
        try {
            status = run(config);
        } catch (RuntimeException e) {
            log.warn("Eingangsordner konnte nicht geprüft werden", e);
            status = Status.of(false, List.of(Msg.of("error.unexpected", Msg.detailOf(e))));
        } finally {
            lock.unlock();
        }
    }

    private Status run(Effective config) {
        Path root = Path.of(config.folderPath());
        if (!Files.isDirectory(root)) {
            return Status.of(false, List.of(Msg.of("folder.scan.unreachable", root)));
        }
        List<Path> candidates;
        try (Stream<Path> walk = Files.walk(root, MAX_DEPTH)) {
            // ein PDF vor der gleichnamigen XML-Datei (auch bei „.XML“): Es nimmt sie mit, falls sie dazugehört
            candidates = walk.filter(path -> isCandidate(root, path)).sorted(java.util.Comparator
                    .comparing((Path path) -> withoutExtension(path).toLowerCase(java.util.Locale.ROOT))
                    .thenComparing(path -> !path.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                            .endsWith(".pdf"))
                    .thenComparing(java.util.Comparator.naturalOrder())).toList();
        } catch (IOException | RuntimeException e) {
            return Status.of(false, List.of(Msg.of("folder.scan.readFailed", Msg.detailOf(e))));
        }

        int imported = 0;
        int duplicates = 0;
        int rejected = 0;
        int failed = 0;
        java.util.Set<Path> handled = new java.util.HashSet<>();
        for (Path file : candidates) {
            if (handled.contains(file)) {
                continue;
            }
            try {
                BasicFileAttributes attributes = pending(file);
                if (attributes == null) {
                    continue;
                }
                IngestResult result = null;
                // „Rechnung_4711.pdf“ und „Rechnung_4711.xml“: meist dieselbe Rechnung – dann ein Dokument
                Path partner = xmlPartner(file, candidates);
                BasicFileAttributes partnerAttributes = partner == null || !Files.isRegularFile(partner) ? null
                        : pending(partner);
                if (partnerAttributes != null) {
                    result = documentService.ingestPair(() -> Files.newInputStream(file),
                            file.getFileName().toString(), () -> Files.newInputStream(partner),
                            partner.getFileName().toString(), DocumentSource.FOLDER, null, config.folderAreaId());
                    if (result != null) {
                        handled.add(partner);
                        remember(config, root, partner, partnerAttributes, Outcome.IGNORED);
                    }
                }
                if (result == null) {
                    try (InputStream in = Files.newInputStream(file)) {
                        result = documentService.ingest(in, file.getFileName().toString(), DocumentSource.FOLDER,
                                null, config.folderAreaId());
                    }
                }
                switch (result.outcome()) {
                    case CREATED -> imported++;
                    case DUPLICATE -> duplicates++;
                    case REJECTED -> rejected++;
                    case IGNORED -> { }
                }
                remember(config, root, file, attributes, result.outcome());
            } catch (IOException | RuntimeException e) {
                failed++;
                log.warn("Datei {} konnte nicht übernommen werden: {}", file, e.toString());
            }
        }

        if (imported > 0) {
            log.info("Eingangsordner {}: {} neue PDFs übernommen, {} bereits vorhanden, {} kein gültiges PDF, "
                    + "{} nicht lesbar", root, imported, duplicates, rejected, failed);
        }
        if (imported + duplicates + rejected + failed == 0) {
            return Status.of(true, List.of(Msg.of("folder.scan.none")));
        }
        // Sätze der Form „Bezeichnung: Anzahl.“ – stimmt in jeder Sprache für jede Anzahl
        List<Msg> sentences = new ArrayList<>();
        sentences.add(Msg.of("folder.scan.imported", imported));
        if (duplicates > 0) {
            sentences.add(Msg.of("folder.scan.duplicates", duplicates));
        }
        if (rejected > 0) {
            sentences.add(Msg.of("folder.scan.rejected", rejected));
        }
        if (failed > 0) {
            sentences.add(Msg.of("folder.scan.unreadable", failed));
        }
        return Status.of(failed == 0, sentences);
    }

    /** Die Angaben der Datei, wenn sie fertig geschrieben und noch nicht übernommen ist – sonst {@code null}. */
    private BasicFileAttributes pending(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        long modified = attributes.lastModifiedTime().toMillis();
        if (System.currentTimeMillis() - modified < SETTLE_MILLIS) {
            return null;
        }
        ImportedFile known = importedFiles.findById(file.toAbsolutePath().toString()).orElse(null);
        return known != null && known.size() == attributes.size() && known.modifiedAt() == modified ? null : attributes;
    }

    /** Übernommene Dateien beiseitelegen oder sich merken (abgelehnte bleiben liegen und werden nur gemerkt). */
    private void remember(Effective config, Path root, Path file, BasicFileAttributes attributes, Outcome outcome)
            throws IOException {
        if (config.folderMode() == FolderMode.MOVE && outcome != Outcome.REJECTED) {
            moveAside(root, file);
        } else {
            importedFiles.save(new ImportedFile(file.toAbsolutePath().toString(), attributes.size(),
                    attributes.lastModifiedTime().toMillis(), outcome.name(), Instant.now()));
        }
    }

    private static String withoutExtension(Path path) {
        String name = path.toString();
        int dot = name.lastIndexOf('.');
        return dot > name.lastIndexOf(java.io.File.separatorChar) ? name.substring(0, dot) : name;
    }

    /** Zu „a.pdf“ die XML-Datei „a.xml“ im selben Verzeichnis (Groß-/Kleinschreibung egal), sonst {@code null}. */
    private static Path xmlPartner(Path file, List<Path> candidates) {
        String name = file.getFileName().toString();
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
            return null;
        }
        String base = name.substring(0, name.length() - 4);
        for (Path candidate : candidates) {
            String other = candidate.getFileName().toString();
            if (java.util.Objects.equals(candidate.getParent(), file.getParent()) && other.length() == name.length()
                    && other.toLowerCase(java.util.Locale.ROOT).endsWith(".xml")
                    && other.substring(0, other.length() - 4).equalsIgnoreCase(base)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isCandidate(Path root, Path path) {
        String name = path.getFileName().toString();
        if (name.startsWith(".") || !IMPORTABLE.matcher(name).matches() || !Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            // keine symbolischen Links: Wer in den Eingangsordner schreiben darf, soll darüber nicht beliebige
            // andere Dateien des Servers einschleusen können
            return false;
        }
        Path relative = root.relativize(path);
        return relative.getNameCount() == 1 || !relative.getName(0).toString().equals(IMPORTED_DIR);
    }

    /** PDFs, Fotos und XML-Rechnungen (beide werden bei der Aufnahme zu PDFs) */
    private static final java.util.regex.Pattern IMPORTABLE =
            java.util.regex.Pattern.compile("(?i).+\\.(pdf|jpe?g|png|hei[cf]|xml)$");

    /** Verschiebt eine übernommene Datei nach "importiert/…"; bei Namensgleichheit wird durchnummeriert. */
    private static void moveAside(Path root, Path file) throws IOException {
        Path target = root.resolve(IMPORTED_DIR).resolve(root.relativize(file));
        Files.createDirectories(target.getParent());
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int counter = 1; ; counter++) {
            try {
                Files.move(file, target);
                return;
            } catch (FileAlreadyExistsException e) {
                target = target.resolveSibling(base + " (" + counter + ")" + extension);
            }
        }
    }
}
