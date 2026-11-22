package de.aidocuments.document;

import de.aidocuments.config.AppProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;
import de.aidocuments.i18n.Msg;
import de.aidocuments.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Legt PDF-Dateien und Vorschaubilder im Datenverzeichnis ab. */
@Component
public class FileStorage {

    private static final Logger log = LoggerFactory.getLogger(FileStorage.class);

    /** Eine vollständig empfangene, aber noch nicht übernommene Datei. */
    public record Received(Path tempFile, String sha256, long size) {
    }

    private final Path root;
    private final Path tmp;
    private final Path thumbnails;

    public FileStorage(AppProperties properties) {
        this.root = properties.dataRoot();
        this.tmp = root.resolve("tmp");
        this.thumbnails = root.resolve("thumbnails");
        try {
            Files.createDirectories(root.resolve("files"));
            Files.createDirectories(tmp);
            Files.createDirectories(thumbnails);
        } catch (IOException e) {
            throw new UncheckedIOException("Datenverzeichnis kann nicht angelegt werden: " + root, e);
        }
    }

    /** Größte Datei, die angenommen wird – gleich auf welchem Weg (Upload, geänderte Fassung, E-Mail, Ordner). */
    public static final long MAX_FILE_BYTES = 250L * 1024 * 1024;

    /** Die Datei ist größer als {@link #MAX_FILE_BYTES}; nichts davon bleibt liegen. */
    public static final class TooLargeException extends IOException {
        public TooLargeException() {
            super("Datei größer als " + MAX_FILE_BYTES + " Bytes");
        }
    }

    /**
     * So viel Platz bleibt mindestens frei: Darunter nimmt die Ablage keine neuen Dateien oder Fassungen mehr an – sonst
     * könnte ein (missbrauchtes) Konto mit Uploads oder immer neuen Fassungen die Platte füllen, und dann scheiterten
     * auch Datenbank und Suchindex.
     */
    public static final long MIN_FREE_BYTES = 1024L * 1024 * 1024;

    /** Auf der Platte des Datenverzeichnisses ist zu wenig Platz frei ({@link #MIN_FREE_BYTES}). */
    public static final class StorageFullException extends ApiException {
        public StorageFullException() {
            super(HttpStatus.INSUFFICIENT_STORAGE, Msg.of("upload.storeFailed"));
        }
    }

    /** Wirft {@link StorageFullException}, wenn weniger als {@link #MIN_FREE_BYTES} plus {@code needed} frei sind. */
    public void requireFreeSpace(long needed) {
        long usable;
        try {
            usable = Files.getFileStore(root).getUsableSpace();
        } catch (IOException e) {
            return; // unbekannt – dann entscheidet das Schreiben selbst
        }
        if (usable < MIN_FREE_BYTES + needed) {
            log.warn("Nur noch {} MB frei im Datenverzeichnis – neue Dateien werden abgelehnt", usable >> 20);
            throw new StorageFullException();
        }
    }

    /**
     * Schreibt den Stream in eine temporäre Datei und berechnet dabei Prüfsumme und Größe. Bricht ab, sobald mehr als
     * {@link #MAX_FILE_BYTES} ankommen – sonst ließe sich mit einem endlosen Upload die Platte füllen.
     */
    public Received receive(InputStream in) throws IOException {
        requireFreeSpace(0);
        Path target = tmp.resolve(UUID.randomUUID() + ".part");
        MessageDigest digest = sha256();
        long size = 0;
        try (OutputStream out = new DigestOutputStream(Files.newOutputStream(target), digest)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = in.read(buffer)) >= 0; ) {
                long before = size;
                size += read;
                if (size > MAX_FILE_BYTES) {
                    throw new TooLargeException();
                }
                // alle 16 MB erneut: mehrere gleichzeitige Uploads sollen die Grenze nicht gemeinsam unterlaufen
                if (before >> 24 != size >> 24) {
                    requireFreeSpace(0);
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            Files.deleteIfExists(target);
            throw e;
        }
        return new Received(target, HexFormat.of().formatHex(digest.digest()), size);
    }

    /** PDFs beginnen mit "%PDF-" (laut Spezifikation innerhalb der ersten 1024 Bytes). */
    public boolean looksLikePdf(Received received) throws IOException {
        byte[] head = new byte[1024];
        int read;
        try (InputStream in = Files.newInputStream(received.tempFile())) {
            read = in.readNBytes(head, 0, head.length);
        }
        return new String(head, 0, read, StandardCharsets.ISO_8859_1).contains("%PDF-");
    }

    /** Verschiebt die Datei an ihren endgültigen Platz und liefert den relativen Pfad. */
    public String commit(Received received, String documentId) throws IOException {
        LocalDate today = LocalDate.now();
        String relative = "files/%04d/%02d/%s.pdf".formatted(today.getYear(), today.getMonthValue(), documentId);
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.move(received.tempFile(), target, StandardCopyOption.REPLACE_EXISTING);
        return relative;
    }

