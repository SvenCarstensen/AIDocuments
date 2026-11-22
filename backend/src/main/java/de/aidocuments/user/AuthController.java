package de.aidocuments.user;

import de.aidocuments.user.UserDtos.AuthStatusDto;
import de.aidocuments.user.UserDtos.ChangePasswordRequest;
import de.aidocuments.user.UserDtos.CodeRequest;
import de.aidocuments.user.UserDtos.LoginRequest;
import de.aidocuments.user.UserDtos.PasswordAndCodeRequest;
import de.aidocuments.user.UserDtos.SetupRequest;
import de.aidocuments.user.UserDtos.TwoFactorRequired;
import de.aidocuments.user.UserDtos.UserDto;
import de.aidocuments.security.RecentConfirmation;
import de.aidocuments.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthController.class);

    private final UserService userService;
    private final AuthenticationManager authenticationManager;
    private final LoginThrottle throttle;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();
    private final CsrfTokenRepository csrfTokens;
    private final TwoFactorService twoFactor;

    public AuthController(UserService userService, AuthenticationManager authenticationManager,
                          LoginThrottle throttle, CsrfTokenRepository csrfTokens, TwoFactorService twoFactor) {
        this.twoFactor = twoFactor;
        this.csrfTokens = csrfTokens;
        this.userService = userService;
        this.authenticationManager = authenticationManager;
        this.throttle = throttle;
    }

    /** Erster Aufruf der Oberfläche: Ist eine Ersteinrichtung nötig, und wer ist angemeldet? */
    @GetMapping("/status")
    public AuthStatusDto status(@AuthenticationPrincipal UserPrincipal principal, HttpServletRequest request) {
        // Token laden, damit das XSRF-TOKEN-Cookie gesetzt wird, bevor die Oberfläche etwas absendet.
        if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) {
            token.getToken();
        }
        UserDto user = principal == null ? null : userService.get(principal.id());
        return new AuthStatusDto(userService.setupRequired(), user);
    }

    @PostMapping("/setup")
    public UserDto setup(@Valid @RequestBody SetupRequest body, HttpServletRequest request,
                         HttpServletResponse response) {
        LoginThrottle.Attempt attempt = begin(null, request);
        try {
            userService.createInitialAdmin(body.username(), body.password(), body.setupCode());
        } catch (ApiException e) {
            if (e.getStatus() != HttpStatus.UNAUTHORIZED) {
                attempt.discard(); // nur ein falscher Code zählt als Rateversuch
            }
            throw e;
        }
        attempt.succeeded();
        return authenticate(body.username(), body.password(), request, response);
    }

    /**
     * Anmeldung mit Benutzername und Passwort. Ist für das Konto die Zwei-Faktor-Anmeldung eingerichtet, entsteht noch
     * keine Sitzung: Die Antwort ist {@code {"twoFactorRequired": true}}, und erst
     * {@link #loginTwoFactor} mit dem Code aus der App meldet an.
     */
    @PostMapping("/login")
    public Object login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
                        HttpServletResponse response) {
        LoginThrottle.Attempt attempt = begin(body.username(), request);
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(body.username().strip(), body.password()));
        } catch (AuthenticationException e) {
            throw ApiException.of(HttpStatus.UNAUTHORIZED, "error.auth.badCredentials");
        }
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        if (twoFactor.isEnabled(principal.id())) {
            // Passwort stimmt – kein Fehlversuch; gezählt wird beim Code erneut
            attempt.discard();
            HttpSession session = request.getSession(true);
            session.setAttribute(PENDING_LOGIN, new PendingLogin(principal.id(), principal.getUsername(),
                    principal.version(), Instant.now().plus(PENDING_LOGIN_TIME), 0));
            return new TwoFactorRequired(true);
        }
        attempt.succeeded();
        return establish(authentication, request, response);
    }

    /** Sitzungsattribut: Passwort geprüft, Code aus der App steht noch aus. */
    private static final String PENDING_LOGIN = "aidocuments.pendingTwoFactorLogin";
    private static final Duration PENDING_LOGIN_TIME = Duration.ofMinutes(5);
    private static final int PENDING_LOGIN_MAX_CODES = 5;

    private record PendingLogin(String userId, String username, long version, Instant expiresAt, int failures)
            implements Serializable {
    }

    /** Zweiter Schritt der Anmeldung: Code aus der Authenticator-App oder ein Wiederherstellungscode. */
    @PostMapping("/login/two-factor")
    public UserDto loginTwoFactor(@Valid @RequestBody CodeRequest body, HttpServletRequest request,
                                  HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        PendingLogin pending = session == null ? null
                : session.getAttribute(PENDING_LOGIN) instanceof PendingLogin p ? p : null;
        if (pending == null || pending.expiresAt().isBefore(Instant.now())
                || pending.version() != userService.currentVersion(pending.userId())) {
            if (session != null) {
                session.removeAttribute(PENDING_LOGIN);
            }
            throw ApiException.of(HttpStatus.UNAUTHORIZED, "error.twoFactor.expired");
        }
        LoginThrottle.Attempt attempt = throttle.beginSecondFactor(pending.username(), request.getRemoteAddr());
        if (attempt == null) {
            throw ApiException.of(HttpStatus.TOO_MANY_REQUESTS, "error.auth.tooManyAttempts");
        }
        if (!twoFactor.verifyLogin(pending.userId(), body.code())) {
            // das Passwort stimmte – wer hier wiederholt falsch liegt, kennt es womöglich unbefugt
            log.warn("Falscher Code bei der Zwei-Faktor-Anmeldung für „{}“ von {}{}", pending.username(),
                    request.getRemoteAddr(), throttle.secondFactorLocked(pending.username())
                            ? " – Konto für 24 Stunden gesperrt; ist das Passwort bekannt geworden?" : "");
            // nach fünf falschen Codes muss das Passwort erneut eingegeben werden
            if (pending.failures() + 1 >= PENDING_LOGIN_MAX_CODES) {
                session.removeAttribute(PENDING_LOGIN);
            } else {
                session.setAttribute(PENDING_LOGIN, new PendingLogin(pending.userId(), pending.username(),
                        pending.version(), pending.expiresAt(), pending.failures() + 1));
            }
            throw ApiException.of(HttpStatus.UNAUTHORIZED, "error.twoFactor.invalidCode");
        }
        session.removeAttribute(PENDING_LOGIN);
        UserPrincipal principal = (UserPrincipal) userService.loadUserByUsername(pending.username());
        if (!principal.isEnabled() || !principal.id().equals(pending.userId())) {
            throw ApiException.of(HttpStatus.UNAUTHORIZED, "error.auth.badCredentials");
        }
        attempt.succeeded();
        UserDto user = establish(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                principal.getAuthorities()), request, response);
        RecentConfirmation.confirmed(request); // der Code ist eben eingegeben – gilt auch als Bestätigung
        return user;
    }

    /**
     * Bestätigt die angemeldete Sitzung mit einem frischen Code aus der App – Voraussetzung für Änderungen an Konten,
     * Postfächern und Einstellungen (siehe {@link de.aidocuments.security.AdminTwoFactorFilter}).
     */
    private static final String CONFIRM_FAILURES = "aidocuments.confirmFailures";
    private static final int CONFIRM_MAX_FAILURES = 5;

    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody CodeRequest body,
                        HttpServletRequest request) {
        if (!twoFactor.isEnabled(principal.id())) {
            throw ApiException.conflict("error.twoFactor.notEnabled");
        }
        LoginThrottle.Attempt attempt = throttle.beginSecondFactor(principal.getUsername(), request.getRemoteAddr());
        if (attempt == null) {
            throw ApiException.of(HttpStatus.TOO_MANY_REQUESTS, "error.auth.tooManyAttempts");
        }
        HttpSession session = request.getSession();
        if (!twoFactor.verifyLogin(principal.id(), body.code())) {
            int failures = session.getAttribute(CONFIRM_FAILURES) instanceof Integer n ? n + 1 : 1;
            log.warn("Falscher Code bei der Bestätigung einer Änderung durch „{}“ von {} ({}. Versuch)",
                    principal.getUsername(), request.getRemoteAddr(), failures);
            if (failures >= CONFIRM_MAX_FAILURES) {
                // wer an einer fremden, offen gelassenen Sitzung rät, fliegt nach fünf Versuchen hinaus –
                // der Inhaber meldet sich einfach neu an
                session.invalidate();
                SecurityContextHolder.clearContext();
                throw ApiException.of(HttpStatus.UNAUTHORIZED, "error.twoFactor.invalidCode");
            }
            session.setAttribute(CONFIRM_FAILURES, failures);
            throw ApiException.badRequest("error.twoFactor.invalidCode");
        }
        attempt.succeeded();
        session.removeAttribute(CONFIRM_FAILURES);
        RecentConfirmation.confirmed(request);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Zwei-Faktor-Anmeldung einrichten und abschalten (für das eigene Konto)
    // ---------------------------------------------------------------------------------------------------------

    /** Sitzungsattribut: Schlüssel, der gerade eingerichtet wird (erst mit dem ersten Code gespeichert). */
    private static final String PENDING_SECRET = "aidocuments.pendingTwoFactorSecret";

    @GetMapping("/two-factor")
    public TwoFactorService.Status twoFactorStatus(@AuthenticationPrincipal UserPrincipal principal) {
        return twoFactor.status(principal.id());
    }

    @PostMapping("/two-factor/setup")
    public TwoFactorService.Setup twoFactorSetup(@AuthenticationPrincipal UserPrincipal principal,
                                                 HttpServletRequest request) {
        TwoFactorService.Setup setup = twoFactor.newSetup(principal.id());
        request.getSession().setAttribute(PENDING_SECRET, setup.secret());
        return setup;
    }

    @PostMapping("/two-factor/enable")
    public TwoFactorService.Enabled twoFactorEnable(@AuthenticationPrincipal UserPrincipal principal,
                                                    @Valid @RequestBody PasswordAndCodeRequest body,
                                                    HttpServletRequest request, HttpServletResponse response) {
        if (!(request.getSession().getAttribute(PENDING_SECRET) instanceof String secret)) {
            throw ApiException.badRequest("error.twoFactor.setupExpired");
        }
        LoginThrottle.Attempt attempt = begin(principal.getUsername(), request);
        TwoFactorService.Enabled enabled = twoFactor.enable(principal.id(), body.password(), secret, body.code());
        attempt.succeeded();
        request.getSession().removeAttribute(PENDING_SECRET);
        keepCurrentSession(principal.id(), request, response);
        RecentConfirmation.confirmed(request);
        return enabled;
    }

    @PostMapping("/two-factor/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void twoFactorDisable(@AuthenticationPrincipal UserPrincipal principal,
                                 @Valid @RequestBody PasswordAndCodeRequest body, HttpServletRequest request,
                                 HttpServletResponse response) {
        LoginThrottle.Attempt attempt = begin(principal.getUsername(), request);
        twoFactor.disable(principal.id(), body.password(), body.code());
        attempt.succeeded();
        keepCurrentSession(principal.id(), request, response);
    }

    /** Nach einer Änderung am Konto enden andere Sitzungen; die aufrufende bekommt den neuen Stand. */
    private void keepCurrentSession(String userId, HttpServletRequest request, HttpServletResponse response) {
        UserPrincipal updated = (UserPrincipal) userService.loadUserByUsername(userService.get(userId).username());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                updated, null, updated.getAuthorities()));
        SecurityContextHolder.setContext(context);
        request.changeSessionId();
        contextRepository.saveContext(context, request, response);
        renewCsrfToken(request, response);
    }

    /** Ändert das eigene Passwort; andere Sitzungen des Kontos enden, diese bleibt angemeldet. */
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal UserPrincipal principal,
                               @Valid @RequestBody ChangePasswordRequest body, HttpServletRequest request,
                               HttpServletResponse response) {
        // auch das bisherige Passwort darf eine fremde, offen gelassene Sitzung nicht durchprobieren können
        LoginThrottle.Attempt attempt = begin(principal.getUsername(), request);
        UserPrincipal updated = userService.changeOwnPassword(principal.id(), body.currentPassword(),
                body.newPassword());
        attempt.succeeded();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                updated, null, updated.getAuthorities()));
        SecurityContextHolder.setContext(context);
        request.changeSessionId();
        contextRepository.saveContext(context, request, response);
        renewCsrfToken(request, response);
    }

    private LoginThrottle.Attempt begin(String username, HttpServletRequest request) {
        LoginThrottle.Attempt attempt = throttle.begin(username, request.getRemoteAddr());
        if (attempt == null) {
            throw ApiException.of(HttpStatus.TOO_MANY_REQUESTS, "error.auth.tooManyAttempts");
        }
        return attempt;
    }

    private UserDto authenticate(String username, String password, HttpServletRequest request,
                                 HttpServletResponse response) {
        return establish(authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(username.strip(), password)), request, response);
    }

    /** Legt die angemeldete Sitzung an (neue Sitzungs-ID, neues CSRF-Token). */
    private UserDto establish(Authentication authentication, HttpServletRequest request,
                              HttpServletResponse response) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        if (request.getSession(false) != null) {
            request.changeSessionId(); // Schutz vor Session-Fixation
        }
        contextRepository.saveContext(context, request, response);
        renewCsrfToken(request, response);
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        userService.recordLogin(principal.id());
        return userService.get(principal.id());
    }

    /** Neues CSRF-Token nach der Anmeldung – ein vorher untergeschobenes Token wird damit wertlos. */
    private void renewCsrfToken(HttpServletRequest request, HttpServletResponse response) {
        csrfTokens.saveToken(null, request, response);
        csrfTokens.saveToken(csrfTokens.generateToken(request), request, response);
    }
}
