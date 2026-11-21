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
                            Long estimatedSecondsRemaining) {
    }

    private final DocumentRepository documents;
    private final AiSettingsService aiSettings;
    private final BuiltinOcrService builtinOcr;

    public SystemController(DocumentRepository documents, AiSettingsService aiSettings, BuiltinOcrService builtinOcr) {
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
        return new SystemDto(ai.enabled(), ai.model(), !ai.enabled() && builtinOcr.isAvailable(),
                builtinOcr.isAvailable(), documents.count(),
                processing,
                documents.countByStatusIn(List.of(DocumentStatus.FAILED)),
                documents.countByStatusIn(List.of(DocumentStatus.TEXT_ONLY)),
                documents.countByDuplicateOfIsNotNull(),
                remaining);
    }
}
