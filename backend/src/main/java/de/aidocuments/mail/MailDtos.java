package de.aidocuments.mail;

import static de.aidocuments.i18n.ValidationMessages.MAX;
import static de.aidocuments.i18n.ValidationMessages.MIN;
import static de.aidocuments.i18n.ValidationMessages.NOT_BLANK;
import static de.aidocuments.i18n.ValidationMessages.NOT_NULL;
import static de.aidocuments.i18n.ValidationMessages.SIZE;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;

public final class MailDtos {

    private MailDtos() {
    }

    /** Das Passwort wird nie ausgeliefert. {@code lastSyncMessage} ist bereits in die Sprache des Lesers übersetzt. */
    public record MailAccountDto(
            String id,
            String name,
            String host,
            int port,
            MailSecurity security,
            String username,
            String folder,
            LocalDate importSince,
            boolean enabled,
            Instant lastSyncAt,
            MailAccount.SyncStatus lastSyncStatus,
            String lastSyncMessage,
            long documentCount) {
    }

    public record MailAccountRequest(
            @NotBlank(message = NOT_BLANK) @Size(max = 120, message = SIZE) String name,
            @NotBlank(message = NOT_BLANK) @Size(max = 255, message = SIZE) String host,
            @Min(value = 1, message = MIN) @Max(value = 65535, message = MAX) int port,
            @NotNull(message = NOT_NULL) MailSecurity security,
            @NotBlank(message = NOT_BLANK) @Size(max = 255, message = SIZE) String username,
            /* beim Bearbeiten: null oder leer = gespeichertes Passwort behalten */
            @Size(max = 500, message = SIZE) String password,
            @NotBlank(message = NOT_BLANK) @Size(max = 255, message = SIZE) String folder,
            LocalDate importSince,
            boolean enabled) {
    }

    /** Verbindungstest; {@code accountId} erlaubt den Test mit dem bereits gespeicherten Passwort. */
    public record ConnectionTestRequest(String accountId,
                                        @NotNull(message = NOT_NULL) @jakarta.validation.Valid MailAccountRequest account) {
    }

    public record ConnectionTestDto(boolean ok, String message) {
    }
}
