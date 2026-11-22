package de.aidocuments.contract;

import static de.aidocuments.persistence.Db.instant;
import static de.aidocuments.persistence.Db.millis;
import static de.aidocuments.persistence.Db.nullableInt;

import de.aidocuments.persistence.Db;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ContractRepository {

    private final Db db;
    private final JdbcClient jdbc;

    public ContractRepository(Db db) {
        this.db = db;
        this.jdbc = db.jdbc();
    }

    public Optional<Contract> findByDocument(String documentId) {
        return jdbc.sql("SELECT * FROM contracts WHERE document_id = ?").param(documentId)
                .query(ContractRepository::map).optional();
    }

    /** Verträge an Dokumenten der Bereiche ({@code null} = alle) – Dokumente im Papierkorb nicht. */
    public List<Contract> findVisible(Set<String> areas) {
        if (areas != null && areas.isEmpty()) {
            return List.of();
        }
        String sql = "SELECT c.* FROM contracts c JOIN documents d ON d.id = c.document_id"
                + (areas != null ? " WHERE d.area_id IN (:areas)" : "");
        var spec = jdbc.sql(sql);
        if (areas != null) {
            spec = spec.param("areas", areas);
        }
        return spec.query(ContractRepository::map).list();
    }

    /** Verträge, die sich verlängern und deren Laufzeit vor {@code today} abgelaufen ist. */
    public List<Contract> findToRenew(LocalDate today) {
        return jdbc.sql("SELECT c.* FROM contracts c JOIN documents d ON d.id = c.document_id WHERE c.cancelled = ?"
                        + " AND c.renewal_months > 0 AND c.end_date IS NOT NULL AND c.end_date < ?")
                .param(false).param(today.toString()).query(ContractRepository::map).list();
    }

    public void save(Contract c) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("document_id", c.documentId());
        row.put("partner", c.partner());
        row.put("start_date", text(c.startDate()));
        row.put("end_date", text(c.endDate()));
        row.put("notice_months", c.noticeMonths());
        row.put("renewal_months", c.renewalMonths());
        row.put("amount", c.amount());
        row.put("reminder_days", c.reminderDays());
        row.put("cancelled", c.cancelled());
        row.put("reminder_id", c.reminderId());
        row.put("assignee_id", c.assigneeId());
        row.put("created_by", c.createdBy());
        row.put("created_at", millis(c.createdAt()));
        row.put("updated_at", millis(c.updatedAt()));
        db.upsert("contracts", row);
    }

    public void delete(String documentId) {
        jdbc.sql("DELETE FROM contracts WHERE document_id = ?").param(documentId).update();
    }

    /** Das Konto ist gelöscht: die Erinnerungen gehen an niemanden Bestimmten mehr. */
    public void unassign(String userId) {
        jdbc.sql("UPDATE contracts SET assignee_id = NULL WHERE assignee_id = ?").param(userId).update();
    }

    private static String text(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : LocalDate.parse(value);
    }

    private static Contract map(ResultSet rs, int row) throws SQLException {
        return new Contract(rs.getString("document_id"), rs.getString("partner"), date(rs, "start_date"),
                date(rs, "end_date"), nullableInt(rs, "notice_months"), nullableInt(rs, "renewal_months"),
                rs.getString("amount"), rs.getInt("reminder_days"), rs.getBoolean("cancelled"),
                rs.getString("reminder_id"), rs.getString("assignee_id"), rs.getString("created_by"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }
}