    public void discard(Received received) {
        try {
            Files.deleteIfExists(received.tempFile());
        } catch (IOException ignored) {
            // temporäre Datei – wird notfalls beim nächsten Aufräumen entfernt
        }
    }

    public Path resolve(String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Pfad außerhalb des Datenverzeichnisses");
        }
        return resolved;
    }

    /** Zeitpunkt, zu dem das Vorschaubild zuletzt geschrieben wurde; null, wenn es keins gibt. */
    public java.time.Instant thumbnailWrittenAt(String documentId) {
        try {
            return Files.getLastModifiedTime(thumbnailPath(documentId)).toInstant();
        } catch (IOException e) {
            return null;
        }
    }

    public Path thumbnailPath(String documentId) {
        return thumbnails.resolve(documentId + ".jpg");
    }

    /**
     * Legt eine Kopie der Datei neben ihr ab ({@code <id>.original.pdf}), sofern es noch keine gibt.
     *
     * @return relativer Pfad der Kopie
     */
    public String keepOriginal(String relativePath) throws IOException {
        String copy = relativePath.replaceFirst("(?i)\\.pdf$", "") + ".original.pdf";
        Path target = resolve(copy);
        if (!Files.exists(target)) {
            requireFreeSpace(Files.size(resolve(relativePath)));
            Path part = target.resolveSibling(target.getFileName() + ".part");
            Files.copy(resolve(relativePath), part, StandardCopyOption.REPLACE_EXISTING);
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
        }
        return copy;
    }

    /** Ersetzt die Datei durch eine empfangene – in einem Schritt, damit nie eine halbe Datei ausgeliefert wird. */
    public void replace(String relativePath, Received received) throws IOException {
        Path target = resolve(relativePath);
        Path part = target.resolveSibling(target.getFileName() + ".part");
        Files.move(received.tempFile(), part, StandardCopyOption.REPLACE_EXISTING);
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Kopiert eine aufbewahrte Datei zurück an ihren Platz – in einem Schritt. */
    public void restore(String copyPath, String relativePath) throws IOException {
        requireFreeSpace(Files.size(resolve(copyPath)));
        Path target = resolve(relativePath);
        Path part = target.resolveSibling(target.getFileName() + ".part");
        Files.copy(resolve(copyPath), part, StandardCopyOption.REPLACE_EXISTING);
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Legt eine Kopie der aktuellen Fassung neben die Datei ({@code <id>.v<Zeitstempel>.pdf}) – damit sich eine Änderung
     * zurücknehmen lässt.
     *
     * @return relativer Pfad der Kopie
     */
    public String keepVersion(String relativePath) throws IOException {
        String copy = relativePath.replaceFirst("(?i)\\.pdf$", "") + ".v" + System.currentTimeMillis() + ".pdf";
        Path target = resolve(copy);
        requireFreeSpace(Files.size(resolve(relativePath)));
        Path part = target.resolveSibling(target.getFileName() + ".part");
        Files.copy(resolve(relativePath), part, StandardCopyOption.REPLACE_EXISTING);
        Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
        return copy;
    }

    /** Entfernt alle Dateien eines Dokuments: aktuelle Fassung, Original, aufbewahrte Fassungen und Vorschaubild. */
    public void deleteAllFiles(String relativePath, String documentId) {
        Path file = resolve(relativePath);
        try {
            if (Files.isDirectory(file.getParent())) {
                try (var siblings = Files.list(file.getParent())) {
                    for (Path sibling : siblings.toList()) {
                        if (sibling.getFileName().toString().startsWith(documentId + ".")) {
                            Files.deleteIfExists(sibling);
                        }
                    }
                }
            }
            Files.deleteIfExists(thumbnailPath(documentId));
        } catch (IOException e) {
            throw new UncheckedIOException("Dateien konnten nicht gelöscht werden", e);
        }
    }

    /** Für Prüfungen, bevor eine empfangene Datei übernommen wird (Vorschaubild in einer Zwischendatei). */
    public Path temporaryFile(String suffix) {
        return tmp.resolve(UUID.randomUUID() + suffix);
    }

    public void delete(String relativePath, String documentId) {
        delete(relativePath, null, documentId);
    }

    public void delete(String relativePath, String originalCopy, String documentId) {
        try {
            Files.deleteIfExists(resolve(relativePath));
            if (originalCopy != null) {
                Files.deleteIfExists(resolve(originalCopy));
            }
            Files.deleteIfExists(thumbnailPath(documentId));
        } catch (IOException e) {
            throw new UncheckedIOException("Datei konnte nicht gelöscht werden", e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
