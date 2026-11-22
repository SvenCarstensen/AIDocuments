package de.aidocuments.area;

import static de.aidocuments.persistence.Db.instant;
import static de.aidocuments.persistence.Db.millis;

import de.aidocuments.persistence.Db;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Bereiche ({@code areas}) und ihre Mitglieder ({@code area_members}). */
@Repository
public class AreaRepository {

    private final Db db;
    private final JdbcClient jdbc;

    public AreaRepository(Db db) {
        this.db = db;
        this.jdbc = db.jdbc();
    }

    public List<Area> findAll() {
        return jdbc.sql("SELECT * FROM areas ORDER BY created_at, id")
                .query((rs, row) -> new Area(rs.getString("id"), rs.getString("name"), rs.getBoolean("everyone"),
                        Permission.parse(rs.getString("everyone_access")), instant(rs, "created_at")))
                .list();
    }

    public Optional<Area> findById(String id) {
        return findAll().stream().filter(area -> area.id().equals(id)).findFirst();
    }

    public void save(Area area) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", area.id());
        c.put("name", area.name());
        c.put("everyone", area.everyone());
        c.put("everyone_access", area.everyoneAccess().name());
        c.put("created_at", millis(area.createdAt()));
        db.upsert("areas", c);
    }

    public void delete(String id) {
        jdbc.sql("DELETE FROM area_members WHERE area_id = ?").param(id).update();
        jdbc.sql("DELETE FROM areas WHERE id = ?").param(id).update();
    }

    /** Mitglieder je Bereich: Benutzer-ID → Recht. */
    public Map<String, Map<String, Permission>> members() {
        Map<String, Map<String, Permission>> result = new LinkedHashMap<>();
        jdbc.sql("SELECT area_id, user_id, access FROM area_members ORDER BY area_id").query((rs, row) -> {
            result.computeIfAbsent(rs.getString("area_id"), k -> new LinkedHashMap<>())
                    .put(rs.getString("user_id"), Permission.parse(rs.getString("access")));
            return null;
        }).list();
        return result;
    }

    public void replaceMembers(String areaId, Map<String, Permission> members) {
        jdbc.sql("DELETE FROM area_members WHERE area_id = ?").param(areaId).update();
        members.forEach((userId, access) ->
                db.insert("area_members", Map.of("area_id", areaId, "user_id", userId, "access", access.name())));
    }

    public Set<String> userIds() {
        return new LinkedHashSet<>(jdbc.sql("SELECT id FROM users").query(String.class).list());
    }

    /** Beim Löschen eines Kontos. */
    public void removeUser(String userId) {
        jdbc.sql("DELETE FROM area_members WHERE user_id = ?").param(userId).update();
    }

    /** Die Bereiche, die ein Benutzer sieht: die für alle freigegebenen und die, in denen er Mitglied ist. */
    public Set<String> visibleFor(String userId) {
        return permissionsFor(userId).keySet();
    }

    /**
     * Was ein Benutzer in welchem Bereich darf. Ist er Mitglied eines für alle freigegebenen Bereichs, gilt das höhere
     * der beiden Rechte. Bereiche ohne Recht fehlen.
     */
    public Map<String, Permission> permissionsFor(String userId) {
        Map<String, Permission> result = new LinkedHashMap<>();
        jdbc.sql("SELECT id, everyone_access FROM areas WHERE everyone = ? ORDER BY created_at, id").param(true)
                .query((rs, row) -> result.put(rs.getString("id"), Permission.parse(rs.getString("everyone_access"))))
                .list();
        jdbc.sql("SELECT a.id, m.access FROM areas a JOIN area_members m ON m.area_id = a.id WHERE m.user_id = ?"
                        + " ORDER BY a.created_at, a.id").param(userId)
                .query((rs, row) -> result.merge(rs.getString("id"), Permission.parse(rs.getString("access")),
                        Permission::max))
                .list();
        return result;
    }

    /** Wie viele Dokumente (auch im Papierkorb) und Postfächer den Bereich nutzen. */
    public long usage(String areaId) {
        return jdbc.sql("SELECT COUNT(*) FROM documents WHERE area_id = ?").param(areaId).query(Long.class).single()
                + jdbc.sql("SELECT COUNT(*) FROM deleted_documents WHERE area_id = ?").param(areaId)
                .query(Long.class).single()
                + jdbc.sql("SELECT COUNT(*) FROM mail_accounts WHERE area_id = ?").param(areaId)
                .query(Long.class).single();
    }

    public Map<String, Long> documentCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.sql("SELECT area_id, COUNT(*) AS n FROM documents GROUP BY area_id")
                .query((rs, row) -> counts.put(rs.getString("area_id"), rs.getLong("n"))).list();
        return counts;
    }
}
