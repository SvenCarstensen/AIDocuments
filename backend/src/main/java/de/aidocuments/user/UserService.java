package de.aidocuments.user;

import de.aidocuments.user.UserDtos.CreateUserRequest;
import de.aidocuments.user.UserDtos.UpdateUserRequest;
import de.aidocuments.user.UserDtos.UserDto;
import de.aidocuments.web.ApiException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService implements UserDetailsService {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    /** Zähler je Benutzer; jede sicherheitsrelevante Änderung macht bestehende Sitzungen ungültig. */
    private final Map<String, Long> versions = new ConcurrentHashMap<>();

    public UserService(AppUserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser user = users.findByUsername(normalize(username))
                .orElseThrow(() -> new UsernameNotFoundException("Unbekannter Benutzer"));
        return principalOf(user);
    }

    public boolean setupRequired() {
        return users.count() == 0;
    }

    /** Legt beim allerersten Start das Administratorkonto an. */
    public synchronized AppUser createInitialAdmin(String username, String password) {
        if (!setupRequired()) {
            throw ApiException.conflict("Die Ersteinrichtung wurde bereits abgeschlossen.");
        }
        return users.save(new AppUser(newId(), normalize(username), passwordEncoder.encode(password), Role.ADMIN));
    }

    public List<UserDto> list() {
        return users.findAll().stream()
                .sorted(Comparator.comparing(AppUser::getUsername))
                .map(UserDto::of)
                .toList();
    }

    public UserDto get(String id) {
        return UserDto.of(require(id));
    }

    public UserDto create(CreateUserRequest request) {
        String username = normalize(request.username());
        if (users.findByUsername(username).isPresent()) {
            throw ApiException.conflict("Der Benutzername „" + username + "“ ist bereits vergeben.");
        }
        try {
            return UserDto.of(users.save(
                    new AppUser(newId(), username, passwordEncoder.encode(request.password()), request.role())));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("Der Benutzername „" + username + "“ ist bereits vergeben.");
        }
    }

    public synchronized UserDto update(String id, UpdateUserRequest request) {
        AppUser user = require(id);
        boolean losesAdmin = user.getRole() == Role.ADMIN && user.isEnabled()
                && (request.role() != Role.ADMIN || !request.enabled());
        if (losesAdmin && users.countByRoleAndEnabled(Role.ADMIN, true) <= 1) {
            throw ApiException.conflict("Es muss mindestens ein aktiver Administrator bestehen bleiben.");
        }
        user.setRole(request.role());
        user.setEnabled(request.enabled());
        if (request.password() != null && !request.password().isBlank()) {
            if (request.password().length() < 8) {
                throw ApiException.badRequest("Das Passwort braucht " + UserDtos.PASSWORD_MESSAGE + ".");
            }
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }
        users.save(user);
        invalidateSessions(id);
        return UserDto.of(user);
    }

    public synchronized void delete(String id, String actingUserId) {
        AppUser user = require(id);
        if (id.equals(actingUserId)) {
            throw ApiException.conflict("Das eigene Konto kann nicht gelöscht werden.");
        }
        if (user.getRole() == Role.ADMIN && user.isEnabled()
                && users.countByRoleAndEnabled(Role.ADMIN, true) <= 1) {
            throw ApiException.conflict("Es muss mindestens ein aktiver Administrator bestehen bleiben.");
        }
        users.delete(user);
        invalidateSessions(id);
    }

    public void changeOwnPassword(String id, String currentPassword, String newPassword) {
        AppUser user = require(id);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("Das aktuelle Passwort ist nicht korrekt.");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        users.save(user);
    }

    public void recordLogin(String id) {
        users.findById(id).ifPresent(user -> {
            user.setLastLoginAt(Instant.now());
            users.save(user);
        });
    }

    public UserPrincipal principalOf(AppUser user) {
        return new UserPrincipal(user.getId(), user.getUsername(), user.getPasswordHash(), user.getRole(),
                user.isEnabled(), currentVersion(user.getId()));
    }

    public long currentVersion(String userId) {
        return versions.getOrDefault(userId, 0L);
    }

    private void invalidateSessions(String userId) {
        versions.merge(userId, 1L, Long::sum);
    }

    private AppUser require(String id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("Der Benutzer"));
    }

    private static String normalize(String username) {
        return username.strip().toLowerCase(Locale.ROOT);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
