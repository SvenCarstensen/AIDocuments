package de.aidocuments.importer;

import de.aidocuments.persistence.Db;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Schon geprüfte Dateien des Eingangsordners (Tabelle {@code imported_files}). Schlüssel ist der SHA-256 des Pfads –
 * Pfade können länger sein, als ein Index in MariaDB erlaubt.
 */
@Repository
public class ImportedFileRepository {

    private final Db db;

    public ImportedFileRepository(Db db) {
        this.db = db;
    }

    public Optional<ImportedFile> findById(String path) {
        return db.jdbc().sql("SELECT * FROM imported_files WHERE path_hash = ?").param(hash(path))
                .query((rs, row) -> new ImportedFile(rs.getString("path"), rs.getLong("size"),
                        rs.getLong("modified_at"), rs.getString("outcome"), Db.instant(rs, "checked_at")))
                .optional();
    }

    public void save(ImportedFile file) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("path_hash", hash(file.path()));
        c.put("path", file.path());
        c.put("size", file.size());
        c.put("modified_at", file.modifiedAt());
        c.put("outcome", file.outcome());
        c.put("checked_at", Db.millis(file.checkedAt()));
        db.upsert("imported_files", c);
    }

    static String hash(String path) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
