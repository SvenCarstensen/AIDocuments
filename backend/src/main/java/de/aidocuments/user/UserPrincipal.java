package de.aidocuments.user;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Der angemeldete Benutzer in der Sitzung. {@code version} ist der Stand des Kontos zum Zeitpunkt der
 * Anmeldung – ändert ein Admin Rolle, Passwort oder Status, wird die Sitzung dadurch ungültig.
 */
public record UserPrincipal(String id, String username, String passwordHash, Role role, boolean enabled,
                            long version, boolean twoFactorEnabled) implements UserDetails, Serializable {

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    /** Administratoren müssen die Zwei-Faktor-Anmeldung einrichten, bevor sie die Anwendung nutzen können. */
    public boolean mustSetUpTwoFactor() {
        return isAdmin() && !twoFactorEnabled;
    }
}
