package de.aidocuments.document;

import de.aidocuments.persistence.Db;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Volltexte in der Tabelle {@code document_texts}. */
@Repository
public class DocumentTextRepository {

    private final Db db;
    private final JdbcClient jdbc;

    public DocumentTextRepository(Db db) {
        this.db = db;
        this.jdbc = db.jdbc();
    }

    public Optional<DocumentText> findById(String documentId) {
        return jdbc.sql("SELECT document_id, content FROM document_texts WHERE document_id = ?").param(documentId)
                .query((rs, row) -> new DocumentText(rs.getString("document_id"), rs.getString("content")))
                .optional();
    }

    public List<DocumentText> findAll() {
        return jdbc.sql("SELECT document_id, content FROM document_texts")
                .query((rs, row) -> new DocumentText(rs.getString("document_id"), rs.getString("content")))
                .list();
    }

    public DocumentText save(DocumentText text) {
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("document_id", text.documentId());
        columns.put("content", text.content());
        db.upsert("document_texts", columns);
        return text;
    }

    public void deleteById(String documentId) {
        jdbc.sql("DELETE FROM document_texts WHERE document_id = ?").param(documentId).update();
    }
}
