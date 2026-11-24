package de.aidocuments.user;

import de.aidocuments.user.UserDtos.CreateUserRequest;
import de.aidocuments.user.UserDtos.UpdateUserRequest;
import de.aidocuments.user.UserDtos.UserDto;
import de.aidocuments.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    /** Zähler je Benutzer; jede sicherheitsrelevante Änderung macht bestehende Sitzungen ungültig. */
    private final Map<String, Long> versions = new ConcurrentHashMap<>();

    private final de.aidocuments.area.AreaRepository areas;

    private final de.aidocuments.reminder.ReminderRepository reminders;
    private final de.aidocuments.contract.ContractRepository contracts;
    private final PasskeyService passkeys;

    public UserService(AppUserRepository users, PasswordEncoder passwordEncoder,
                       de.aidocuments.area.AreaRepository areas, de.aidocuments.reminder.ReminderRepository reminders,
                       de.aidocuments.contract.ContractRepository contracts, PasskeyService passkeys) {
        this.passkeys = passkeys;
        this.reminders = reminders;
        this.contracts = contracts;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.areas = areas;
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

    /**
     * Einmaliger Code für die Ersteinrichtung. Ohne ihn könnte jeder, der eine frisch gestartete, aus dem Internet
     * erreichbare Instanz zuerst aufruft, sich selbst zum Administrator machen. Der Code steht nur im Protokoll des
     * Servers – wer ihn kennt, hat Zugriff auf den Server und ist damit der Betreiber.
     */
    private volatile String setupCode;

    @EventListener(ApplicationReadyEvent.class)
    public void announceSetupCode() {
        if (setupRequired()) {
            log.warn("""

                    ================================================================
                      AIDocuments ist noch nicht eingerichtet.
                      Einrichtungscode für das Administratorkonto: {}
                      (Initial setup code – enter it on the setup page.)
                    ================================================================""", setupCode());
        }
    }

    /** Der Einrichtungscode dieses Starts – nur für das Protokoll und für Tests. */
    public synchronized String setupCode() {
        if (setupCode == null) {
            StringBuilder code = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                if (i > 0 && i % 4 == 0) {
                    code.append('-');
                }
                code.append(SETUP_CODE_ALPHABET.charAt(random.nextInt(SETUP_CODE_ALPHABET.length())));
            }
            setupCode = code.toString();
        }
        return setupCode;
    }

    private static final String SETUP_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final SecureRandom random = new SecureRandom();

    /** Legt beim allerersten Start das Administratorkonto an – nur mit dem Einrichtungscode aus dem Protokoll. */
    public synchronized AppUser createInitialAdmin(String username, String password, String code) {
        if (!setupRequired()) {
            throw ApiException.conflict("error.setup.alreadyDone");
        }
        String given = code == null ? "" : code.strip().toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "");
        String expected = setupCode().replace("-", "");
        if (!MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.of(HttpStatus.UNAUTHORIZED, "error.setup.wrongCode");
        }
        AppUser admin = users.save(
                new AppUser(newId(), normalize(username), encode(password), Role.ADMIN));
        setupCode = null;
        return admin;
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
            throw ApiException.conflict("error.user.nameTaken", username);
        }
        try {
            return UserDto.of(users.save(
                    new AppUser(newId(), username, encode(request.password()), request.role())));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("error.user.nameTaken", username);
        }
    }

    public synchronized UserDto update(String id, UpdateUserRequest request) {
        AppUser user = require(id);
        boolean losesAdmin = user.getRole() == Role.ADMIN && user.isEnabled()
                && (request.role() != Role.ADMIN || !request.enabled());
        if (losesAdmin && users.countByRoleAndEnabled(Role.ADMIN, true) <= 1) {
            throw ApiException.conflict("error.user.lastAdmin");
        }
        user.setRole(request.role());
        user.setEnabled(request.enabled());
        if (request.password() != null && !request.password().isBlank()) {
            if (request.password().length() < 8) {
                throw ApiException.badRequest("error.user.passwordTooShort");
            }
            user.setPasswordHash(encode(request.password()));
        }
        users.save(user);
        if (request.password() != null && !request.password().isBlank()) {
            // Passwort zurückgesetzt (Konto wiederhergestellt): Passkeys ersetzen Passwort und Code – wer einen
            // entwendet hat, käme sonst weiter herein
            passkeys.deleteAll(id);
        }
        invalidateSessions(id);
        return UserDto.of(user);
    }

    public synchronized void delete(String id, String actingUserId) {
        AppUser user = require(id);
        if (id.equals(actingUserId)) {
            throw ApiException.conflict("error.user.cannotDeleteSelf");
        }
        if (user.getRole() == Role.ADMIN && user.isEnabled()
                && users.countByRoleAndEnabled(Role.ADMIN, true) <= 1) {
            throw ApiException.conflict("error.user.lastAdmin");
        }
        users.delete(user);
        areas.removeUser(id);
        reminders.unassign(id);
        contracts.unassign(id);
        passkeys.deleteAll(id);
        invalidateSessions(id);
    }

    /**
     * Ändert das eigene Passwort und beendet alle anderen Sitzungen des Kontos (etwa eine, die jemand mit dem alten
     * Passwort geöffnet hat).
     *
     * @return die Anmeldedaten mit dem neuen Stand – damit bleibt die aufrufende Sitzung gültig
     */
    /** Das eigene Konto – für Schritte, die zusätzlich das Passwort verlangen (etwa einen Passkey anlegen). */
    public AppUser requireOwnPassword(String id, String password) {
        AppUser user = require(id);
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw ApiException.badRequest("error.user.currentPasswordWrong");
        }
        return user;
    }

    public AppUser account(String id) {
        return require(id);
    }

    public UserPrincipal changeOwnPassword(String id, String currentPassword, String newPassword) {
        AppUser user = require(id);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("error.user.currentPasswordWrong");
        }
        user.setPasswordHash(encode(newPassword));
        users.save(user);
        invalidateSessions(id);
        return principalOf(user);
    }

    /**
     * Eigene Adresse für Erinnerungen; die Sprache der E-Mails ist die, in der die Oberfläche gerade läuft.
     *
     * @param email leer = keine E-Mails
     */
    public UserDto updateProfile(String id, UserDtos.ProfileRequest request, java.util.Locale locale) {
        AppUser user = require(id);
        String email = request.email();
        user.setEmail(email == null || email.isBlank() ? null : email.strip());
        user.setLocale(locale == null ? null : locale.toLanguageTag());
        if (request.notifyNewDocuments() != null) {
            user.setNotifyNewDocuments(request.notifyNewDocuments());
        }
        if (request.notifyAdmin() != null) {
            user.setNotifyAdmin(request.notifyAdmin());
        }
        users.save(user);
        return UserDto.of(user);
    }

    public void recordLogin(String id) {
        users.findById(id).ifPresent(user -> {
            user.setLastLoginAt(Instant.now());
            users.save(user);
        });
    }

    public UserPrincipal principalOf(AppUser user) {
        return new UserPrincipal(user.getId(), user.getUsername(), user.getPasswordHash(), user.getRole(),
                user.isEnabled(), currentVersion(user.getId()), user.isTwoFactorEnabled());
    }

    public long currentVersion(String userId) {
        return versions.getOrDefault(userId, 0L);
    }

    /** Beendet alle Sitzungen des Kontos (beim nächsten Aufruf, siehe SessionValidationFilter). */
    void invalidateSessions(String userId) {
        versions.merge(userId, 1L, Long::sum);
    }

    private AppUser require(String id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("error.user.notFound"));
    }

    /** bcrypt verarbeitet höchstens 72 Byte – längere Passwörter werden abgelehnt statt mit einem Serverfehler. */
    private String encode(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw ApiException.badRequest("error.user.passwordTooLong");
        }
        return passwordEncoder.encode(password);
    }

    private static String normalize(String username) {
        return username.strip().toLowerCase(Locale.ROOT);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
