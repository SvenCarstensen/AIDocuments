package de.aidocuments.reminder;

import static de.aidocuments.persistence.Db.enumValue;
import static de.aidocuments.persistence.Db.instant;
import static de.aidocuments.persistence.Db.nullableInt;
import static de.aidocuments.persistence.Db.millis;

import de.aidocuments.persistence.Db;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Termine in der Tabelle {@code reminders}; sichtbar ist ein Termin, wenn sein Dokument es ist (Bereich). */
@Repository
public class ReminderRepository {

    private final Db db;
    private final JdbcClient jdbc;

    public ReminderRepository(Db db) {
        this.db = db;
        this.jdbc = db.jdbc();
    }

    public Optional<Reminder> findById(String id) {
        return jdbc.sql("SELECT * FROM reminders WHERE id = ?").param(id).query(ReminderRepository::map).optional();
    }

    public List<Reminder> findByDocument(String documentId) {
        return jdbc.sql("SELECT * FROM reminders WHERE document_id = ? ORDER BY due_date").param(documentId)
                .query(ReminderRepository::map).list();
    }

    /**
     * Offene (oder auch erledigte) Termine an Dokumenten der sichtbaren Bereiche – Dokumente im Papierkorb nicht.
     *
     * @param areas {@code null} = alle Bereiche
     * @param until nur fällig bis einschließlich; {@code null} = alle
     * @param assigneeId nur diese Person; {@code null} = alle
     */
    public List<Reminder> find(Set<String> areas, Collection<Reminder.Status> statuses, LocalDate until,
                               String assigneeId) {
        if (areas != null && areas.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("SELECT r.* FROM reminders r JOIN documents d ON d.id = r.document_id "
                + "WHERE r.status IN (:statuses)");
        if (areas != null) {
            sql.append(" AND d.area_id IN (:areas)");
        }
        if (until != null) {
            sql.append(" AND r.due_date <= :until");
        }
        if (assigneeId != null) {
            sql.append(" AND r.assignee_id = :assignee");
        }
        sql.append(" ORDER BY r.due_date, r.created_at");
        var spec = jdbc.sql(sql.toString()).param("statuses", statuses.stream().map(Enum::name).toList());
        if (areas != null) {
            spec = spec.param("areas", areas);
        }
        if (until != null) {
            spec = spec.param("until", until.toString());
        }
        if (assigneeId != null) {
            spec = spec.param("assignee", assigneeId);
        }
        return spec.query(ReminderRepository::map).list();
    }

    /** Fällige offene Termine, zu denen noch keine E-Mail ging. */
    public List<Reminder> findDueUnnotified(LocalDate today) {
        return jdbc.sql("SELECT r.* FROM reminders r JOIN documents d ON d.id = r.document_id "
                        + "WHERE r.status = 'OPEN' AND r.due_date <= ? AND r.notified_at IS NULL AND r.assignee_id IS NOT NULL")
                .param(today.toString()).query(ReminderRepository::map).list();
    }

    public void save(Reminder r) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", r.id());
        c.put("document_id", r.documentId());
        c.put("due_date", r.dueDate().toString());
        c.put("note", r.note());
        c.put("assignee_id", r.assigneeId());
        c.put("status", r.status().name());
        c.put("source", r.source().name());
        c.put("created_by", r.createdBy());
        c.put("created_at", millis(r.createdAt()));
        c.put("done_at", millis(r.doneAt()));
        c.put("done_by", r.doneBy());
        c.put("notified_at", millis(r.notifiedAt()));
        c.put("repeat_months", r.repeatMonths());
        db.upsert("reminders", c);
    }

    public void markNotified(Collection<String> ids, Instant at) {
        if (!ids.isEmpty()) {
            jdbc.sql("UPDATE reminders SET notified_at = :at WHERE id IN (:ids)").param("at", at.toEpochMilli())
                    .param("ids", ids).update();
        }
    }

    public void delete(String id) {
        jdbc.sql("DELETE FROM reminders WHERE id = ?").param(id).update();
    }

    /** Das Dokument ist endgültig gelöscht. */
    public void forgetDocument(String documentId) {
        jdbc.sql("DELETE FROM reminders WHERE document_id = ?").param(documentId).update();
    }

    /** Das Konto ist gelöscht: seine Termine gehören niemandem Bestimmten mehr. */
    public void unassign(String userId) {
        jdbc.sql("UPDATE reminders SET assignee_id = NULL WHERE assignee_id = ?").param(userId).update();
    }

    private static Reminder map(ResultSet rs, int row) throws SQLException {
        return new Reminder(rs.getString("id"), rs.getString("document_id"), LocalDate.parse(rs.getString("due_date")),
                rs.getString("note"), rs.getString("assignee_id"), enumValue(rs, "status", Reminder.Status.class),
                enumValue(rs, "source", Reminder.Source.class), rs.getString("created_by"), instant(rs, "created_at"),
                instant(rs, "done_at"), rs.getString("done_by"), instant(rs, "notified_at"),
                nullableInt(rs, "repeat_months"));
    }
}
