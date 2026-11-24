package de.aidocuments.security;

import de.aidocuments.user.UserPrincipal;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Laufzeit der Sitzungen: Eine Sitzung, die vor der Anmeldung entsteht (Challenge für Passkeys, Anmeldung mit noch
 * ausstehendem Code aus der App), endet nach {@link #ANONYMOUS} ohne Aufruf. Sonst könnte jeder ohne Konto mit vielen
 * Aufrufen Sitzungen anlegen, die jeweils zwölf Stunden Speicher belegen. Erst die Anmeldung gibt der Sitzung die
 * eingestellte Laufzeit ({@code server.servlet.session.timeout}).
 *
 * <p>Als {@link HttpSessionListener} beginnt jede neue Sitzung kurz – auch eine, die an anderer Stelle unbeabsichtigt
 * entsteht; {@link #established} verlängert sie bei der Anmeldung.
 */
@Component
public class SessionTimeouts implements HttpSessionListener {

    public static final Duration ANONYMOUS = Duration.ofMinutes(5);

    private final Duration configured;

    public SessionTimeouts(@Value("${server.servlet.session.timeout:12h}") Duration configured) {
        this.configured = configured;
    }

    @Override
    public void sessionCreated(HttpSessionEvent event) {
        event.getSession().setMaxInactiveInterval(seconds(ANONYMOUS));
    }

    /** Eine Sitzung, die (noch) keine Anmeldung trägt, läuft nach fünf Minuten ab; eine angemeldete bleibt, wie sie ist. */
    public static HttpSession anonymous(HttpSession session) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication != null && authentication.getPrincipal() instanceof UserPrincipal)) {
            session.setMaxInactiveInterval(seconds(ANONYMOUS));
        }
        return session;
    }

    /** Die Anmeldung steht: ab jetzt die eingestellte Laufzeit. */
    public void established(HttpSession session) {
        if (session != null) {
            // 0 oder negativ: ohne Ablauf, wie beim Servlet-Container
            session.setMaxInactiveInterval(configured.isPositive() ? seconds(configured) : -1);
        }
    }

    private static int seconds(Duration duration) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, duration.toSeconds()));
    }
}
