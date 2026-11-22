package de.aidocuments.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.Instant;

/**
 * Merkt sich in der Sitzung, wann zuletzt ein Code aus der Authenticator-App eingegeben wurde. Änderungen an Konten,
 * Postfächern und Einstellungen verlangen einen höchstens {@link #VALID_FOR} alten Code (siehe
 * {@link AdminTwoFactorFilter}) – eine offen gelassene oder entwendete Sitzung allein genügt dafür nicht.
 */
public final class RecentConfirmation {

    public static final Duration VALID_FOR = Duration.ofMinutes(10);
    private static final String ATTRIBUTE = "aidocuments.confirmedAt";

    private RecentConfirmation() {
    }

    /** Ein Code wurde eben bestätigt (bei der Anmeldung, beim Einrichten oder ausdrücklich). */
    public static void confirmed(HttpServletRequest request) {
        request.getSession().setAttribute(ATTRIBUTE, Instant.now());
    }

    public static boolean isRecent(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && session.getAttribute(ATTRIBUTE) instanceof Instant at
                && at.plus(VALID_FOR).isAfter(Instant.now());
    }
}
