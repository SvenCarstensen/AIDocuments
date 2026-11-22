package de.aidocuments.ai;

import de.aidocuments.web.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Begrenzt Anfragen, die die Oberfläche direkt an die KI stellt (Fragen an ein Dokument, KI-Suche): Jede belegt einen
 * Server-Thread bis zur Antwort und kostet beim Anbieter Geld. Je Benutzer höchstens zwei gleichzeitig und 60 je
 * Stunde, insgesamt höchstens sechs gleichzeitig.
 */
@Component
public class AiRequestLimiter {

    static final int PER_USER_CONCURRENT = 2;
    static final int PER_USER_PER_HOUR = 60;
    static final int TOTAL_CONCURRENT = 6;

    private final Semaphore total = new Semaphore(TOTAL_CONCURRENT);
    private final Map<String, Semaphore> running = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> recent = new ConcurrentHashMap<>();

    public <T> T run(String userId, Supplier<T> request) {
        Semaphore mine = running.computeIfAbsent(userId, id -> new Semaphore(PER_USER_CONCURRENT));
        if (!mine.tryAcquire()) {
            throw ApiException.of(HttpStatus.TOO_MANY_REQUESTS, "error.ai.tooManyRequests");
        }
        try {
            // erst nach der Prüfung auf gleichzeitige Anfragen zählen – eine abgewiesene verbraucht nichts
            if (!withinHourlyLimit(userId)) {
                throw ApiException.of(HttpStatus.TOO_MANY_REQUESTS, "error.ai.tooManyRequests");
            }
            if (!total.tryAcquire()) {
                throw ApiException.of(HttpStatus.TOO_MANY_REQUESTS, "error.ai.tooManyRequests");
            }
            try {
                return request.get();
            } finally {
                total.release();
            }
        } finally {
            mine.release();
        }
    }

    private boolean withinHourlyLimit(String userId) {
        Instant now = Instant.now();
        Instant limit = now.minus(Duration.ofHours(1));
        Deque<Instant> times = recent.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst().isBefore(limit)) {
                times.pollFirst();
            }
            if (times.size() >= PER_USER_PER_HOUR) {
                return false;
            }
            times.addLast(now);
            return true;
        }
    }
}
