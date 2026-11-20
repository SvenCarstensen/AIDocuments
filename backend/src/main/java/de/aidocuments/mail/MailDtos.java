package de.aidocuments.mail;

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

    /** Das Passwort wird nie ausgeliefert. */
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
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 255) String host,
            @Min(1) @Max(65535) int port,
            @NotNull MailSecurity security,
            @NotBlank @Size(max = 255) String username,
            /* beim Bearbeiten: null oder leer = gespeichertes Passwort behalten */
            @Size(max = 500) String password,
            @NotBlank @Size(max = 255) String folder,
            LocalDate importSince,
            boolean enabled) {
    }

    /** Verbindungstest; {@code accountId} erlaubt den Test mit dem bereits gespeicherten Passwort. */
    public record ConnectionTestRequest(String accountId, @NotNull @jakarta.validation.Valid MailAccountRequest account) {
    }

    public record ConnectionTestDto(boolean ok, String message) {
    }
}
