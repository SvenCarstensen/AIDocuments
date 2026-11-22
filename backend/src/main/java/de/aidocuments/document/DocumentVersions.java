package de.aidocuments.document;

import static de.aidocuments.persistence.Db.instant;
import static de.aidocuments.persistence.Db.millis;
import static de.aidocuments.persistence.Db.nullableInt;
import static de.aidocuments.persistence.Db.nullableLong;

import de.aidocuments.persistence.Db;
import de.aidocuments.web.ApiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Die Fassungen der PDF-Datei eines Dokuments (Tabelle {@code document_versions}). Jede Änderung ergibt eine neue
 * Fassung; keine wird überschrieben oder gelöscht, solange es das Dokument gibt. Die aktuelle Fassung ist die Datei des
 * Dokuments selbst ({@code storage_path} leer); bevor sie sich ändert, wird sie als eigene Datei aufbewahrt.
 *
 * <p>Ein Dokument ohne Zeilen hat nur seine erste Fassung – die Zeilen entstehen erst mit der ersten Änderung.
 */
@Component
public class DocumentVersions {

    public enum Kind {
        /** wie importiert (E-Mail, Eingangsordner, Upload) */
        IMPORTED,
        /** im Betrachter geändert und gespeichert */
        EDITED,
        /** das Original wiederhergestellt */
        ORIGINAL_RESTORED,
        /** eine ältere Fassung wiederhergestellt */
        VERSION_RESTORED,
        /** eine Änderung über die Historie zurückgenommen */
        UNDONE
    }

    public record Version(String id, String documentId, int number, String storagePath, Long sizeBytes,
                          Integer pageCount, Kind kind, Integer restoredFrom, Instant createdAt, String createdBy) {

        public boolean current() {
            return storagePath == null;
        }
    }

    private final Db db;
    private final JdbcClient jdbc;
    private final FileStorage storage;

    public DocumentVersions(Db db, FileStorage storage) {
        this.db = db;
        this.jdbc = db.jdbc();
        this.storage = storage;
    }

    /** Alle Fassungen, die neueste zuletzt; ohne gespeicherte Zeilen die eine, ursprüngliche. */
    public List<Version> list(Document doc) {
        List<Version> rows = rows(doc.getId());
        if (!rows.isEmpty()) {
            return rows;
        }
        return initialRows(doc);
    }

    /** Datei einer Fassung. */
    public Path file(Document doc, int number) {
        Version version = list(doc).stream().filter(v -> v.number() == number).findFirst()
                .orElseThrow(() -> ApiException.notFound("error.document.versionNotFound"));
        Path path = storage.resolve(version.current() ? doc.getStoragePath() : version.storagePath());
        if (!Files.exists(path)) {
            throw ApiException.notFound("error.document.fileNotFound");
        }
        return path;
    }

    /**
     * Vor einer Änderung der Datei: die aktuelle Fassung als eigene Datei aufbewahren.
     *
     * @param existingCopy eine schon angelegte Kopie der aktuellen Datei (wird übernommen), oder {@code null}
     * @return relativer Pfad der aufbewahrten Fassung
     */
    public synchronized String keepCurrent(Document doc, String existingCopy) throws IOException {
        if (rows(doc.getId()).isEmpty()) {
            for (Version version : initialRows(doc)) {
                insert(version);
            }
        }
        Version current = rows(doc.getId()).stream().filter(Version::current).findFirst().orElse(null);
        if (current == null) {
            return existingCopy; // kann nur nach einem abgebrochenen Vorgang vorkommen – nichts mehr aufzubewahren
        }
        String copy = existingCopy;
        if (copy == null) {
            copy = doc.getStoragePath().replaceFirst("(?i)\\.pdf$", "") + ".ver" + current.number() + ".pdf";
            Path target = storage.resolve(copy);
            storage.requireFreeSpace(Files.size(storage.resolve(doc.getStoragePath())));
            Path part = target.resolveSibling(target.getFileName() + ".part");
            Files.copy(storage.resolve(doc.getStoragePath()), part, StandardCopyOption.REPLACE_EXISTING);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        jdbc.sql("UPDATE document_versions SET storage_path = ?, size_bytes = ?, page_count = ? WHERE id = ?")
                .param(copy).param(Files.size(storage.resolve(copy))).param(doc.getPageCount()).param(current.id())
                .update();
        return copy;
    }

    /** Nach einer Änderung der Datei: die neue aktuelle Fassung vermerken. */
    public synchronized void added(Document doc, Kind kind, Integer restoredFrom) {
        int next = rows(doc.getId()).stream().mapToInt(Version::number).max().orElse(0) + 1;
        insert(new Version(UUID.randomUUID().toString(), doc.getId(), next, null, null, null, kind, restoredFrom,
                Instant.now(), currentUser()));
    }

    /** Beim endgültigen Löschen eines Dokuments (die Dateien entfernt {@link FileStorage#deleteAllFiles}). */
    public void forget(String documentId) {
        jdbc.sql("DELETE FROM document_versions WHERE document_id = ?").param(documentId).update();
    }

    /**
     * Fassungen eines Dokuments, für das noch keine vermerkt sind: die importierte – und, wenn das Dokument schon vor
     * der Versionierung geändert wurde, dessen aufbewahrtes Original und die aktuelle Fassung.
     */
    private List<Version> initialRows(Document doc) {
        String documentId = doc.getId();
        Instant imported = doc.getCreatedAt();
        if (doc.getOriginalStoragePath() == null) {
            return List.of(new Version(UUID.randomUUID().toString(), documentId, 1, null, null, null, Kind.IMPORTED,
                    null, imported, null));
        }
        Long originalSize = null;
        try {
            originalSize = Files.size(storage.resolve(doc.getOriginalStoragePath()));
        } catch (IOException e) {
            // Größe bleibt offen
        }
        return List.of(
                new Version(UUID.randomUUID().toString(), documentId, 1, doc.getOriginalStoragePath(), originalSize,
                        null, Kind.IMPORTED, null, imported, null),
                new Version(UUID.randomUUID().toString(), documentId, 2, null, null, null, Kind.EDITED, null,
                        doc.getModifiedAt(), null));
    }

    private List<Version> rows(String documentId) {
        return jdbc.sql("SELECT * FROM document_versions WHERE document_id = ? ORDER BY version_no")
                .param(documentId).query(DocumentVersions::map).list();
    }

    private void insert(Version version) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", version.id());
        c.put("document_id", version.documentId());
        c.put("version_no", version.number());
        c.put("storage_path", version.storagePath());
        c.put("size_bytes", version.sizeBytes());
        c.put("page_count", version.pageCount());
        c.put("kind", version.kind().name());
        c.put("restored_from", version.restoredFrom());
        c.put("created_at", millis(version.createdAt()));
        c.put("created_by", version.createdBy());
        db.insert("document_versions", c);
    }

    private static Version map(ResultSet rs, int row) throws SQLException {
        return new Version(rs.getString("id"), rs.getString("document_id"), rs.getInt("version_no"),
                rs.getString("storage_path"), nullableLong(rs, "size_bytes"), nullableInt(rs, "page_count"),
                Kind.valueOf(rs.getString("kind")), nullableInt(rs, "restored_from"), instant(rs, "created_at"),
                rs.getString("created_by"));
    }

    /** Der angemeldete Anwender; null bei Vorgängen im Hintergrund. */
    private static String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return auth.getName();
    }
}
