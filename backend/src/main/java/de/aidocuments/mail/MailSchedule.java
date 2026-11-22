package de.aidocuments.mail;

import de.aidocuments.importer.ImportSettingsService;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Wann ein Postfach wieder abgerufen wird: nach seinem eigenen Intervall oder, wenn keins eingestellt ist, nach dem
 * allgemeinen aus „Abruf &amp; Eingangsordner“. Gezählt wird ab dem Beginn des letzten Abrufs – auch eines von Hand
 * gestarteten. Nach einem Neustart gilt der zuletzt gespeicherte Abrufzeitpunkt.
 */
@Component
public class MailSchedule {

    public static final int MIN_INTERVAL_MINUTES = 1;
    public static final int MAX_INTERVAL_MINUTES = 1440;

    private final ImportSettingsService settings;
    /** Beginn des letzten Abrufs seit dem Start der Anwendung. */
    private final Map<String, Instant> lastStarted = new ConcurrentHashMap<>();

    public MailSchedule(ImportSettingsService settings) {
        this.settings = settings;
    }

    public int intervalMinutes(MailAccount account) {
        Integer own = account.getIntervalMinutes();
        return own != null ? own : settings.current().intervalMinutes();
    }

    /** {@code null}, wenn das Postfach pausiert ist; noch nie abgerufen = sofort fällig. */
    public Instant nextSyncAt(MailAccount account) {
        if (!account.isEnabled()) {
            return null;
        }
        Instant last = lastStart(account);
        return last == null ? Instant.now() : last.plus(Duration.ofMinutes(intervalMinutes(account)));
    }

    boolean isDue(MailAccount account, Instant now) {
        if (!account.isEnabled()) {
            return false;
        }
        Instant last = lastStart(account);
        return last == null || !last.plus(Duration.ofMinutes(intervalMinutes(account))).isAfter(now);
    }

    private Instant lastStart(MailAccount account) {
        return lastStarted.getOrDefault(account.getId(), account.getLastSyncAt());
    }

    void started(String accountId, Instant at) {
        lastStarted.put(accountId, at);
    }

    void forget(String accountId) {
        lastStarted.remove(accountId);
    }
}
