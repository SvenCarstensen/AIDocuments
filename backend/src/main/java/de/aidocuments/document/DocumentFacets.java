package de.aidocuments.document;

import com.fasterxml.jackson.core.type.TypeReference;
import de.aidocuments.document.DocumentDtos.FacetsDto;
import de.aidocuments.persistence.Db;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Zählt, wie oft Dokumentarten, Absender und Schlagworte vorkommen – Grundlage der Filterleiste. */
@Component
public class DocumentFacets {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final de.aidocuments.area.AreaAccess access;

    public DocumentFacets(Db db, de.aidocuments.area.AreaAccess access) {
        this.jdbc = db.jdbc();
        this.access = access;
    }

    /** Nur Dokumente aus den sichtbaren Bereichen zählen – auch Absender und Schlagworte verraten sonst etwas. */
    private String areaClause(java.util.Set<String> areas) {
        if (areas == null) {
            return "";
        }
        return areas.isEmpty() ? " AND 1 = 0" : " AND area_id IN (:areas)";
    }

    private org.springframework.jdbc.core.simple.JdbcClient.StatementSpec withAreas(
            org.springframework.jdbc.core.simple.JdbcClient.StatementSpec spec, java.util.Set<String> areas) {
        return areas == null || areas.isEmpty() ? spec : spec.param("areas", areas);
    }

    public FacetsDto all() {
        return new FacetsDto(categories(), correspondents(50), tags(100));
    }

    public List<FacetCount> categories() {
        return count("category", 100);
    }

    public List<FacetCount> correspondents(int max) {
        return count("correspondent", max);
    }

    /** Schlagworte liegen als JSON-Liste je Dokument – gezählt wird hier, das bleibt in SQLite und MariaDB gleich. */
    public List<FacetCount> tags(int max) {
        Map<String, Long> counts = new HashMap<>();
        java.util.Set<String> areas = access.visible();
        withAreas(jdbc.sql("SELECT tags FROM documents WHERE tags IS NOT NULL" + areaClause(areas)), areas)
                .query(String.class).list().forEach(json -> {
            List<String> tags = Db.fromJson(json, STRINGS);
            if (tags != null) {
                tags.stream().distinct().forEach(tag -> counts.merge(tag, 1L, Long::sum));
            }
        });
        return counts.entrySet().stream()
                .map(e -> new FacetCount(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(FacetCount::count).reversed().thenComparing(FacetCount::name))
                .limit(max)
                .toList();
    }

    private List<FacetCount> count(String column, int max) {
        java.util.Set<String> areas = access.visible();
        return withAreas(jdbc.sql("SELECT " + column + " AS facet_value, COUNT(*) AS facet_count FROM documents"
                        + " WHERE " + column + " IS NOT NULL" + areaClause(areas) + " GROUP BY " + column
                        + " ORDER BY facet_count DESC, facet_value ASC LIMIT " + max), areas)
                .query((rs, row) -> new FacetCount(rs.getString("facet_value"), rs.getLong("facet_count")))
                .list();
    }
}
