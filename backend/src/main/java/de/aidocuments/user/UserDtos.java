package de.aidocuments.user;

import static de.aidocuments.i18n.ValidationMessages.NOT_BLANK;
import static de.aidocuments.i18n.ValidationMessages.NOT_NULL;
import static de.aidocuments.i18n.ValidationMessages.SIZE;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class UserDtos {

    private UserDtos() {
    }

    public static final String USERNAME_PATTERN = "^[A-Za-z0-9._@-]{3,50}$";
    /** Schlüssel in messages.properties; Bean Validation löst sie in der Sprache der Anfrage auf. */
    public static final String USERNAME_MESSAGE = "{validation.username}";
    public static final String PASSWORD_MESSAGE = "{validation.password}";

    public record UserDto(String id, String username, Role role, boolean enabled, Instant createdAt,
                          Instant lastLoginAt, boolean twoFactorEnabled,
                          /* für Erinnerungen per E-Mail; null = keine */
                          String email,
                          /* tägliche Übersicht neuer Dokumente; Hinweise an Admins */
                          boolean notifyNewDocuments, boolean notifyAdmin) {

        static UserDto of(AppUser user) {
            return new UserDto(user.getId(), user.getUsername(), user.getRole(), user.isEnabled(),
                    user.getCreatedAt(), user.getLastLoginAt(), user.isTwoFactorEnabled(), user.getEmail(),
                    user.isNotifyNewDocuments(), user.isNotifyAdmin());
        }
    }

    /**
     * Eigene Angaben: die Adresse für Erinnerungen und Benachrichtigungen (leer = keine E-Mails) und welche
     * Benachrichtigungen; {@code null} lässt die bisherige Wahl.
     */
    public record ProfileRequest(
            @Size(max = 254, message = SIZE)
            @jakarta.validation.constraints.Email(message = "{validation.email}") String email,
            Boolean notifyNewDocuments, Boolean notifyAdmin) {
    }

    /** Antwort der Anmeldung, wenn noch der Code aus der Authenticator-App fehlt. */
    public record TwoFactorRequired(boolean twoFactorRequired) {
    }

    public record CodeRequest(@NotBlank(message = NOT_BLANK) @Size(max = 50, message = SIZE) String code) {
    }

    public record PasswordAndCodeRequest(
            @NotBlank(message = NOT_BLANK) @Size(max = 200, message = SIZE) String password,
            @NotBlank(message = NOT_BLANK) @Size(max = 50, message = SIZE) String code) {
    }

    /** Ersteinrichtung: Administratorkonto plus Einrichtungscode aus dem Protokoll des Servers. */
    public record SetupRequest(
            @NotBlank(message = NOT_BLANK) @Pattern(regexp = USERNAME_PATTERN, message = USERNAME_MESSAGE) String username,
            @NotBlank(message = NOT_BLANK) @Size(min = 8, max = 200, message = PASSWORD_MESSAGE) String password,
            @NotBlank(message = NOT_BLANK) @Size(max = 100, message = SIZE) String setupCode) {
    }

    public record LoginRequest(@NotBlank(message = NOT_BLANK) @Size(max = 200, message = SIZE) String username,
                               @NotBlank(message = NOT_BLANK) @Size(max = 200, message = SIZE) String password) {
    }

    public record CreateUserRequest(
            @NotBlank(message = NOT_BLANK) @Pattern(regexp = USERNAME_PATTERN, message = USERNAME_MESSAGE) String username,
            @NotBlank(message = NOT_BLANK) @Size(min = 8, max = 200, message = PASSWORD_MESSAGE) String password,
            @NotNull(message = NOT_NULL) Role role) {
    }

    public record UpdateUserRequest(
            @NotNull(message = NOT_NULL) Role role,
            boolean enabled,
            /* null oder leer = Passwort unverändert lassen */
            @Size(max = 200, message = SIZE) String password) {
    }

    public record ChangePasswordRequest(
            @NotBlank(message = NOT_BLANK) String currentPassword,
            @NotBlank(message = NOT_BLANK) @Size(min = 8, max = 200, message = PASSWORD_MESSAGE) String newPassword) {
    }

    public record AuthStatusDto(boolean setupRequired, UserDto user) {
    }
}
