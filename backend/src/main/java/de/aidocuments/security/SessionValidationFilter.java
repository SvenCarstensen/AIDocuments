package de.aidocuments.security;

import de.aidocuments.user.UserPrincipal;
import de.aidocuments.user.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Beendet Sitzungen, deren Konto seit der Anmeldung geändert, deaktiviert oder gelöscht wurde –
 * sonst behielte z. B. ein herabgestufter Administrator seine Rechte bis zur Abmeldung.
 */
public class SessionValidationFilter extends OncePerRequestFilter {

    private final UserService userService;

    public SessionValidationFilter(UserService userService) {
        this.userService = userService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal
                && principal.version() != userService.currentVersion(principal.id())) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            SecurityContextHolder.clearContext();
        }
        chain.doFilter(request, response);
    }
}
