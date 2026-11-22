package de.aidocuments.persistence;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Repository;

/**
 * In der Oberfläche gespeicherte Einstellungen: je Bereich ein Datensatz in der Tabelle {@code settings}, der Inhalt
 * als JSON. Neue Einstellungen brauchen so keine Schemaänderung.
 */
@Repository
public class SettingsStore {

    private final Db db;

    public SettingsStore(Db db) {
        this.db = db;
    }

    /** @return die gespeicherten Einstellungen oder null, solange keine gespeichert wurden */
    public <T> T find(String id, Class<T> type) {
        return db.jdbc().sql("SELECT data FROM settings WHERE id = ?").param(id).query(String.class).optional()
                .map(json -> Db.fromJson(json, type))
                .orElse(null);
    }

    public void save(String id, Object settings) {
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("id", id);
        columns.put("data", Db.toJson(settings));
        db.upsert("settings", columns);
    }
}
