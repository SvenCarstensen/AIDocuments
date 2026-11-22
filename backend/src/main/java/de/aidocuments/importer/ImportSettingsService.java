package de.aidocuments.importer;

import static de.aidocuments.i18n.ValidationMessages.NOT_NULL;
import static de.aidocuments.i18n.ValidationMessages.SIZE;

import de.aidocuments.area.Area;
import de.aidocuments.area.AreaRepository;
import de.aidocuments.config.AppProperties;
import de.aidocuments.importer.ImportSettings.FolderMode;
import de.aidocuments.web.ApiException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import de.aidocuments.persistence.SettingsStore;
import org.springframework.stereotype.Service;

/** Wirksame Abruf-Einstellungen: gespeicherte Werte haben Vorrang vor den Vorgaben aus der Konfiguration. */
@Service
public class ImportSettingsService {

    public record Effective(int intervalMinutes, boolean folderEnabled, String folderPath, FolderMode folderMode,
                            String folderAreaId) {

        public Duration interval() {
            return Duration.ofMinutes(intervalMinutes);
        }
    }

    public record UpdateRequest(
            @Min(value = 1, message = "{validation.interval.min}")
            @Max(value = 1440, message = "{validation.interval.max}") int intervalMinutes,
            boolean folderEnabled,
            @Size(max = 1000, message = SIZE) String folderPath,
            @NotNull(message = NOT_NULL) FolderMode folderMode,
            /* null = der allgemeine Bereich */
            @Size(max = 64, message = SIZE) String folderAreaId) {
    }

    private final SettingsStore store;
    private final AreaRepository areas;
    private final AppProperties.Import defaults;
    private volatile Effective cached;

    public ImportSettingsService(SettingsStore store, AppProperties properties, AreaRepository areas) {
        this.store = store;
        this.areas = areas;
        this.defaults = properties.importer();
    }

    public Effective current() {
        Effective effective = cached;
        if (effective == null) {
            ImportSettings stored = store.find(ImportSettings.ID, ImportSettings.class);
            if (stored == null) {
                String directory = defaults.directory() == null ? "" : defaults.directory().strip();
                int minutes = (int) Math.clamp(defaults.interval().toMinutes(), 1, 1440);
                effective = new Effective(minutes, !directory.isEmpty(), directory, FolderMode.KEEP, Area.GENERAL);
            } else {
                effective = new Effective(stored.getIntervalMinutes(), stored.isFolderEnabled(),
                        stored.getFolderPath() == null ? "" : stored.getFolderPath(), stored.getFolderMode(),
                        stored.getFolderAreaId() == null ? Area.GENERAL : stored.getFolderAreaId());
            }
            cached = effective;
        }
        return effective;
    }

    public synchronized Effective update(UpdateRequest request) {
        String path = request.folderPath() == null ? "" : request.folderPath().strip();
        if (request.folderEnabled()) {
            if (path.isEmpty()) {
                throw ApiException.badRequest("error.folder.pathRequired");
            }
            Path directory;
            try {
                directory = Path.of(path);
            } catch (InvalidPathException e) {
                throw ApiException.badRequest("error.folder.pathInvalid");
            }
            if (!directory.isAbsolute()) {
                throw ApiException.badRequest("error.folder.pathNotAbsolute");
            }
            if (!Files.isDirectory(directory) || !Files.isReadable(directory)) {
                throw ApiException.badRequest("error.folder.notReadable", path);
            }
            if (request.folderMode() == FolderMode.MOVE && !Files.isWritable(directory)) {
                throw ApiException.badRequest("error.folder.readOnly");
            }
        }
        String areaId = request.folderAreaId() == null || request.folderAreaId().isBlank()
                ? Area.GENERAL : request.folderAreaId();
        if (areas.findById(areaId).isEmpty()) {
            throw ApiException.badRequest("error.area.notFound");
        }
        ImportSettings stored = new ImportSettings();
        stored.setFolderAreaId(areaId);
        stored.setIntervalMinutes(request.intervalMinutes());
        stored.setFolderEnabled(request.folderEnabled());
        stored.setFolderPath(path);
        stored.setFolderMode(request.folderMode());
        store.save(ImportSettings.ID, stored);
        cached = null;
        return current();
    }
}
