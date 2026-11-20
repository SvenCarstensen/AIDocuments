package de.aidocuments.importer;

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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/** Wirksame Abruf-Einstellungen: gespeicherte Werte haben Vorrang vor den Vorgaben aus der Konfiguration. */
@Service
public class ImportSettingsService {

    public record Effective(int intervalMinutes, boolean folderEnabled, String folderPath, FolderMode folderMode) {

        public Duration interval() {
            return Duration.ofMinutes(intervalMinutes);
        }
    }

    public record UpdateRequest(
            @Min(value = 1, message = "mindestens 1 Minute")
            @Max(value = 1440, message = "höchstens 1440 Minuten (24 Stunden)") int intervalMinutes,
            boolean folderEnabled,
            @Size(max = 1000) String folderPath,
            @NotNull FolderMode folderMode) {
    }

    private final MongoTemplate mongo;
    private final AppProperties.Import defaults;
    private volatile Effective cached;

    public ImportSettingsService(MongoTemplate mongo, AppProperties properties) {
        this.mongo = mongo;
        this.defaults = properties.importer();
    }

    public Effective current() {
        Effective effective = cached;
        if (effective == null) {
            ImportSettings stored = mongo.findById(ImportSettings.ID, ImportSettings.class);
            if (stored == null) {
                String directory = defaults.directory() == null ? "" : defaults.directory().strip();
                int minutes = (int) Math.clamp(defaults.interval().toMinutes(), 1, 1440);
                effective = new Effective(minutes, !directory.isEmpty(), directory, FolderMode.KEEP);
            } else {
                effective = new Effective(stored.getIntervalMinutes(), stored.isFolderEnabled(),
                        stored.getFolderPath() == null ? "" : stored.getFolderPath(), stored.getFolderMode());
            }
            cached = effective;
        }
        return effective;
    }

    public synchronized Effective update(UpdateRequest request) {
        String path = request.folderPath() == null ? "" : request.folderPath().strip();
        if (request.folderEnabled()) {
            if (path.isEmpty()) {
                throw ApiException.badRequest("Bitte den Pfad des Eingangsordners angeben.");
            }
            Path directory;
            try {
                directory = Path.of(path);
            } catch (InvalidPathException e) {
                throw ApiException.badRequest("Der Pfad ist ungültig.");
            }
            if (!directory.isAbsolute()) {
                throw ApiException.badRequest("Bitte einen absoluten Pfad angeben (z. B. /import).");
            }
            if (!Files.isDirectory(directory) || !Files.isReadable(directory)) {
                throw ApiException.badRequest("Der Ordner „" + path + "“ existiert auf dem Server nicht "
                        + "oder ist nicht lesbar.");
            }
            if (request.folderMode() == FolderMode.MOVE && !Files.isWritable(directory)) {
                throw ApiException.badRequest("Der Ordner ist schreibgeschützt – zum Verschieben übernommener "
                        + "Dateien braucht die Anwendung Schreibrechte.");
            }
        }
        ImportSettings stored = new ImportSettings();
        stored.setIntervalMinutes(request.intervalMinutes());
        stored.setFolderEnabled(request.folderEnabled());
        stored.setFolderPath(path);
        stored.setFolderMode(request.folderMode());
        mongo.save(stored);
        cached = null;
        return current();
    }
}
