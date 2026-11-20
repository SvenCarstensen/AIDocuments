package de.aidocuments.user;

import de.aidocuments.user.UserDtos.AuthStatusDto;
import de.aidocuments.user.UserDtos.ChangePasswordRequest;
import de.aidocuments.user.UserDtos.CredentialsRequest;
import de.aidocuments.user.UserDtos.LoginRequest;
import de.aidocuments.user.UserDtos.UserDto;
import de.aidocuments.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final AuthenticationManager authenticationManager;
    private final LoginThrottle throttle;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public AuthController(UserService userService, AuthenticationManager authenticationManager,
                          LoginThrottle throttle) {
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
    public UserDto setup(@Valid @RequestBody CredentialsRequest body, HttpServletRequest request,
                         HttpServletResponse response) {
        userService.createInitialAdmin(body.username(), body.password());
        return authenticate(body.username(), body.password(), request, response);
    }

    @PostMapping("/login")
    public UserDto login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
                         HttpServletResponse response) {
        if (throttle.isLocked(body.username())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Zu viele Fehlversuche. Bitte in einigen Minuten erneut versuchen.");
        }
        try {
            UserDto user = authenticate(body.username(), body.password(), request, response);
            throttle.recordSuccess(body.username());
            return user;
        } catch (AuthenticationException e) {
            throttle.recordFailure(body.username());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Benutzername oder Passwort ist nicht korrekt.");
        }
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal UserPrincipal principal,
                               @Valid @RequestBody ChangePasswordRequest body) {
        userService.changeOwnPassword(principal.id(), body.currentPassword(), body.newPassword());
    }

    private UserDto authenticate(String username, String password, HttpServletRequest request,
                                 HttpServletResponse response) {
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(username.strip(), password));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        if (request.getSession(false) != null) {
            request.changeSessionId(); // Schutz vor Session-Fixation
        }
        contextRepository.saveContext(context, request, response);
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        userService.recordLogin(principal.id());
        return userService.get(principal.id());
    }
}
