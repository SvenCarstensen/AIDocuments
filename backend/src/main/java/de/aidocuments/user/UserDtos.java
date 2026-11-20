package de.aidocuments.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class UserDtos {

    private UserDtos() {
    }

    public static final String USERNAME_PATTERN = "^[A-Za-z0-9._@-]{3,50}$";
    public static final String USERNAME_MESSAGE =
            "3–50 Zeichen; erlaubt sind Buchstaben, Ziffern, Punkt, Bindestrich, Unterstrich und @";
    public static final String PASSWORD_MESSAGE = "mindestens 8 Zeichen";

    public record UserDto(String id, String username, Role role, boolean enabled, Instant createdAt,
                          Instant lastLoginAt) {

        static UserDto of(AppUser user) {
            return new UserDto(user.getId(), user.getUsername(), user.getRole(), user.isEnabled(),
                    user.getCreatedAt(), user.getLastLoginAt());
        }
    }

    public record CredentialsRequest(
            @NotBlank @Pattern(regexp = USERNAME_PATTERN, message = USERNAME_MESSAGE) String username,
            @NotBlank @Size(min = 8, max = 200, message = PASSWORD_MESSAGE) String password) {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record CreateUserRequest(
            @NotBlank @Pattern(regexp = USERNAME_PATTERN, message = USERNAME_MESSAGE) String username,
            @NotBlank @Size(min = 8, max = 200, message = PASSWORD_MESSAGE) String password,
            @NotNull Role role) {
    }

    public record UpdateUserRequest(
            @NotNull Role role,
            boolean enabled,
            /* null oder leer = Passwort unverändert lassen */
            @Size(max = 200) String password) {
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8, max = 200, message = PASSWORD_MESSAGE) String newPassword) {
    }

    public record AuthStatusDto(boolean setupRequired, UserDto user) {
    }
}
