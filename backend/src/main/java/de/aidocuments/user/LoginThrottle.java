package de.aidocuments.user;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Bremst das Durchprobieren von Passwörtern: nach fünf Fehlversuchen ist ein Konto fünf Minuten gesperrt. */
@Component
public class LoginThrottle {

    private static final int MAX_FAILURES = 5;
    private static final Duration LOCK_TIME = Duration.ofMinutes(5);

    private record Attempts(int failures, Instant lastFailure) {
    }

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    public boolean isLocked(String username) {
        Attempts current = attempts.get(key(username));
        if (current == null || current.failures() < MAX_FAILURES) {
            return false;
        }
        if (current.lastFailure().plus(LOCK_TIME).isBefore(Instant.now())) {
            attempts.remove(key(username));
            return false;
        }
        return true;
    }

    public void recordFailure(String username) {
        attempts.merge(key(username), new Attempts(1, Instant.now()),
                (old, fresh) -> new Attempts(old.failures() + 1, Instant.now()));
        if (attempts.size() > 10_000) {
            attempts.clear(); // Schutz vor unbegrenztem Wachstum durch erfundene Benutzernamen
        }
    }

    public void recordSuccess(String username) {
        attempts.remove(key(username));
    }

    private static String key(String username) {
        return username.strip().toLowerCase();
    }
}
