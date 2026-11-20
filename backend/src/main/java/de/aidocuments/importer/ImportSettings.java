package de.aidocuments.importer;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** In der Oberfläche gespeicherte Abruf-Einstellungen (genau ein Datensatz mit der ID "import"). */
@Document(collection = "settings")
public class ImportSettings {

    public static final String ID = "import";

    public enum FolderMode {
        /** Dateien bleiben liegen; die Anwendung merkt sich, was sie schon übernommen hat. */
        KEEP,
        /** Übernommene Dateien wandern in den Unterordner "importiert". */
        MOVE
    }

    @Id
    private String id = ID;
    private int intervalMinutes;
    private boolean folderEnabled;
    private String folderPath;
    private FolderMode folderMode = FolderMode.KEEP;

    public int getIntervalMinutes() {
        return intervalMinutes;
    }

    public void setIntervalMinutes(int intervalMinutes) {
        this.intervalMinutes = intervalMinutes;
    }

    public boolean isFolderEnabled() {
        return folderEnabled;
    }

    public void setFolderEnabled(boolean folderEnabled) {
        this.folderEnabled = folderEnabled;
    }

    public String getFolderPath() {
        return folderPath;
    }

    public void setFolderPath(String folderPath) {
        this.folderPath = folderPath;
    }

    public FolderMode getFolderMode() {
        return folderMode;
    }

    public void setFolderMode(FolderMode folderMode) {
        this.folderMode = folderMode;
    }
}
