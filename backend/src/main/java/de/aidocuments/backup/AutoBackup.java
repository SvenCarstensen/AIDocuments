package de.aidocuments.backup;

import static de.aidocuments.i18n.ValidationMessages.MAX;
import static de.aidocuments.i18n.ValidationMessages.MIN;
import static de.aidocuments.i18n.ValidationMessages.SIZE;

import de.aidocuments.config.AppProperties;
import de.aidocuments.document.FileStorage;
import de.aidocuments.i18n.Messages;
import de.aidocuments.i18n.Msg;
import de.aidocuments.i18n.MsgCodec;
import de.aidocuments.notify.Notifications;
import de.aidocuments.persistence.SettingsStore;
import de.aidocuments.web.ApiException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

/**
 * Automatische Sicherung: einmal am Tag ab einer einstellbaren Stunde dasselbe ZIP wie „Sicherung herunterladen“, in
 * einen Ordner auf dem Server; dort bleiben die letzten N Stände. Schlägt ein Lauf fehl, wird es nach einer Stunde
 * erneut versucht, und die Admins bekommen (einmal je Fehlerserie) eine E-Mail.
 *
 * <p>Dazu die Warnung „zu lange nicht gesichert“ für die Admins – sie gilt für jede Sicherung, auch die von Hand.
 *
 * <p>Wie die Sicherung per Knopfdruck nur mit der eingebauten Datenbank.
 */
@Service
public class AutoBackup {

    private static final Logger log = LoggerFactory.getLogger(AutoBackup.class);
    static final String SETTINGS_ID = "backupAuto";
    static final String RUN_ID = "backupAutoRun";
    static final String PREFIX = "AIDocuments-Sicherung-";
    /** Nur Dateien mit genau diesem Namen räumt die Aufbewahrung weg – nie etwas anderes im Ordner. */
    static final Pattern NAME = Pattern.compile("AIDocuments-Sicherung-\\d{4}-\\d{2}-\\d{2}-\\d{6}\\.zip");
    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");
    /** Nach einem Fehlschlag nicht gleich wieder, sondern frühestens nach dieser Pause. */
    static final Duration RETRY_AFTER = Duration.ofHours(1);
    /** Liegt der Ordner auf einer anderen Platte als das Datenverzeichnis, genügt dort dieser Spielraum. */
    static final long OTHER_DISK_RESERVE = 256L * 1024 * 1024;

    /** Gespeicherte Einstellungen. {@code directory} null = Vorgabe. {@code warnAfterDays} 0 = nie warnen. */
    public record Settings(boolean enabled, int hour, int keep, String directory, int warnAfterDays) {
        static final Settings DEFAULT = new Settings(false, 2, 7, null, 7);
    }

    public record UpdateRequest(
            boolean enabled,
            @Min(value = 0, message = MIN) @Max(value = 23, message = MAX) int hour,
            @Min(value = 1, message = MIN) @Max(value = 90, message = MAX) int keep,
            /* leer = die Vorgabe */
            @Size(max = 1000, message = SIZE) String directory,
            @Min(value = 0, message = MIN) @Max(value = 365, message = MAX) int warnAfterDays) {
    }

    /** Der letzte automatische Lauf; {@code error} ist ein sprachneutraler Text ({@link MsgCodec}). */
    record LastRun(Instant at, boolean ok, String file, long bytes, String error, boolean failureNotified) {
    }

    public record StoredBackup(String name, long bytes, Instant createdAt) {
    }

    public record AutoStatus(boolean available, boolean enabled, int hour, int keep, String directory,
                             String defaultDirectory, int warnAfterDays, boolean running, Instant lastRunAt,
                             Boolean lastRunOk, String lastRunError, Instant nextRunAt, List<StoredBackup> backups) {
    }

    /** Für die Admins: wann zuletzt gesichert wurde und ob das zu lange her ist bzw. was schiefging. */
    public record Notice(Instant lastBackupAt, boolean overdue, String failure) {
    }

    private final BackupService backups;
    private final SettingsStore store;
    private final Notifications notifications;
    private final Messages messages;
    private final ThreadPoolTaskExecutor executor;
    private final Path root;
    private final Path defaultDirectory;
    private final AtomicBoolean running = new AtomicBoolean();

    public AutoBackup(BackupService backups, SettingsStore store, Notifications notifications, Messages messages,
                      @Qualifier("importExecutor") ThreadPoolTaskExecutor executor, AppProperties properties) {
        this.backups = backups;
        this.store = store;
        this.notifications = notifications;
        this.messages = messages;
        this.executor = executor;
        this.root = properties.dataRoot();
        String configured = properties.backup() == null ? "" : properties.backup().directory().strip();
        this.defaultDirectory = configured.isEmpty() ? root.resolve("backups")
                : Path.of(configured).toAbsolutePath().normalize();
    }

