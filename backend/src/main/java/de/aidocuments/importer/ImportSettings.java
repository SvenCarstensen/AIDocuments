package de.aidocuments.importer;

/** In der Oberfläche gespeicherte Abruf-Einstellungen (Datensatz "import" der Tabelle settings). */
public class ImportSettings {

    public static final String ID = "import";

    public enum FolderMode {
        /** Dateien bleiben liegen; die Anwendung merkt sich, was sie schon übernommen hat. */
        KEEP,
        /** Übernommene Dateien wandern in den Unterordner "importiert". */
        MOVE
    }

    private int intervalMinutes;
    private boolean folderEnabled;
    private String folderPath;
    private FolderMode folderMode = FolderMode.KEEP;
    /** In welchen Bereich Dateien aus dem Eingangsordner kommen; fehlt bei älteren Datensätzen. */
    private String folderAreaId;

    public String getFolderAreaId() {
        return folderAreaId;
    }

    public void setFolderAreaId(String folderAreaId) {
        this.folderAreaId = folderAreaId;
    }

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
