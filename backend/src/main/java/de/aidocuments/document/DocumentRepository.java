package de.aidocuments.document;

import static de.aidocuments.persistence.Db.enumValue;
import static de.aidocuments.persistence.Db.fromJson;
import static de.aidocuments.persistence.Db.instant;
import static de.aidocuments.persistence.Db.millis;
import static de.aidocuments.persistence.Db.name;
import static de.aidocuments.persistence.Db.nullableInt;
import static de.aidocuments.persistence.Db.toJson;

import com.fasterxml.jackson.core.type.TypeReference;
import de.aidocuments.persistence.Db;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Dokumente in der Tabelle {@code documents}; gelöschte liegen bis zum endgültigen Entfernen mit denselben Spalten
 * in {@code deleted_documents} (Papierkorb).
 */
@Repository
public class DocumentRepository {

    private static final String TABLE = "documents";
    private static final String TRASH = "deleted_documents";
    private static final TypeReference<LinkedHashSet<String>> STRING_SET = new TypeReference<>() { };
    private static final TypeReference<List<KeyFact>> KEY_FACTS = new TypeReference<>() { };

    private final Db db;
    private final JdbcClient jdbc;

    public DocumentRepository(Db db) {
        this.db = db;
        this.jdbc = db.jdbc();
    }

    public Optional<Document> findById(String id) {
        return jdbc.sql("SELECT * FROM documents WHERE id = ?").param(id).query(DocumentRepository::map).optional();
    }

    public List<Document> findAllById(Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT * FROM documents WHERE id IN (:ids)").param("ids", ids)
                .query(DocumentRepository::map).list();
    }

    public List<Document> findAll() {
        return jdbc.sql("SELECT * FROM documents ORDER BY created_at").query(DocumentRepository::map).list();
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM documents").query(Long.class).single();
    }

    public Optional<Document> findBySha256(String sha256) {
        return jdbc.sql("SELECT * FROM documents WHERE sha256 = ?").param(sha256)
                .query(DocumentRepository::map).optional();
    }

    public List<Document> findByStatusIn(Collection<DocumentStatus> statuses) {
        return jdbc.sql("SELECT * FROM documents WHERE status IN (:statuses) ORDER BY created_at")
                .param("statuses", names(statuses)).query(DocumentRepository::map).list();
    }

    public long countByStatusIn(Collection<DocumentStatus> statuses) {
        return jdbc.sql("SELECT COUNT(*) FROM documents WHERE status IN (:statuses)")
                .param("statuses", names(statuses)).query(Long.class).single();
    }

    public long countByMailAccountId(String mailAccountId) {
        return jdbc.sql("SELECT COUNT(*) FROM documents WHERE mail_account_id = ?").param(mailAccountId)
                .query(Long.class).single();
    }

    /** Die zuletzt fertig bearbeiteten Dokumente, neueste zuerst – Grundlage der Restzeit-Schätzung. */
    public List<Document> findTop25ByProcessedAtNotNullOrderByProcessedAtDesc() {
        return jdbc.sql("SELECT * FROM documents WHERE processed_at IS NOT NULL ORDER BY processed_at DESC LIMIT 25")
                .query(DocumentRepository::map).list();
    }

    public List<Document> findByFingerprintIsNotNull() {
        return jdbc.sql("SELECT * FROM documents WHERE fingerprint IS NOT NULL ORDER BY created_at")
                .query(DocumentRepository::map).list();
    }

    public List<Document> findByDuplicateOf(String duplicateOf) {
        return jdbc.sql("SELECT * FROM documents WHERE duplicate_of = ?").param(duplicateOf)
                .query(DocumentRepository::map).list();
    }

    public long countByDuplicateOfIsNotNull() {
        return jdbc.sql("SELECT COUNT(*) FROM documents WHERE duplicate_of IS NOT NULL").query(Long.class).single();
    }

    public Document save(Document doc) {
        db.upsert(TABLE, columns(doc));
        return doc;
    }

    public void delete(Document doc) {
        jdbc.sql("DELETE FROM documents WHERE id = ?").param(doc.getId()).update();
    }

    /** Beim Löschen eines Postfachs: Dokumente bleiben, verlieren aber den Verweis darauf. */
    public void clearMailAccount(String mailAccountId) {
        jdbc.sql("UPDATE documents SET mail_account_id = NULL WHERE mail_account_id = ?").param(mailAccountId).update();
    }

    // ---- Papierkorb ----

    public void saveToTrash(Document doc) {
        db.upsert(TRASH, columns(doc));
    }

    public Optional<Document> findInTrash(String id) {
        return jdbc.sql("SELECT * FROM deleted_documents WHERE id = ?").param(id)
                .query(DocumentRepository::map).optional();
    }

