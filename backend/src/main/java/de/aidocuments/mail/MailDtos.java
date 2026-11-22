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
import java.util.List;

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
            MailFilterDto filter,
            /* eigenes Intervall oder null; effectiveIntervalMinutes ist das, was tatsächlich gilt */
            Integer intervalMinutes,
            int effectiveIntervalMinutes,
            /* null = pausiert */
            Instant nextSyncAt,
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
            boolean enabled,
            /* null = keine Einschränkung */
            @jakarta.validation.Valid MailFilterDto filter,
            /* Minuten; null = das allgemeine Abrufintervall */
            @Min(value = MailSchedule.MIN_INTERVAL_MINUTES, message = "{validation.interval.min}")
            @Max(value = MailSchedule.MAX_INTERVAL_MINUTES, message = "{validation.interval.max}")
            Integer intervalMinutes) {
    }

    /**
     * Betreff- und Absenderfilter eines Postfachs, je Liste ein Begriff pro Eintrag (siehe {@link MailFilter}).
     * Leere Listen schränken nicht ein.
     */
    public record MailFilterDto(
            @Size(max = MailFilter.MAX_TERMS, message = SIZE)
            List<@Size(max = MailFilter.MAX_TERM_LENGTH, message = SIZE) String> subjectIncludes,
            @Size(max = MailFilter.MAX_TERMS, message = SIZE)
            List<@Size(max = MailFilter.MAX_TERM_LENGTH, message = SIZE) String> subjectExcludes,
            @Size(max = MailFilter.MAX_TERMS, message = SIZE)
            List<@Size(max = MailFilter.MAX_TERM_LENGTH, message = SIZE) String> senderIncludes,
            @Size(max = MailFilter.MAX_TERMS, message = SIZE)
            List<@Size(max = MailFilter.MAX_TERM_LENGTH, message = SIZE) String> senderExcludes) {

        static MailFilterDto of(MailFilter filter) {
            return new MailFilterDto(filter.subjectIncludes(), filter.subjectExcludes(), filter.senderIncludes(),
                    filter.senderExcludes());
        }

        MailFilter toFilter() {
            return new MailFilter(subjectIncludes, subjectExcludes, senderIncludes, senderExcludes);
        }
    }

    /** Verbindungstest; {@code accountId} erlaubt den Test mit dem bereits gespeicherten Passwort. */
    public record ConnectionTestRequest(String accountId,
                                        @NotNull(message = NOT_NULL) @jakarta.validation.Valid MailAccountRequest account) {
    }

    public record ConnectionTestDto(boolean ok, String message) {
    }
}
