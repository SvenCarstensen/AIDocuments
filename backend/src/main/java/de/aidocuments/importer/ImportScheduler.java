package de.aidocuments.importer;

import de.aidocuments.mail.MailSyncService;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Prüft den Eingangsordner im allgemeinen Intervall und jedes aktive Postfach in seinem eigenen (ohne eigenes ebenfalls
 * im allgemeinen, siehe {@link de.aidocuments.mail.MailSchedule}) auf neue PDFs. Der Takt läuft kurz getaktet durch und
 * vergleicht mit den Intervallen – so wirkt eine Änderung in den Einstellungen sofort, ohne Neustart.
 */
@Component
public class ImportScheduler {

    private static final Logger log = LoggerFactory.getLogger(ImportScheduler.class);

    private final ImportSettingsService settings;
    private final MailSyncService mailSync;
    private final FolderImportService folderImport;
    private volatile Instant lastRun;

    public ImportScheduler(ImportSettingsService settings, MailSyncService mailSync,
                           FolderImportService folderImport) {
        this.settings = settings;
        this.mailSync = mailSync;
        this.folderImport = folderImport;
    }

    @Scheduled(initialDelay = 15_000, fixedDelay = 15_000)
    void tick() {
        try {
            mailSync.syncDue();
        } catch (RuntimeException e) {
            log.error("Automatischer Postfach-Abruf fehlgeschlagen", e);
        }
        Duration interval = settings.current().interval();
        if (lastRun != null && Duration.between(lastRun, Instant.now()).compareTo(interval) < 0) {
            return;
        }
        lastRun = Instant.now();
        try {
            folderImport.scan();
        } catch (RuntimeException e) {
            log.error("Automatische Prüfung des Eingangsordners fehlgeschlagen", e);
        }
    }

    public Instant nextRunAt() {
        return lastRun == null ? Instant.now() : lastRun.plus(settings.current().interval());
    }
}