    public List<Document> findInTrashDeletedBefore(Instant limit) {
        return jdbc.sql("SELECT * FROM deleted_documents WHERE deleted_at < ?").param(limit.toEpochMilli())
                .query(DocumentRepository::map).list();
    }

    public void removeFromTrash(String id) {
        jdbc.sql("DELETE FROM deleted_documents WHERE id = ?").param(id).update();
    }

    // ---- Abbildung Zeile ↔ Dokument ----

    private static List<String> names(Collection<DocumentStatus> statuses) {
        return statuses.stream().map(Enum::name).toList();
    }

    private static Map<String, Object> columns(Document doc) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", doc.getId());
        c.put("original_filename", doc.getOriginalFilename());
        c.put("storage_path", doc.getStoragePath());
        c.put("sha256", doc.getSha256());
        c.put("size_bytes", doc.getSizeBytes());
        c.put("page_count", doc.getPageCount());
        c.put("source", name(doc.getSource()));
        c.put("status", name(doc.getStatus()));
        c.put("error_message", doc.getErrorMessage());
        c.put("title", doc.getTitle());
        c.put("summary", doc.getSummary());
        c.put("category", doc.getCategory());
        c.put("correspondent", doc.getCorrespondent());
        c.put("document_date", doc.getDocumentDate() == null ? null : doc.getDocumentDate().toString());
        c.put("language", doc.getLanguage());
        c.put("ocr_used", doc.isOcrUsed());
        c.put("has_thumbnail", doc.isHasThumbnail());
        c.put("mail_account_id", doc.getMailAccountId());
        c.put("mail_subject", doc.getMailSubject());
        c.put("mail_from", doc.getMailFrom());
        c.put("mail_received_at", millis(doc.getMailReceivedAt()));
        c.put("created_at", millis(doc.getCreatedAt()));
        c.put("processed_at", millis(doc.getProcessedAt()));
        c.put("original_storage_path", doc.getOriginalStoragePath());
        c.put("modified_at", millis(doc.getModifiedAt()));
        c.put("thumbnail_at", millis(doc.getThumbnailAt()));
        c.put("deleted_at", millis(doc.getDeletedAt()));
        c.put("fingerprint", toJson(doc.getFingerprint()));
        c.put("duplicate_of", doc.getDuplicateOf());
        c.put("not_duplicate_of", doc.getNotDuplicateOf().isEmpty() ? null : toJson(doc.getNotDuplicateOf()));
        c.put("tags", toJson(doc.getTags()));
        c.put("key_facts", toJson(doc.getKeyFacts()));
        return c;
    }

    private static Document map(ResultSet rs, int row) throws SQLException {
        Document doc = new Document();
        doc.restoreIdentity(rs.getString("id"), rs.getString("original_filename"), rs.getString("storage_path"),
                rs.getString("sha256"), enumValue(rs, "source", DocumentSource.class), instant(rs, "created_at"));
        doc.setSizeBytes(rs.getLong("size_bytes"));
        doc.setPageCount(nullableInt(rs, "page_count"));
        doc.setStatus(enumValue(rs, "status", DocumentStatus.class));
        doc.setErrorMessage(rs.getString("error_message"));
        doc.setTitle(rs.getString("title"));
        doc.setSummary(rs.getString("summary"));
        doc.setCategory(rs.getString("category"));
        doc.setCorrespondent(rs.getString("correspondent"));
        String date = rs.getString("document_date");
        doc.setDocumentDate(date == null ? null : LocalDate.parse(date));
        doc.setLanguage(rs.getString("language"));
        doc.setOcrUsed(rs.getBoolean("ocr_used"));
        doc.setHasThumbnail(rs.getBoolean("has_thumbnail"));
        doc.setMailOrigin(rs.getString("mail_account_id"), rs.getString("mail_subject"), rs.getString("mail_from"),
                instant(rs, "mail_received_at"));
        doc.setProcessedAt(instant(rs, "processed_at"));
        doc.setOriginalStoragePath(rs.getString("original_storage_path"));
        doc.setModifiedAt(instant(rs, "modified_at"));
        doc.setThumbnailAt(instant(rs, "thumbnail_at"));
        doc.setDeletedAt(instant(rs, "deleted_at"));
        doc.setFingerprint(fromJson(rs.getString("fingerprint"), ContentFingerprint.class));
        doc.setDuplicateOf(rs.getString("duplicate_of"));
        doc.restoreNotDuplicateOf(fromJson(rs.getString("not_duplicate_of"), STRING_SET));
        LinkedHashSet<String> tags = fromJson(rs.getString("tags"), STRING_SET);
        doc.replaceTags(tags == null ? List.of() : tags);
        List<KeyFact> facts = fromJson(rs.getString("key_facts"), KEY_FACTS);
        doc.replaceKeyFacts(facts == null ? new ArrayList<>() : facts);
        return doc;
    }
}
