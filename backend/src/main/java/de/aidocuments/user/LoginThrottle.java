package de.aidocuments.user;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Bremst das Durchprobieren von Passwörtern (und Einrichtungscodes) auf drei Ebenen:
 *
 * <ul>
 *   <li>je Konto und Absender (IP-Adresse): nach fünf Fehlversuchen fünf Minuten Pause – gegen gezieltes Raten,
 *       ohne dass ein Fremder damit das Konto für alle anderen sperren kann;</li>
 *   <li>je Absender: nach 20 Fehlversuchen innerhalb von 15 Minuten 15 Minuten Pause – gegen das Durchprobieren
 *       vieler Konten oder erfundener Benutzernamen;</li>
 *   <li>je Konto insgesamt: nach 100 Fehlversuchen innerhalb einer Stunde 15 Minuten Pause – gegen verteiltes
 *       Raten von sehr vielen Adressen aus. Adressen, von denen sich das Konto schon einmal erfolgreich angemeldet hat,
 *       sind davon ausgenommen – so kann ein Angreifer den Inhaber damit nicht aussperren.</li>
 *   <li>für den Code aus der Authenticator-App zusätzlich je Konto: zehn falsche Codes in 24 Stunden
 *       ({@link #beginSecondFactor}).</li>
 * </ul>
 *
 * <p>IPv6-Adressen zählen je /64-Netz: Ein Anschluss bekommt meist ein ganzes /64, darin ließe sich sonst für jeden
 * Versuch eine neue Adresse wählen.
 *
 * <p>Ein Versuch wird gezählt, <em>bevor</em> das Passwort geprüft wird ({@link #begin}); so kommen auch viele
 * gleichzeitige Anfragen nicht über die Grenze. Ein erfolgreicher Versuch wird wieder abgezogen ({@link #succeeded}).
 *
 * <p>Hinter einem Reverse-Proxy ist die IP-Adresse die des Browsers, sofern der Proxy als vertrauenswürdig
 * eingetragen ist (AIDOCS_TRUSTED_PROXIES).
 */
@Component
public class LoginThrottle {

    private final Limiter byAccountAndAddress = new Limiter(5, Duration.ofMinutes(5), Duration.ofMinutes(5));
    private final Limiter byAddress = new Limiter(20, Duration.ofMinutes(15), Duration.ofMinutes(15));
    private final Limiter byAccount = new Limiter(100, Duration.ofHours(1), Duration.ofMinutes(15));
    private final Limiter secondFactorByAccount = new Limiter(10, Duration.ofHours(24), Duration.ofHours(24));
    /** Konto+Adresse, die sich schon erfolgreich angemeldet haben (für 30 Tage), mit Zeitpunkt der letzten Anmeldung. */
    private final Map<String, Instant> knownPairs = new ConcurrentHashMap<>();
    private static final Duration KNOWN_PAIR_TIME = Duration.ofDays(30);
    private static final int MAX_KNOWN_PAIRS = 100_000;

    /** Ein gezählter, noch nicht entschiedener Versuch. */
    public final class Attempt {

        private final List<Runnable> releases;
        private final String pairKey;

        private Attempt(List<Runnable> releases, String pairKey) {
            this.releases = releases;
            this.pairKey = pairKey;
        }

        /** Der Versuch war erfolgreich: zählt nicht als Fehlversuch, und die Pause für Konto und Adresse endet. */
        public void succeeded() {
            releases.forEach(Runnable::run);
            if (pairKey != null) {
                byAccountAndAddress.reset(pairKey);
                rememberPair(pairKey);
            }
        }

        /** Der Versuch war ungültig, aber kein Rateversuch (etwa eine abgelehnte Eingabe) – nicht mitzählen. */
        public void discard() {
            releases.forEach(Runnable::run);
        }
    }

    /**
     * Zählt einen Versuch für Konto und Adresse, sofern keine Grenze erreicht ist.
     *
     * @param username Benutzername, {@code null} für Versuche ohne Konto (Einrichtungscode)
     * @return der gezählte Versuch, oder {@code null}, wenn eine Pause gilt
     */
    public Attempt begin(String username, String rawAddress) {
        return begin(username, rawAddress, false);
    }

    /**
     * Wie {@link #begin}, für den Code aus der Authenticator-App bei der Anmeldung. Wer hier rät, kennt das Passwort
     * schon – deshalb gilt zusätzlich je Konto: höchstens zehn falsche Codes in 24 Stunden, danach 24 Stunden Pause
     * (Adressen, von denen sich das Konto schon angemeldet hat, ausgenommen). Ohne diese Grenze ließe sich der Code von
     * vielen Adressen aus mit 100 Versuchen in der Stunde erraten – in einem Monat mit rund 20 % Wahrscheinlichkeit.
     */
    public Attempt beginSecondFactor(String username, String rawAddress) {
        return begin(username, rawAddress, true);
    }

    /** Ist das Konto wegen zu vieler falscher Codes aus der App gesperrt? (für einen Hinweis im Log) */
    public boolean secondFactorLocked(String username) {
        return secondFactorByAccount.isLocked(accountKey(username));
    }

    private Attempt begin(String username, String rawAddress, boolean secondFactor) {
        String address = addressKey(rawAddress);
        List<Runnable> releases = new ArrayList<>();
        String pairKey = username == null ? null : accountKey(username) + "\n" + address;
        boolean knownPair = pairKey != null && isKnownPair(pairKey);
        if (!acquire(byAddress, address, releases)
                || (username != null && !knownPair && !acquire(byAccount, accountKey(username), releases))
                || (secondFactor && username != null && !knownPair
                        && !acquire(secondFactorByAccount, accountKey(username), releases))
                || (pairKey != null && !acquire(byAccountAndAddress, pairKey, releases))) {
            releases.forEach(Runnable::run);
            return null;
        }
        return new Attempt(releases, pairKey);
    }

    private static boolean acquire(Limiter limiter, String key, List<Runnable> releases) {
        if (!limiter.tryAcquire(key)) {
            return false;
        }
        releases.add(() -> limiter.release(key));
        return true;
    }

    private boolean isKnownPair(String pairKey) {
        Instant last = knownPairs.get(pairKey);
        return last != null && last.plus(KNOWN_PAIR_TIME).isAfter(Instant.now());
    }

    private void rememberPair(String pairKey) {
        knownPairs.put(pairKey, Instant.now());
        if (knownPairs.size() > MAX_KNOWN_PAIRS) {
            synchronized (knownPairs) {
                Instant limit = Instant.now().minus(KNOWN_PAIR_TIME);
                knownPairs.values().removeIf(last -> last.isBefore(limit));
                int excess = knownPairs.size() - MAX_KNOWN_PAIRS * 9 / 10;
                if (excess > 0) {
                    knownPairs.entrySet().stream().sorted(Map.Entry.comparingByValue()).limit(excess)
                            .map(Map.Entry::getKey).toList().forEach(knownPairs::remove);
                }
            }
        }
    }

    /** IPv4 unverändert, IPv6 auf das /64-Netz gekürzt. */
    static String addressKey(String address) {
        if (address == null || address.indexOf(':') < 0) {
            return String.valueOf(address);
        }
        try {
            // ein IP-Literal (enthält „:“) löst getByName ohne DNS-Anfrage auf
            byte[] bytes = InetAddress.getByName(address.replaceAll("^\\[|]$|%.*$", "")).getAddress();
            if (bytes.length != 16) {
                return address;
            }
            return "v6:" + HexFormat.of().formatHex(bytes, 0, 8) + "/64";
        } catch (java.net.UnknownHostException | IllegalArgumentException e) {
            return address;
        }
    }

    private static String accountKey(String username) {
        return username.strip().toLowerCase(Locale.ROOT);
    }

    /** Zählt Versuche je Schlüssel; Einträge verfallen nach Ablauf von Zeitfenster bzw. Sperre. */
    static final class Limiter {

        /** Obergrenze für die Zahl der Einträge – darüber werden zuerst abgelaufene, dann die ältesten verworfen. */
        private static final int MAX_ENTRIES = 100_000;

        private record Attempts(int count, Instant first, Instant last) {
        }

        private final int maxAttempts;
        private final Duration window;
        private final Duration lockTime;
        private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
        private final java.util.function.Supplier<Instant> clock;

        Limiter(int maxAttempts, Duration window, Duration lockTime) {
            this(maxAttempts, window, lockTime, Instant::now);
        }

        /** Mit eigener Uhr – für Tests. */
        Limiter(int maxAttempts, Duration window, Duration lockTime, java.util.function.Supplier<Instant> clock) {
            this.maxAttempts = maxAttempts;
            this.window = window;
            this.lockTime = lockTime;
            this.clock = clock;
        }

        /** Zählt einen Versuch – in einem Schritt mit der Prüfung, damit gleichzeitige Anfragen nichts überholen. */
        boolean tryAcquire(String key) {
            Instant now = clock.get();
            boolean[] acquired = {false};
            attempts.compute(key, (k, old) -> {
                if (old == null || expired(old, now)) {
                    acquired[0] = true;
                    return new Attempts(1, now, now);
                }
                if (old.count() >= maxAttempts) {
                    return old;
                }
                acquired[0] = true;
                return new Attempts(old.count() + 1, old.first(), now);
            });
            if (attempts.size() > MAX_ENTRIES) {
                prune(now);
            }
            return acquired[0];
        }

        void release(String key) {
            attempts.computeIfPresent(key, (k, old) -> old.count() <= 1 ? null
                    : new Attempts(old.count() - 1, old.first(), old.last()));
        }

        boolean isLocked(String key) {
            Attempts current = attempts.get(key);
            return current != null && !expired(current, clock.get()) && current.count() >= maxAttempts;
        }

        void reset(String key) {
            attempts.remove(key);
        }

        /**
         * Gesperrt: bis die Sperrzeit nach dem letzten Versuch um ist <em>und</em> das Zeitfenster abgelaufen – sonst
         * gälten etwa „100 in der Stunde“ nach jeder kürzeren Sperre von vorn; sonst zählt nur das Zeitfenster.
         */
        private boolean expired(Attempts attempts, Instant now) {
            if (attempts.count() >= maxAttempts) {
                return attempts.last().plus(lockTime).isBefore(now) && attempts.first().plus(window).isBefore(now);
            }
            return attempts.first().plus(window).isBefore(now);
        }

        private synchronized void prune(Instant now) {
            attempts.entrySet().removeIf(entry -> expired(entry.getValue(), now));
            int excess = attempts.size() - MAX_ENTRIES * 9 / 10;
            if (excess > 0) {
                // zuerst ungesperrte, älteste Einträge verwerfen – eine bestehende Sperre soll sich nicht durch viele
                // erfundene Namen aus dem Speicher drängen lassen
                attempts.entrySet().stream()
                        .sorted(Comparator.<Map.Entry<String, Attempts>, Boolean>comparing(
                                        entry -> entry.getValue().count() >= maxAttempts)
                                .thenComparing(entry -> entry.getValue().last()))
                        .limit(excess)
                        .map(Map.Entry::getKey)
                        .toList()
                        .forEach(attempts::remove);
            }
        }
    }
}
