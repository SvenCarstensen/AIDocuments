package de.aidocuments.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Gemeinsame Helfer für alle Datenbankzugriffe – eingebaut SQLite, extern MariaDB. Die Abfragen sind bewusst
 * schlichtes SQL, das beide verstehen. Zeitpunkte liegen als Millisekunden seit 1970 (UTC) in BIGINT-Spalten,
 * Listen und verschachtelte Werte als JSON-Text.
 */
@Component
public class Db {

    private static final ObjectMapper JSON = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final JdbcClient jdbc;

    public Db(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    /**
     * Speichert einen Datensatz: ändert ihn, wenn es ihn gibt, sonst wird er angelegt. Verletzt das einen
     * eindeutigen Index (z. B. gleiche Prüfsumme, gleicher Benutzername), kommt eine {@link DuplicateKeyException}.
     *
     * @param values Spalten und Werte; der erste Eintrag ist der Schlüssel
     */
    public void upsert(String table, Map<String, ?> values) {
        String key = values.keySet().iterator().next();
        String assignments = values.keySet().stream().skip(1).map(c -> c + " = :" + c)
                .collect(Collectors.joining(", "));
        try {
            int updated = jdbc.sql("UPDATE " + table + " SET " + assignments + " WHERE " + key + " = :" + key)
                    .params(values).update();
            if (updated == 0) {
                insert(table, values);
            }
        } catch (DataAccessException e) {
            throw translate(e);
        }
    }

    /** Legt einen Datensatz an; bei verletztem eindeutigen Index kommt eine {@link DuplicateKeyException}. */
    public void insert(String table, Map<String, ?> values) {
        String columns = String.join(", ", values.keySet());
        String params = values.keySet().stream().map(c -> ":" + c).collect(Collectors.joining(", "));
        try {
            jdbc.sql("INSERT INTO " + table + " (" + columns + ") VALUES (" + params + ")").params(values).update();
        } catch (DataAccessException e) {
            throw translate(e);
        }
    }

    /**
     * Macht aus einem verletzten eindeutigen Index eine {@link DuplicateKeyException} – MariaDB liefert sie schon so,
     * SQLite meldet sie nur über seinen Fehlercode.
     */
    static DataAccessException translate(DataAccessException e) {
        if (e instanceof DuplicateKeyException) {
            return e;
        }
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                String message = String.valueOf(sql.getMessage());
                boolean sqliteUnique = message.contains("UNIQUE constraint failed")
                        || message.contains("PRIMARY KEY constraint failed");
                boolean mariaDbUnique = sql.getErrorCode() == 1062;
                if (sqliteUnique || mariaDbUnique) {
                    return new DuplicateKeyException(message, e);
                }
            }
        }
        return e;
    }

    // ---- Zeitpunkte ----

    public static Long millis(Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    public static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    public static <E extends Enum<E>> E enumValue(ResultSet rs, String column, Class<E> type) throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : Enum.valueOf(type, value);
    }

    public static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    // ---- JSON-Spalten ----

    public static String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Wert lässt sich nicht als JSON speichern: " + value.getClass(), e);
        }
    }

    public static <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Gespeichertes JSON passt nicht zu " + type.getSimpleName(), e);
        }
    }

    public static <T> T fromJson(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Gespeichertes JSON passt nicht zu " + type.getType(), e);
        }
    }
}