    public Settings settings() {
        Settings stored = store.find(SETTINGS_ID, Settings.class);
        return stored == null ? Settings.DEFAULT : stored;
    }

    public Path directory() {
        String configured = settings().directory();
        return configured == null || configured.isBlank() ? defaultDirectory : Path.of(configured);
    }

    public synchronized Settings update(UpdateRequest request) {
        if (!backups.available()) {
            throw ApiException.conflict("error.backup.externalDatabase");
        }
        String path = request.directory() == null ? "" : request.directory().strip();
        Path target = defaultDirectory;
        if (!path.isEmpty()) {
            try {
                target = Path.of(path);
            } catch (InvalidPathException e) {
                throw ApiException.badRequest("error.folder.pathInvalid");
            }
            if (!target.isAbsolute()) {
                throw ApiException.badRequest("error.folder.pathNotAbsolute");
            }
            target = target.normalize();
            // nicht dorthin, wo die Sicherung selbst liest – sonst sicherte sie ihre eigenen Stände mit
            if (target.equals(root) || target.startsWith(root.resolve("files"))
                    || target.startsWith(root.resolve("thumbnails")) || target.startsWith(root.resolve("index"))
                    || target.startsWith(root.resolve("tmp"))) {
                throw ApiException.badRequest("error.backup.directoryInData");
            }
        }
        if (request.enabled()) {
            requireWritable(target);
        }
        Settings settings = new Settings(request.enabled(), request.hour(), request.keep(),
                path.isEmpty() ? null : target.toString(), request.warnAfterDays());
        store.save(SETTINGS_ID, settings);
        log.info("Automatische Sicherung {}: täglich ab {} Uhr nach {}, {} Stände", settings.enabled() ? "an" : "aus",
                settings.hour(), target, settings.keep());
        return settings;
    }

