package de.aidocuments.web;

import de.aidocuments.document.DocumentRepository;
import de.aidocuments.document.DocumentStatus;
import de.aidocuments.settings.AiSettingsService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Kennzahlen für die Oberfläche; für alle angemeldeten Benutzer lesbar. */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    public record SystemDto(boolean aiEnabled, String aiModel, long documents, long processing, long failed,
                            long unanalyzed) {
    }

    private final DocumentRepository documents;
    private final AiSettingsService aiSettings;

    public SystemController(DocumentRepository documents, AiSettingsService aiSettings) {
        this.documents = documents;
        this.aiSettings = aiSettings;
    }

    @GetMapping
    public SystemDto system() {
        AiSettingsService.Effective ai = aiSettings.current();
        return new SystemDto(ai.enabled(), ai.model(), documents.count(),
                documents.countByStatusIn(List.of(DocumentStatus.PENDING, DocumentStatus.PROCESSING)),
                documents.countByStatusIn(List.of(DocumentStatus.FAILED)),
                documents.countByStatusIn(List.of(DocumentStatus.TEXT_ONLY)));
    }
}
