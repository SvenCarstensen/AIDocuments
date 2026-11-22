package de.aidocuments.backup;

import de.aidocuments.config.AppProperties;
import de.aidocuments.i18n.Messages;
import de.aidocuments.i18n.Msg;
import de.aidocuments.persistence.Db;
import de.aidocuments.persistence.SettingsStore;
import de.aidocuments.web.ApiException;
import jakarta.servlet.http.HttpSession;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Sicherung per Knopfdruck: ein ZIP mit allem, was zum Wiederherstellen nötig ist – ein in sich stimmiger Abzug der
 * SQLite-Datenbank ({@code VACUUM INTO}, auch während die Anwendung läuft), die Dateien der Dokumente samt früheren
 * Fassungen, die Vorschaubilder und der Schlüssel, mit dem gespeicherte Kennwörter verschlüsselt sind. Der Suchindex
 * fehlt mit Absicht: Er entsteht beim Start neu.
 *
 * <p>Mit MariaDB gibt es diese Sicherung nicht – die Datenbank sichert dann deren eigenes Werkzeug.
 *
 * <p>Wie beim Export zweistufig: {@link #prepare} (mit Bestätigung per App-Code) gibt einen einmal gültigen Schlüssel
 * für den Download aus.
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final String SESSION_ATTRIBUTE = "aidocuments.backups";
    private static final Duration VALID_FOR = Duration.ofMinutes(5);
    private static final SecureRandom RANDOM = new SecureRandom();
    static final String SETTINGS_ID = "backup";
    static final String DATABASE_ENTRY = "aidocuments.db";
    static final String KEY_ENTRY = "secret.key";

    /** Was die Einstellungsseite anzeigt. */
    public record BackupStatus(boolean available, Instant lastBackupAt, long dataBytes, int files) {
    }

    /** Gespeichert in den Einstellungen: wann zuletzt gesichert wurde. */
    record LastBackup(Instant at) {
    }

    private final Db db;
    private final SettingsStore settings;
    private final Messages messages;
    private final Path root;
    private final boolean sqlite;
    private final AtomicBoolean running = new AtomicBoolean();

    public BackupService(Db db, SettingsStore settings, Messages messages, AppProperties properties,
                         @Value("${spring.datasource.url}") String datasourceUrl) {
        this.db = db;
        this.settings = settings;
        this.messages = messages;
        this.root = properties.dataRoot();
        this.sqlite = datasourceUrl.startsWith("jdbc:sqlite:");
    }

    public boolean available() {
        return sqlite;
    }

    public BackupStatus status() {
        LastBackup last = settings.find(SETTINGS_ID, LastBackup.class);
        long bytes = 0;
        int count = 0;
        if (sqlite) {
            for (String dir : List.of("files", "thumbnails")) {
                for (Path file : files(root.resolve(dir))) {
                    bytes += size(file);
                    count++;
                }
            }
            bytes += size(root.resolve(DATABASE_ENTRY));
        }
        return new BackupStatus(sqlite, last == null ? null : last.at(), bytes, count);
    }

    /** Wann zuletzt gesichert wurde – von Hand oder automatisch; null = noch nie. */
    public Instant lastBackupAt() {
        LastBackup last = settings.find(SETTINGS_ID, LastBackup.class);
        return last == null ? null : last.at();
    }

    /** Schritt 1: merkt sich in der Sitzung, dass ein Download erlaubt ist. */
    public String prepare(HttpSession session) {
        requireAvailable();
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Map<String, Instant> tickets = tickets(session);
        Instant now = Instant.now();
        tickets.values().removeIf(expires -> expires.isBefore(now));
        tickets.put(token, now.plus(VALID_FOR));
        return token;
    }

    /** Schritt 2: der Schlüssel gilt einmal und nur in der Sitzung, die ihn bekam. */
    public boolean take(HttpSession session, String token) {
        Instant expires = session == null || token == null ? null : tickets(session).remove(token);
        return expires != null && expires.isAfter(Instant.now());
    }

    /** Schreibt das ZIP. Läuft schon eine Sicherung, wird abgelehnt – zwei gleichzeitig kosten nur Platz und Zeit. */
    public void write(OutputStream target, Locale locale, String user) throws IOException {
        requireAvailable();
        if (!running.compareAndSet(false, true)) {
            throw ApiException.conflict("error.backup.running");
        }
        Path snapshot = root.resolve("tmp").resolve("backup-" + java.util.UUID.randomUUID() + ".db");
        try {
            Files.createDirectories(snapshot.getParent());
            // ein stimmiger Stand der Datenbank, ohne sie anzuhalten; der Pfad stammt von der Anwendung, nicht von außen
            db.jdbc().sql("VACUUM INTO '" + snapshot.toString().replace("'", "''") + "'").update();
            Instant now = Instant.now();
            int files = 0;
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(target, 64 * 1024),
                    StandardCharsets.UTF_8)) {
                zip.setLevel(1); // PDFs und JPEGs sind schon komprimiert
                entry(zip, "README.txt", messages.render(Msg.of("backup.readme", java.util.Date.from(now)), locale)
                        .getBytes(StandardCharsets.UTF_8));
                copy(zip, DATABASE_ENTRY, snapshot);
                Path key = root.resolve(KEY_ENTRY);
                if (Files.isRegularFile(key)) {
                    copy(zip, KEY_ENTRY, key);
                }
                for (String dir : List.of("files", "thumbnails")) {
                    for (Path file : files(root.resolve(dir))) {
                        copy(zip, root.relativize(file).toString().replace('\\', '/'), file);
                        files++;
                    }
                }
            }
            settings.save(SETTINGS_ID, new LastBackup(now));
            log.info("Sicherung durch {} erstellt ({} Dateien)", user, files);
        } finally {
            Files.deleteIfExists(snapshot);
            running.set(false);
        }
    }

    private void requireAvailable() {
        if (!sqlite) {
            throw ApiException.conflict("error.backup.externalDatabase");
        }
    }

    private static void entry(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private static void copy(ZipOutputStream zip, String name, Path file) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        try {
            entry.setLastModifiedTime(Files.getLastModifiedTime(file));
        } catch (IOException e) {
            entry.setLastModifiedTime(FileTime.from(Instant.now()));
        }
        zip.putNextEntry(entry);
        try {
            Files.copy(file, zip);
        } catch (java.nio.file.NoSuchFileException e) {
            // eben gelöscht (Papierkorb geleert, Fassung verworfen) – dann bleibt der Eintrag leer
            log.warn("Sicherung: {} fehlt", name);
        }
        zip.closeEntry();
    }

    private static List<Path> files(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long size(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.size(file) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Instant> tickets(HttpSession session) {
        synchronized (session) {
            Object existing = session.getAttribute(SESSION_ATTRIBUTE);
            if (existing instanceof Map<?, ?> map) {
                return (Map<String, Instant>) map;
            }
            Map<String, Instant> created = new ConcurrentHashMap<>();
            session.setAttribute(SESSION_ATTRIBUTE, created);
            return created;
        }
    }
}
