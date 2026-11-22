package de.aidocuments.retention;

import static de.aidocuments.persistence.Db.instant;
import static de.aidocuments.persistence.Db.millis;

import de.aidocuments.persistence.Db;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Die Aufbewahrungsregeln (Tabelle {@code retention_rules}) und die Berechnung der Frist eines Dokuments.
 *
 * <p>Die Frist beginnt mit dem Ende des Kalenderjahres, aus dem das Dokument stammt (Dokumentdatum, sonst der Tag der
 * Übernahme) – wie bei den handels- und steuerrechtlichen Fristen. Eine Rechnung vom März 2025 mit zehn Jahren ist
 * also bis zum 31.12.2035 gesperrt.
 */
@Component
public class RetentionRules {

    private final Db db;
    private final JdbcClient jdbc;
    /** Kategorie in Kleinbuchstaben → Jahre; bei jeder Änderung neu geladen. */
    private volatile Map<String, Integer> byCategory;

    public RetentionRules(Db db) {
        this.db = db;
        this.jdbc = db.jdbc();
    }

    public List<RetentionRule> findAll() {
        return jdbc.sql("SELECT * FROM retention_rules ORDER BY category")
                .query((rs, row) -> new RetentionRule(rs.getString("id"), rs.getString("category"), rs.getInt("years"),
                        instant(rs, "created_at")))
                .list();
    }

    public Optional<RetentionRule> findById(String id) {
        return findAll().stream().filter(rule -> rule.id().equals(id)).findFirst();
    }

    public Optional<RetentionRule> findByCategory(String category) {
        return findAll().stream().filter(rule -> rule.category().equalsIgnoreCase(category.strip())).findFirst();
    }

    public void save(RetentionRule rule) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", rule.id());
        c.put("category", rule.category());
        c.put("years", rule.years());
        c.put("created_at", millis(rule.createdAt()));
        db.upsert("retention_rules", c);
        byCategory = null;
    }

    public void delete(String id) {
        jdbc.sql("DELETE FROM retention_rules WHERE id = ?").param(id).update();
        byCategory = null;
    }

    /**
     * Bis wann ein Dokument mit dieser Kategorie und diesem Datum nach den geltenden Regeln aufzubewahren ist;
     * {@code null} = keine Regel.
     *
     * @param date Dokumentdatum, sonst der Tag der Übernahme
     */
    public LocalDate due(String category, LocalDate date) {
        if (category == null || category.isBlank() || date == null) {
            return null;
        }
        Integer years = rules().get(category.strip().toLowerCase(Locale.ROOT));
        return years == null ? null : LocalDate.of(date.getYear() + years, 12, 31);
    }

    /** Das Datum, von dem aus gezählt wird: das Dokumentdatum, sonst der Tag der Übernahme. */
    public static LocalDate baseDate(LocalDate documentDate, java.time.Instant createdAt) {
        if (documentDate != null) {
            return documentDate;
        }
        return createdAt == null ? null : LocalDate.ofInstant(createdAt, ZoneId.systemDefault());
    }

    /** Der spätere von zwei Terminen ({@code null} = keiner). */
    public static LocalDate later(LocalDate a, LocalDate b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }

    private Map<String, Integer> rules() {
        Map<String, Integer> current = byCategory;
        if (current == null) {
            Map<String, Integer> loaded = new LinkedHashMap<>();
            findAll().forEach(rule -> loaded.put(rule.category().toLowerCase(Locale.ROOT), rule.years()));
            byCategory = current = Map.copyOf(loaded);
        }
        return current;
    }
}
