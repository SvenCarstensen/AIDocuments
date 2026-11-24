package de.aidocuments.web;

import de.aidocuments.ai.BuiltinOcrService;
import de.aidocuments.document.Document;
import de.aidocuments.document.DocumentRepository;
import de.aidocuments.document.DocumentStatus;
import de.aidocuments.document.ProcessingEstimate;
import de.aidocuments.settings.AiSettingsService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Kennzahlen für die Oberfläche; für alle angemeldeten Benutzer lesbar. */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    public record SystemDto(boolean aiEnabled, String aiModel,
                            /* Scans liest die eingebaute Texterkennung – es gibt sie, und keine externe KI ist da */
                            boolean builtinOcr,
                            /* die eingebaute Texterkennung (Tesseract) steht bereit – unabhängig von der KI */
                            boolean ocrAvailable,
                            long documents, long processing, long failed,
                            long unanalyzed,
                            /* Dokumente, die als mögliches Duplikat eines anderen vermerkt sind */
                            long duplicates,
                            /* geschätzte Sekunden, bis die Warteschlange leer ist; null = (noch) keine Schätzung */
                            Long estimatedSecondsRemaining,
                            /* Dokumente, deren Aufbewahrungsfrist abgelaufen ist – sie dürfen jetzt gelöscht werden */
                            long retentionExpired,
                            /* nur für Admins: Postfächer, die sich wiederholt nicht abrufen lassen */
                            List<String> failingMailboxes,
                            /* nur für Admins: zu lange nicht gesichert oder die automatische Sicherung schlug fehl */
                            de.aidocuments.backup.AutoBackup.Notice backup,
                            /* Nutzungsart und Freischaltung – für alle sichtbar (Hinweis, „lizenziert für …“) */
                            de.aidocuments.license.LicenseService.Status license) {
    }

    private final DocumentRepository documents;
    private final AiSettingsService aiSettings;
    private final BuiltinOcrService builtinOcr;

    private final de.aidocuments.area.AreaAccess access;
    private final de.aidocuments.mail.MailAccountRepository mailAccounts;
    private final de.aidocuments.backup.AutoBackup autoBackup;
    private final de.aidocuments.license.LicenseService license;

    public SystemController(DocumentRepository documents, AiSettingsService aiSettings, BuiltinOcrService builtinOcr,
                            de.aidocuments.area.AreaAccess access,
                            de.aidocuments.mail.MailAccountRepository mailAccounts,
                            de.aidocuments.backup.AutoBackup autoBackup,
                            de.aidocuments.license.LicenseService license) {
        this.license = license;
        this.autoBackup = autoBackup;
        this.access = access;
        this.mailAccounts = mailAccounts;
        this.documents = documents;
        this.aiSettings = aiSettings;
        this.builtinOcr = builtinOcr;
    }

    @GetMapping
    public SystemDto system() {
        AiSettingsService.Effective ai = aiSettings.current();
        long processing = documents.countByStatusIn(List.of(DocumentStatus.PENDING, DocumentStatus.PROCESSING));
        Long remaining = processing == 0 ? null : ProcessingEstimate.secondsRemaining(processing,
                documents.findTop25ByProcessedAtNotNullOrderByProcessedAtDesc().stream()
                        .map(Document::getProcessedAt).toList());
        // die Warteschlange (processing, Restzeit) betrifft alle; was gezählt wird, nur die sichtbaren Bereiche
        java.util.Set<String> areas = access.visible();
        long visible = documents.countVisible(null, false, areas);
        return new SystemDto(ai.enabled(), ai.model(), !ai.enabled() && builtinOcr.isAvailable(),
                builtinOcr.isAvailable(), visible,
                processing,
                documents.countVisible(List.of(DocumentStatus.FAILED), false, areas),
                documents.countVisible(List.of(DocumentStatus.TEXT_ONLY), false, areas),
                documents.countVisible(null, true, areas),
                remaining,
                documents.idsWithRetentionExpired(java.time.LocalDate.now(), areas).size(),
                // alles sichtbar heißt hier: Admin
                areas == null ? mailAccounts.failingNames(de.aidocuments.mail.MailSyncService.FAILURES_BEFORE_NOTICE)
                        : List.of(),
                areas == null ? autoBackup.notice(visible) : null,
                license.status());
    }
}
