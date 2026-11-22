package de.aidocuments.settings;

import de.aidocuments.ai.AiAnalysisService;
import de.aidocuments.ai.AiException;
import de.aidocuments.i18n.Messages;
import de.aidocuments.importer.FolderImportService;
import de.aidocuments.importer.ImportScheduler;
import de.aidocuments.importer.ImportSettings.FolderMode;
import de.aidocuments.importer.ImportSettingsService;
import de.aidocuments.settings.AiSettingsService.AiSettingsDto;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Einstellungen für KI und Abruf – nur für Administratoren (siehe SecurityConfig). */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    public record TestResultDto(boolean ok, String message) {
    }

    public record ImportSettingsDto(int intervalMinutes, boolean folderEnabled, String folderPath,
                                    FolderMode folderMode, FolderImportService.Status folderStatus,
                                    Instant nextRunAt, String folderAreaId) {
    }

    private final AiSettingsService aiSettings;
    private final AiAnalysisService aiService;
    private final ImportSettingsService importSettings;
    private final FolderImportService folderImport;
    private final ImportScheduler scheduler;
    private final ThreadPoolTaskExecutor executor;
    private final Messages messages;

    public SettingsController(AiSettingsService aiSettings, AiAnalysisService aiService,
                              ImportSettingsService importSettings, FolderImportService folderImport,
                              ImportScheduler scheduler,
                              @Qualifier("importExecutor") ThreadPoolTaskExecutor executor, Messages messages) {
        this.messages = messages;
        this.aiSettings = aiSettings;
        this.aiService = aiService;
        this.importSettings = importSettings;
        this.folderImport = folderImport;
        this.scheduler = scheduler;
        this.executor = executor;
    }

    @GetMapping("/ai")
    public AiSettingsDto ai() {
        return aiSettings.dto();
    }

    @PutMapping("/ai")
    public AiSettingsDto updateAi(@Valid @RequestBody AiSettingsService.UpdateRequest request) {
        return aiSettings.update(request);
    }

    /** Prüft Adresse, Schlüssel und Modell der gespeicherten KI-Einstellungen; antwortet in jedem Fall zügig. */
    @PostMapping("/ai/test")
    public TestResultDto testAi() {
        try {
            return new TestResultDto(true, messages.render(aiService.testConnection()));
        } catch (AiException e) {
            return new TestResultDto(false, messages.render(e.msg()));
        }
    }

    /** Modell-IDs, die der gespeicherte Server anbietet – als Auswahlhilfe im Formular. */
    @GetMapping("/ai/models")
    public List<String> aiModels() {
        return aiService.availableModels();
    }

    @GetMapping("/import")
    public ImportSettingsDto importSettings() {
        return importDto();
    }

    @PutMapping("/import")
    public ImportSettingsDto updateImport(@Valid @RequestBody ImportSettingsService.UpdateRequest request) {
        importSettings.update(request);
        return importDto();
    }

    /** Durchsucht den Eingangsordner sofort (im Hintergrund). */
    @PostMapping("/import/scan")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void scanNow() {
        executor.execute(folderImport::scan);
    }

    private ImportSettingsDto importDto() {
        ImportSettingsService.Effective effective = importSettings.current();
        // Der Stand des Eingangsordners entsteht im Hintergrund und wird erst hier übersetzt.
        FolderImportService.Status status = folderImport.status();
        return new ImportSettingsDto(effective.intervalMinutes(), effective.folderEnabled(),
                effective.folderPath(), effective.folderMode(),
                status.withMessage(messages.localize(status.message())), scheduler.nextRunAt(),
                effective.folderAreaId());
    }
}