    private static void requireWritable(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException | SecurityException e) {
            throw ApiException.badRequest("error.backup.directoryNotWritable", directory.toString());
        }
        if (!Files.isDirectory(directory) || !Files.isWritable(directory)) {
            throw ApiException.badRequest("error.backup.directoryNotWritable", directory.toString());
        }
    }

    public AutoStatus status() {
        Settings settings = settings();
        LastRun last = lastRun();
        Locale locale = org.springframework.context.i18n.LocaleContextHolder.getLocale();
        return new AutoStatus(backups.available(), settings.enabled(), settings.hour(), settings.keep(),
                directory().toString(), defaultDirectory.toString(), settings.warnAfterDays(), running.get(),
                last == null ? null : last.at(), last == null ? null : last.ok(),
                last == null || last.ok() ? null : messages.localize(last.error(), locale),
                nextRun(settings, last), stored(directory()));
    }

    /** Wann der nächste Lauf ansteht; null = aus. Liegt der Zeitpunkt schon zurück, kommt er beim nächsten Takt. */
    Instant nextRun(Settings settings, LastRun last) {
        if (!settings.enabled() || !backups.available()) {
            return null;
        }
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalDate day = today;
        if (last != null && !LocalDate.ofInstant(last.at(), zone).isBefore(today)) {
            if (last.ok()) {
                day = today.plusDays(1);
            } else {
                return last.at().plus(RETRY_AFTER);
            }
        }
        return day.atTime(settings.hour(), 0).atZone(zone).toInstant();
    }

    /** Die Admin-Warnung; null, wenn es nichts zu sagen gibt. */
    public Notice notice(long documents) {
        if (!backups.available()) {
            return null;
        }
        Settings settings = settings();
        LastRun last = lastRun();
        String failure = settings.enabled() && last != null && !last.ok()
                ? messages.localize(last.error(), org.springframework.context.i18n.LocaleContextHolder.getLocale())
                : null;
        Instant lastBackup = backups.lastBackupAt();
        boolean overdue = settings.warnAfterDays() > 0 && documents > 0 && (lastBackup == null
                || lastBackup.isBefore(Instant.now().minus(Duration.ofDays(settings.warnAfterDays()))));
        return overdue || failure != null ? new Notice(lastBackup, overdue, failure) : null;
    }

    /** Alle fünf Minuten: steht heute noch ein Lauf an? */
    @Scheduled(initialDelay = 120_000, fixedDelay = 300_000)
    public void tick() {
        Settings settings = settings();
        if (!settings.enabled() || !backups.available()) {
            return;
        }
        Instant next = nextRun(settings, lastRun());
        if (next != null && !next.isAfter(Instant.now())) {
            run();
        }
    }

    /** Jetzt sichern, im Hintergrund. @return false, wenn schon eine Sicherung läuft */
    public boolean runSoon() {
        if (!backups.available()) {
            throw ApiException.conflict("error.backup.externalDatabase");
        }
        if (running.get()) {
            return false;
        }
        executor.execute(this::run);
        return true;
    }

    /** Ein Lauf: ZIP schreiben, alte Stände wegräumen, das Ergebnis vermerken. Läuft schon einer, passiert nichts. */
    public void run() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Path part = null;
        try {
            Settings settings = settings();
            Path directory = directory();
            requireWritable(directory);
            requireSpace(directory, backups.status().dataBytes());
            String name = PREFIX + STAMP.format(LocalDateTime.now()) + ".zip";
            part = directory.resolve(name + ".part");
            try (OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                backups.write(out, Locale.getDefault(), "automatisch");
            }
            Path target = directory.resolve(name);
            try {
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, target);
            }
            part = null;
            long bytes = Files.size(target);
            int removed = prune(directory, settings.keep());
            store.save(RUN_ID, new LastRun(Instant.now(), true, name, bytes, null, false));
            log.info("Automatische Sicherung geschrieben: {} ({} MB), {} alte Stände entfernt", target, bytes >> 20,
                    removed);
        } catch (ApiException e) {
            if ("error.backup.running".equals(e.msg().key())) {
                return; // eine Sicherung von Hand läuft – beim nächsten Takt
            }
            failed(e.msg());
        } catch (IOException | RuntimeException e) {
            log.warn("Automatische Sicherung fehlgeschlagen", e);
            failed(Msg.of("error.backup.writeFailed", String.valueOf(e.getMessage())));
        } finally {
            if (part != null) {
                try {
                    Files.deleteIfExists(part);
                } catch (IOException ignored) {
                    // bleibt liegen; ein späterer Lauf schreibt unter anderem Namen
                }
            }
            running.set(false);
        }
    }

    private void failed(Msg reason) {
        LastRun previous = lastRun();
        boolean notified = previous != null && !previous.ok() && previous.failureNotified();
        if (!notified) {
            notified = notifications.backupFailed(reason);
        }
        store.save(RUN_ID, new LastRun(Instant.now(), false, null, 0, MsgCodec.encode(reason), notified));
        log.warn("Automatische Sicherung fehlgeschlagen: {}", messages.render(reason, Locale.GERMAN));
    }

    /** Genug Platz für ein ZIP von etwa der Größe der Daten – im Datenverzeichnis zusätzlich die übliche Reserve. */
    private void requireSpace(Path directory, long estimate) {
        long usable;
        boolean sameDisk;
        try {
            var store = Files.getFileStore(directory);
            usable = store.getUsableSpace();
            sameDisk = store.equals(Files.getFileStore(root));
        } catch (IOException e) {
            return; // unbekannt – dann entscheidet das Schreiben
        }
        long reserve = sameDisk ? FileStorage.MIN_FREE_BYTES : OTHER_DISK_RESERVE;
        if (usable < estimate + reserve) {
            throw ApiException.conflict("error.backup.noSpace", directory.toString(),
                    (estimate + reserve - usable + (1 << 20) - 1) >> 20);
        }
    }

    /** Behält die {@code keep} neuesten Stände (nach dem Zeitstempel im Namen); halbe Dateien von gestern weg. */
    int prune(Path directory, int keep) throws IOException {
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (NAME.matcher(name).matches() && Files.isRegularFile(entry)) {
                    found.add(entry);
                } else if (name.endsWith(".zip.part") && NAME.matcher(name.substring(0, name.length() - 5)).matches()
                        && Files.getLastModifiedTime(entry).toInstant().isBefore(Instant.now().minus(Duration.ofDays(1)))
                        && !running.get()) {
                    Files.deleteIfExists(entry);
                }
            }
        }
        found.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        int removed = 0;
        for (Path old : found.subList(Math.min(keep, found.size()), found.size())) {
            Files.deleteIfExists(old);
            removed++;
        }
        return removed;
    }

    private static List<StoredBackup> stored(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<StoredBackup> result = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (NAME.matcher(name).matches() && Files.isRegularFile(entry)) {
                    result.add(new StoredBackup(name, Files.size(entry),
                            Files.getLastModifiedTime(entry).toInstant()));
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        result.sort(Comparator.comparing(StoredBackup::name).reversed());
        return result;
    }

    LastRun lastRun() {
        return store.find(RUN_ID, LastRun.class);
    }
}
