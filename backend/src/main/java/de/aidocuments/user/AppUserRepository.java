package de.aidocuments.user;

import static de.aidocuments.persistence.Db.enumValue;
import static de.aidocuments.persistence.Db.instant;
import static de.aidocuments.persistence.Db.millis;
import static de.aidocuments.persistence.Db.name;
import static de.aidocuments.persistence.Db.nullableLong;

import com.fasterxml.jackson.core.type.TypeReference;
import de.aidocuments.persistence.Db;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Benutzerkonten in der Tabelle {@code users}. */
@Repository
public class AppUserRepository {

    private final Db db;
    private final JdbcClient jdbc;

    public AppUserRepository(Db db) {
        this.db = db;
        this.jdbc = db.jdbc();
    }

    public Optional<AppUser> findById(String id) {
        return jdbc.sql("SELECT * FROM users WHERE id = ?").param(id).query(AppUserRepository::map).optional();
    }

    public Optional<AppUser> findByUsername(String username) {
        return jdbc.sql("SELECT * FROM users WHERE username = ?").param(username)
                .query(AppUserRepository::map).optional();
    }

    public List<AppUser> findAll() {
        return jdbc.sql("SELECT * FROM users ORDER BY created_at").query(AppUserRepository::map).list();
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM users").query(Long.class).single();
    }

    public long countByRoleAndEnabled(Role role, boolean enabled) {
        return jdbc.sql("SELECT COUNT(*) FROM users WHERE role = ? AND enabled = ?").param(role.name()).param(enabled)
                .query(Long.class).single();
    }

    /** Ein schon vergebener Benutzername führt zu einer {@link org.springframework.dao.DuplicateKeyException}. */
    public AppUser save(AppUser user) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", user.getId());
        c.put("username", user.getUsername());
        c.put("password_hash", user.getPasswordHash());
        c.put("role", name(user.getRole()));
        c.put("enabled", user.isEnabled());
        c.put("created_at", millis(user.getCreatedAt()));
        c.put("last_login_at", millis(user.getLastLoginAt()));
        c.put("totp_secret", user.getTotpSecretEncrypted());
        c.put("totp_last_step", user.getTotpLastStep());
        c.put("recovery_codes", user.isTwoFactorEnabled() ? Db.toJson(user.getRecoveryCodeHashes()) : null);
        db.upsert("users", c);
        return user;
    }

    public void delete(AppUser user) {
        jdbc.sql("DELETE FROM users WHERE id = ?").param(user.getId()).update();
    }

    private static AppUser map(ResultSet rs, int row) throws SQLException {
        AppUser user = new AppUser(rs.getString("id"), rs.getString("username"), rs.getString("password_hash"),
                enumValue(rs, "role", Role.class));
        user.setEnabled(rs.getBoolean("enabled"));
        user.restoreCreatedAt(instant(rs, "created_at"));
        user.setLastLoginAt(instant(rs, "last_login_at"));
        String recovery = rs.getString("recovery_codes");
        user.setTwoFactor(rs.getString("totp_secret"), nullableLong(rs, "totp_last_step"),
                recovery == null ? List.of() : Db.fromJson(recovery, new TypeReference<List<String>>() { }));
        return user;
    }
}
