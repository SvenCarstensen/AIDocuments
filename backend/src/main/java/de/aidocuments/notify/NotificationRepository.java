package de.aidocuments.notify;

import de.aidocuments.persistence.Db;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Was für die Benachrichtigungen aus der Datenbank nötig ist – und was davon schon gemeldet wurde. */
@Repository
public class NotificationRepository {

    /** Ein Dokument, wie es in einer Benachrichtigung steht. */
    public record Entry(String id, String title, String filename, LocalDate retainUntil) {

        public String name() {
            return title != null && !title.isBlank() ? title : filename;
        }
    }

    private final JdbcClient jdbc;

    public NotificationRepository(Db db) {
        this.jdbc = db.jdbc();
    }

    /** Dokumente, deren Frist vor {@code today} endete und über die (mit dieser Frist) noch nicht benachrichtigt wurde. */
    List<Entry> unnotifiedExpired(LocalDate today) {
        return jdbc.sql("SELECT d.id, d.title, d.original_filename, d.retain_until FROM documents d "
                        + "LEFT JOIN retention_notices n ON n.document_id = d.id "
                        + "WHERE d.retain_until IS NOT NULL AND d.retain_until < ? "
                        + "AND (n.document_id IS NULL OR n.retain_until <> d.retain_until) "
                        + "ORDER BY d.retain_until, d.title")
                .param(today.toString())
                .query((rs, row) -> new Entry(rs.getString("id"), rs.getString("title"),
                        rs.getString("original_filename"), LocalDate.parse(rs.getString("retain_until"))))
                .list();
    }

    void markRetentionNotified(List<Entry> entries, Instant at) {
        for (Entry entry : entries) {
            jdbc.sql("DELETE FROM retention_notices WHERE document_id = ?").param(entry.id()).update();
            jdbc.sql("INSERT INTO retention_notices (document_id, retain_until, notified_at) VALUES (?, ?, ?)")
                    .param(entry.id()).param(entry.retainUntil().toString()).param(at.toEpochMilli()).update();
        }
    }

    /** Vermerke zu Dokumenten, die es nicht mehr gibt (auch nicht im Papierkorb). */
    void forgetVanished() {
        jdbc.sql("DELETE FROM retention_notices WHERE document_id NOT IN (SELECT id FROM documents) "
                + "AND document_id NOT IN (SELECT id FROM deleted_documents)").update();
    }

    /** Neu hinzugekommene Dokumente im Zeitraum, in den Bereichen ({@code null} = allen). */
    List<Entry> createdBetween(Instant after, Instant until, Set<String> areas) {
        if (areas != null && areas.isEmpty()) {
            return List.of();
        }
        var spec = jdbc.sql("SELECT id, title, original_filename FROM documents "
                        + "WHERE created_at > :after AND created_at <= :until"
                        + (areas != null ? " AND area_id IN (:areas)" : "") + " ORDER BY created_at")
                .param("after", after.toEpochMilli()).param("until", until.toEpochMilli());
        if (areas != null) {
            spec = spec.param("areas", areas);
        }
        return spec.query((rs, row) -> new Entry(rs.getString("id"), rs.getString("title"),
                rs.getString("original_filename"), null)).list();
    }

    /** Bis wann neue Dokumente in einer Übersicht an diese Person standen; {@code null} = noch nie. */
    Instant digestUntil(String userId) {
        List<Long> found = jdbc.sql("SELECT digest_until FROM users WHERE id = ?").param(userId)
                .query((rs, row) -> {
                    long value = rs.getLong(1);
                    return rs.wasNull() ? null : value;
                }).list();
        Long millis = found.isEmpty() ? null : found.getFirst();
        return millis == null ? null : Instant.ofEpochMilli(millis);
    }

    void setDigestUntil(String userId, Instant until) {
        jdbc.sql("UPDATE users SET digest_until = ? WHERE id = ?").param(until.toEpochMilli()).param(userId).update();
    }
}
