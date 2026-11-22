package de.aidocuments.history;

import static de.aidocuments.persistence.Db.enumValue;
import static de.aidocuments.persistence.Db.fromJson;
import static de.aidocuments.persistence.Db.instant;
import static de.aidocuments.persistence.Db.millis;
import static de.aidocuments.persistence.Db.name;
import static de.aidocuments.persistence.Db.toJson;

import de.aidocuments.document.DocumentSource;
import de.aidocuments.history.HistoryEntry.Action;
import de.aidocuments.history.HistoryEntry.Change;
import de.aidocuments.history.HistoryEntry.Details;
import de.aidocuments.persistence.Db;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Einträge der Historie in der Tabelle {@code history}. */
@Repository
public class HistoryRepository {

    private final Db db;
    private final JdbcClient jdbc;

    public HistoryRepository(Db db) {
        this.db = db;
        this.jdbc = db.jdbc();
    }

    public Optional<HistoryEntry> findById(String id) {
        return jdbc.sql("SELECT * FROM history WHERE id = ?").param(id).query(HistoryRepository::map).optional();
    }

    public Page<HistoryEntry> findAllByOrderByAtDesc(Pageable pageable) {
        List<HistoryEntry> content = jdbc.sql("SELECT * FROM history ORDER BY occurred_at DESC, id DESC"
                        + " LIMIT " + pageable.getPageSize() + " OFFSET " + pageable.getOffset())
                .query(HistoryRepository::map).list();
        long total = jdbc.sql("SELECT COUNT(*) FROM history").query(Long.class).single();
        return new PageImpl<>(content, pageable, total);
    }

    /** Gibt es zu dem Dokument einen späteren, noch geltenden Schritt? Dann lässt sich dieser nicht mehr zurücknehmen. */
    public boolean existsByDocumentIdAndAtAfterAndUndoneAtIsNull(String documentId, Instant at) {
        return jdbc.sql("SELECT COUNT(*) FROM history WHERE document_id = ? AND occurred_at > ? AND undone_at IS NULL")
                .param(documentId).param(at.toEpochMilli()).query(Long.class).single() > 0;
    }

    public HistoryEntry save(HistoryEntry entry) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", entry.id());
        c.put("occurred_at", millis(entry.at()));
        c.put("action", name(entry.action()));
        c.put("document_id", entry.documentId());
        c.put("document_title", entry.documentTitle());
        c.put("source", name(entry.source()));
        c.put("origin", entry.origin());
        c.put("change_type", name(entry.change()));
        c.put("user_name", entry.user());
        c.put("before_details", toJson(entry.before()));
        c.put("previous_file", entry.previousFile());
        c.put("undone_at", millis(entry.undoneAt()));
        c.put("undone_by", entry.undoneBy());
        db.upsert("history", c);
        return entry;
    }

    private static HistoryEntry map(ResultSet rs, int row) throws SQLException {
        return new HistoryEntry(
                rs.getString("id"),
                instant(rs, "occurred_at"),
                enumValue(rs, "action", Action.class),
                rs.getString("document_id"),
                rs.getString("document_title"),
                enumValue(rs, "source", DocumentSource.class),
                rs.getString("origin"),
                enumValue(rs, "change_type", Change.class),
                rs.getString("user_name"),
                fromJson(rs.getString("before_details"), Details.class),
                rs.getString("previous_file"),
                instant(rs, "undone_at"),
                rs.getString("undone_by"));
    }
}
