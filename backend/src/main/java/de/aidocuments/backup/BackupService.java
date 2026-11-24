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
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.CompressionLevel;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;
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
 *
 * <p>Mit Passwort entsteht ein AES-256-verschlüsseltes ZIP (WinZip-Verfahren, zu öffnen etwa mit 7-Zip, Keka oder The
 * Unarchiver). Das Passwort wird nirgends gespeichert – beim Download liegt es nur bis zum Abruf in der Sitzung.
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
    /** Das ZIP-Verfahren leitet den Schlüssel mit nur 1.000 Runden ab – ein kurzes Passwort wäre schnell erraten. */
    public static final int MIN_PASSWORD = 12;
    public static final int MAX_PASSWORD = 128;

    /** Ein freigegebener Download; {@code password} null = unverschlüsselt. */
    public record Ticket(Instant expires, char[] password) {
    }

    /** Was die Einstellungsseite anzeigt; {@code passwordConfigured}: AIDOCS_BACKUP_PASSWORD ist gesetzt. */
    public record BackupStatus(boolean available, Instant lastBackupAt, long dataBytes, int files,
                               boolean passwordConfigured) {
    }

    /** Gespeichert in den Einstellungen: wann zuletzt gesichert wurde. */
    record LastBackup(Instant at) {
    }

    private final Db db;
    private final SettingsStore settings;
    private final Messages messages;
    private final Path root;
    private final boolean sqlite;
    private final String configuredPassword;
    private final AtomicBoolean running = new AtomicBoolean();

    public BackupService(Db db, SettingsStore settings, Messages messages, AppProperties properties,
                         @Value("${spring.datasource.url}") String datasourceUrl) {
        this.db = db;
        this.settings = settings;
        this.messages = messages;
        this.root = properties.dataRoot();
        this.sqlite = datasourceUrl.startsWith("jdbc:sqlite:");
        this.configuredPassword = properties.backup() == null ? "" : properties.backup().password();
        if (!configuredPassword.isEmpty()) {
            if (configuredPassword.length() < MIN_PASSWORD || configuredPassword.length() > MAX_PASSWORD) {
                // starten soll die Anwendung trotzdem; die Sicherungen damit schlagen fehl (siehe configuredPassword)
                log.warn("AIDOCS_BACKUP_PASSWORD muss {} bis {} Zeichen lang sein – so wird es nicht verwendet: "
                        + "Automatische Sicherungen schlagen fehl, bis es geändert ist", MIN_PASSWORD, MAX_PASSWORD);
            } else {
                log.info("Automatische Sicherungen werden verschlüsselt (AIDOCS_BACKUP_PASSWORD)");
            }
        }
    }

    /** Ist AIDOCS_BACKUP_PASSWORD gesetzt (ob gültig oder nicht)? */
    public boolean hasConfiguredPassword() {
        return !configuredPassword.isEmpty();
    }

    /**
     * Das Passwort aus AIDOCS_BACKUP_PASSWORD als frische Kopie; null = keins gesetzt. Ein zu kurzes (oder zu langes)
     * wird nicht verwendet – auch nicht stillschweigend weggelassen: Die Sicherung damit schlägt mit 400 fehl.
     */
    public char[] configuredPassword() {
        return configuredPassword.isEmpty() ? null : checkedPassword(configuredPassword);
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
        return new BackupStatus(sqlite, last == null ? null : last.at(), bytes, count, hasConfiguredPassword());
    }

    /** Wann zuletzt gesichert wurde – von Hand oder automatisch; null = noch nie. */
    public Instant lastBackupAt() {
        LastBackup last = settings.find(SETTINGS_ID, LastBackup.class);
        return last == null ? null : last.at();
    }

    /** Schritt 1: merkt sich in der Sitzung, dass ein Download erlaubt ist. */
    public String prepare(HttpSession session) {
        return prepare(session, null);
    }

    /** @param password leer oder null = unverschlüsselt */
    public String prepare(HttpSession session, String password) {
        return prepare(session, password, false);
    }

    /**
     * @param password      eigenes Passwort; leer oder null = unverschlüsselt
     * @param useConfigured stattdessen das Passwort aus AIDOCS_BACKUP_PASSWORD
     */
    public String prepare(HttpSession session, String password, boolean useConfigured) {
        requireAvailable();
        if (useConfigured && configuredPassword.isEmpty()) {
            throw ApiException.badRequest("error.backup.noConfiguredPassword");
        }
        char[] secret = useConfigured ? configuredPassword()
                : password == null || password.isEmpty() ? null : checkedPassword(password);
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Map<String, Ticket> tickets = tickets(session);
        Instant now = Instant.now();
        tickets.values().removeIf(ticket -> ticket.expires().isBefore(now));
        tickets.put(token, new Ticket(now.plus(VALID_FOR), secret));
        return token;
    }

    /** Prüft die Länge – Leerzeichen zählen mit, abgeschnitten wird nichts. */
    public static char[] checkedPassword(String password) {
        if (password.length() < MIN_PASSWORD || password.length() > MAX_PASSWORD) {
            throw ApiException.badRequest("error.backup.passwordLength", MIN_PASSWORD, MAX_PASSWORD);
        }
        return password.toCharArray();
    }

    /** Schritt 2: der Schlüssel gilt einmal und nur in der Sitzung, die ihn bekam. @return null = ungültig */
    public Ticket take(HttpSession session, String token) {
        Ticket ticket = session == null || token == null ? null : tickets(session).remove(token);
        return ticket != null && ticket.expires().isAfter(Instant.now()) ? ticket : null;
    }

    /** Schreibt das ZIP. Läuft schon eine Sicherung, wird abgelehnt – zwei gleichzeitig kosten nur Platz und Zeit. */
    public void write(OutputStream target, Locale locale, String user) throws IOException {
        write(target, locale, user, null);
    }

    /** @param password null = unverschlüsselt; sonst AES-256 für jeden Eintrag */
    public void write(OutputStream target, Locale locale, String user, char[] password) throws IOException {
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
            OutputStream buffered = new BufferedOutputStream(target, 64 * 1024);
            try (Archive zip = password == null ? new PlainArchive(buffered) : new EncryptedArchive(buffered, password)) {
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
            log.info("Sicherung durch {} erstellt ({} Dateien{})", user, files,
                    password == null ? "" : ", verschlüsselt");
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

    private static void entry(Archive zip, String name, byte[] content) throws IOException {
        zip.begin(name, FileTime.from(Instant.now()));
        zip.out().write(content);
        zip.end();
    }

    private static void copy(Archive zip, String name, Path file) throws IOException {
        FileTime modified;
        try {
            modified = Files.getLastModifiedTime(file);
        } catch (IOException e) {
            modified = FileTime.from(Instant.now());
        }
        zip.begin(name, modified);
        try {
            Files.copy(file, zip.out());
        } catch (java.nio.file.NoSuchFileException e) {
            // eben gelöscht (Papierkorb geleert, Fassung verworfen) – dann bleibt der Eintrag leer
            log.warn("Sicherung: {} fehlt", name);
        }
        zip.end();
    }

    /** Ein ZIP, Eintrag für Eintrag geschrieben – mit oder ohne Verschlüsselung. */
    private interface Archive extends java.io.Closeable {
        void begin(String name, FileTime modified) throws IOException;

        OutputStream out();

        void end() throws IOException;
    }

    private static final class PlainArchive implements Archive {
        private final ZipOutputStream zip;

        PlainArchive(OutputStream target) {
            zip = new ZipOutputStream(target, StandardCharsets.UTF_8);
            zip.setLevel(1); // PDFs und JPEGs sind schon komprimiert
        }

        @Override
        public void begin(String name, FileTime modified) throws IOException {
            ZipEntry entry = new ZipEntry(name);
            entry.setLastModifiedTime(modified);
            zip.putNextEntry(entry);
        }

        @Override
        public OutputStream out() {
            return zip;
        }

        @Override
        public void end() throws IOException {
            zip.closeEntry();
        }

        @Override
        public void close() throws IOException {
            zip.close();
        }
    }

    /** AES-256 nach dem WinZip-Verfahren (AE-2) – jeder Eintrag einzeln verschlüsselt, auch README.txt. */
    private static final class EncryptedArchive implements Archive {
        private final net.lingala.zip4j.io.outputstream.ZipOutputStream zip;

        EncryptedArchive(OutputStream target, char[] password) throws IOException {
            zip = new net.lingala.zip4j.io.outputstream.ZipOutputStream(target, password, StandardCharsets.UTF_8);
        }

        @Override
        public void begin(String name, FileTime modified) throws IOException {
            ZipParameters parameters = new ZipParameters();
            parameters.setFileNameInZip(name);
            parameters.setCompressionMethod(CompressionMethod.DEFLATE);
            parameters.setCompressionLevel(CompressionLevel.FASTEST);
            parameters.setEncryptFiles(true);
            parameters.setEncryptionMethod(EncryptionMethod.AES);
            parameters.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
            parameters.setLastModifiedFileTime(modified.toMillis());
            zip.putNextEntry(parameters);
        }

        @Override
        public OutputStream out() {
            return zip;
        }

        @Override
        public void end() throws IOException {
            zip.closeEntry();
        }

        @Override
        public void close() throws IOException {
            zip.close();
        }
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
    private static Map<String, Ticket> tickets(HttpSession session) {
        synchronized (session) {
            Object existing = session.getAttribute(SESSION_ATTRIBUTE);
            if (existing instanceof Map<?, ?> map) {
                return (Map<String, Ticket>) map;
            }
            Map<String, Ticket> created = new ConcurrentHashMap<>();
            session.setAttribute(SESSION_ATTRIBUTE, created);
            return created;
        }
    }
}
