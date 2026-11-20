package de.aidocuments.document;

public enum DocumentSource {
    /** Anhang aus einem IMAP-Postfach */
    MAIL,
    /** Datei aus dem überwachten Eingangsordner */
    FOLDER,
    /** In der Oberfläche hochgeladen */
    UPLOAD
}
