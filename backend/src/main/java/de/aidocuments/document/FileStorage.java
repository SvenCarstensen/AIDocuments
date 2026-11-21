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
import org.springframework.stereotype.Component;

/** Legt PDF-Dateien und Vorschaubilder im Datenverzeichnis ab. */
@Component
public class FileStorage {

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

    /** Schreibt den Stream in eine temporäre Datei und berechnet dabei Prüfsumme und Größe. */
    public Received receive(InputStream in) throws IOException {
        Path target = tmp.resolve(UUID.randomUUID() + ".part");
        MessageDigest digest = sha256();
        long size;
        try (OutputStream out = new DigestOutputStream(Files.newOutputStream(target), digest)) {
            size = in.transferTo(out);
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
        Path target = resolve(relativePath);
        Path part = target.resolveSibling(target.getFileName() + ".part");
        Files.copy(resolve(copyPath), part, StandardCopyOption.REPLACE_EXISTING);
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
