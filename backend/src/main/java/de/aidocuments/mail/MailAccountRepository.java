package de.aidocuments.mail;

import static de.aidocuments.persistence.Db.enumValue;
import static de.aidocuments.persistence.Db.instant;
import static de.aidocuments.persistence.Db.millis;
import static de.aidocuments.persistence.Db.name;
import static de.aidocuments.persistence.Db.nullableInt;
import static de.aidocuments.persistence.Db.nullableLong;

import de.aidocuments.persistence.Db;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Postfächer in der Tabelle {@code mail_accounts}. */
@Repository
public class MailAccountRepository {

    private final Db db;
    private final JdbcClient jdbc;

    public MailAccountRepository(Db db) {
        this.db = db;
        this.jdbc = db.jdbc();
    }

    public Optional<MailAccount> findById(String id) {
        return jdbc.sql("SELECT * FROM mail_accounts WHERE id = ?").param(id)
                .query(MailAccountRepository::map).optional();
    }

    public List<MailAccount> findAll() {
        return jdbc.sql("SELECT * FROM mail_accounts ORDER BY created_at").query(MailAccountRepository::map).list();
    }

    public List<MailAccount> findByEnabledTrue() {
        return jdbc.sql("SELECT * FROM mail_accounts WHERE enabled = ? ORDER BY created_at").param(true)
                .query(MailAccountRepository::map).list();
    }

    public MailAccount save(MailAccount account) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", account.getId());
        c.put("name", account.getName());
        c.put("host", account.getHost());
        c.put("port", account.getPort());
        c.put("security", name(account.getSecurity()));
        c.put("username", account.getUsername());
        c.put("password_encrypted", account.getPasswordEncrypted());
        c.put("folder", account.getFolder());
        c.put("import_since", account.getImportSince() == null ? null : account.getImportSince().toString());
        c.put("enabled", account.isEnabled());
        MailFilter filter = account.getFilter();
        c.put("subject_include", MailFilter.join(filter.subjectIncludes()));
        c.put("subject_exclude", MailFilter.join(filter.subjectExcludes()));
        c.put("sender_include", MailFilter.join(filter.senderIncludes()));
        c.put("sender_exclude", MailFilter.join(filter.senderExcludes()));
        c.put("interval_minutes", account.getIntervalMinutes());
        c.put("area_id", account.getAreaId());
        c.put("uid_validity", account.getUidValidity());
        c.put("last_uid", account.getLastUid());
        c.put("checked_until", millis(account.getCheckedUntil()));
        c.put("last_sync_at", millis(account.getLastSyncAt()));
        c.put("last_sync_status", name(account.getLastSyncStatus()));
        c.put("last_sync_message", account.getLastSyncMessage());
        c.put("created_at", millis(account.getCreatedAt()));
        db.upsert("mail_accounts", c);
        return account;
    }

    public void delete(MailAccount account) {
        jdbc.sql("DELETE FROM mail_accounts WHERE id = ?").param(account.getId()).update();
    }

    // --- Abrufstand: gezielte Feld-Updates, damit ein parallel gespeichertes Formular nichts überschreibt ---

    void markRunning(String id) {
        jdbc.sql("UPDATE mail_accounts SET last_sync_status = ? WHERE id = ?")
                .param(MailAccount.SyncStatus.RUNNING.name()).param(id).update();
    }

    void saveCursor(String id, long uidValidity, long lastUid) {
        jdbc.sql("UPDATE mail_accounts SET uid_validity = ?, last_uid = ? WHERE id = ?")
                .param(uidValidity).param(lastUid).param(id).update();
    }

    /** Nur nach einem erfolgreichen Abruf: E-Mails, die vor {@code startedAt} empfangen wurden, sind geprüft. */
    void saveCheckedUntil(String id, Instant startedAt) {
        jdbc.sql("UPDATE mail_accounts SET checked_until = ? WHERE id = ?").param(startedAt.toEpochMilli()).param(id)
                .update();
    }

    void markFinished(String id, MailAccount.SyncStatus status, String message, Instant at) {
        jdbc.sql("UPDATE mail_accounts SET last_sync_status = ?, last_sync_message = ?, last_sync_at = ? WHERE id = ?")
                .param(status.name()).param(message).param(at.toEpochMilli()).param(id).update();
    }

    /** Zählt einen fehlgeschlagenen Abruf. @return wie viele in Folge, mit diesem */
    int recordFailure(String id) {
        jdbc.sql("UPDATE mail_accounts SET failures = failures + 1 WHERE id = ?").param(id).update();
        return jdbc.sql("SELECT failures FROM mail_accounts WHERE id = ?").param(id).query(Integer.class).optional()
                .orElse(0);
    }

    /** Nach einem erfolgreichen Abruf: Fehlerserie vorbei, ein neuer Fehler wird wieder gemeldet. */
    void resetFailures(String id) {
        jdbc.sql("UPDATE mail_accounts SET failures = 0, failure_notified_at = NULL WHERE id = ? "
                + "AND (failures <> 0 OR failure_notified_at IS NOT NULL)").param(id).update();
    }

    /** Ob über die laufende Fehlerserie schon benachrichtigt wurde. */
    boolean failureNotified(String id) {
        return jdbc.sql("SELECT COUNT(*) FROM mail_accounts WHERE id = ? AND failure_notified_at IS NOT NULL")
                .param(id).query(Long.class).single() > 0;
    }

    /** Namen der aktiven Postfächer, die sich mindestens {@code failures}-mal in Folge nicht abrufen ließen. */
    public List<String> failingNames(int failures) {
        return jdbc.sql("SELECT name FROM mail_accounts WHERE enabled = ? AND failures >= ? ORDER BY name")
                .param(true).param(failures).query(String.class).list();
    }

    void markFailureNotified(String id, Instant at) {
        jdbc.sql("UPDATE mail_accounts SET failure_notified_at = ? WHERE id = ?").param(at.toEpochMilli()).param(id)
                .update();
    }

    private static MailAccount map(ResultSet rs, int row) throws SQLException {
        MailAccount account = new MailAccount(rs.getString("id"));
        account.setName(rs.getString("name"));
        account.setHost(rs.getString("host"));
        account.setPort(rs.getInt("port"));
        account.setSecurity(enumValue(rs, "security", MailSecurity.class));
        account.setUsername(rs.getString("username"));
        account.setPasswordEncrypted(rs.getString("password_encrypted"));
        account.setFolder(rs.getString("folder"));
        String since = rs.getString("import_since");
        account.setImportSince(since == null ? null : LocalDate.parse(since));
        account.setEnabled(rs.getBoolean("enabled"));
        account.setFilter(new MailFilter(MailFilter.split(rs.getString("subject_include")),
                MailFilter.split(rs.getString("subject_exclude")), MailFilter.split(rs.getString("sender_include")),
                MailFilter.split(rs.getString("sender_exclude"))));
        account.setIntervalMinutes(nullableInt(rs, "interval_minutes"));
        account.setAreaId(rs.getString("area_id"));
        account.restoreState(nullableLong(rs, "uid_validity"), nullableLong(rs, "last_uid"),
                instant(rs, "checked_until"), instant(rs, "last_sync_at"), enumValue(rs, "last_sync_status", MailAccount.SyncStatus.class),
                rs.getString("last_sync_message"), instant(rs, "created_at"));
        return account;
    }
}
