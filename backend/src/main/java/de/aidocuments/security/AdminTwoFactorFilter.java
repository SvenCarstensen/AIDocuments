package de.aidocuments.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.aidocuments.i18n.Messages;
import de.aidocuments.i18n.Msg;
import de.aidocuments.user.UserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Schutz der Administration durch den zweiten Faktor, in zwei Stufen:
 *
 * <ul>
 *   <li>Für Administratoren ist die Zwei-Faktor-Anmeldung Pflicht: Solange sie nicht eingerichtet ist, erlaubt der
 *       Server nur die Anmeldung selbst ({@code /api/auth/**} – Status, Abmelden, Passwort, Einrichten). Alles andere
 *       beantwortet er mit 403 – die Oberfläche zeigt dann die Einrichtung, aber auch wer sie umgeht, kommt nicht
 *       weiter.</li>
 *   <li>Änderungen an Konten, Postfächern und Einstellungen verlangen einen frischen Code aus der App
 *       ({@link RecentConfirmation}): Sonst könnte jemand an einer offen gelassenen Sitzung ein eigenes
 *       Administratorkonto anlegen, Passwort und Zwei-Faktor-Anmeldung eines anderen zurücksetzen oder die KI auf einen
 *       eigenen Server umleiten und damit alle Dokumente abgreifen. Die Antwort ist dann 403 mit
 *       {@code "code": "confirmationRequired"}; die Oberfläche fragt den Code ab und wiederholt die Anfrage.</li>
 * </ul>
 */
public class AdminTwoFactorFilter extends OncePerRequestFilter {

    private static final ObjectMapper JSON = new ObjectMapper();
    /*
     * Dieselben Muster wie in SecurityConfig – sie vergleichen den dekodierten Pfad. Ein Vergleich mit der rohen
     * Adresse ließe sich mit kodierten Zeichen umgehen: „/%61pi/users“ ist für Spring „/api/users“.
     */
    private static final RequestMatcher API = matcher("/api/**");
    private static final RequestMatcher AUTH = matcher("/api/auth/**");
    /** öffentlich (Teilen-Links) – dort zählt nicht, wer angemeldet ist */
    private static final RequestMatcher SHARE = matcher("/api/share/**");
    private static final List<RequestMatcher> CONFIRMED = List.of(
            matcher("/api/users/**"), matcher("/api/mail-accounts/**"), matcher("/api/settings/**"));
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final Messages messages;
    private final LocaleResolver localeResolver;

    public AdminTwoFactorFilter(Messages messages, LocaleResolver localeResolver) {
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String path = request.getServletPath();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal
                && API.matches(request) && !SHARE.matches(request)) {
            if (principal.mustSetUpTwoFactor() && !AUTH.matches(request)) {
                refuse(request, response, path, "error.twoFactor.requiredForAdmin", null);
                return;
            }
            if (principal.isAdmin() && principal.twoFactorEnabled() && !SAFE_METHODS.contains(request.getMethod())
                    && CONFIRMED.stream().anyMatch(m -> m.matches(request)) && !RecentConfirmation.isRecent(request)) {
                refuse(request, response, path, "error.twoFactor.confirmationRequired", "confirmationRequired");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static RequestMatcher matcher(String pattern) {
        return PathPatternRequestMatcher.withDefaults().matcher(pattern);
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response, String path, String key,
                        String code) throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", HttpStatus.FORBIDDEN.getReasonPhrase());
        problem.put("status", HttpStatus.FORBIDDEN.value());
        // die Sprache wie überall in der Anwendung aus Accept-Language (der Filter läuft vor Spring MVC)
        problem.put("detail", messages.render(Msg.of(key), localeResolver.resolveLocale(request)));
        problem.put("instance", path);
        if (code != null) {
            problem.put("code", code);
        }
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(JSON.writeValueAsString(problem));
    }
}
