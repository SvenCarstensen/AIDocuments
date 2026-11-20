package de.aidocuments.document;

public enum DocumentStatus {
    /** Gespeichert, wartet auf die Verarbeitung. */
    PENDING,
    /** PDF wird gelesen bzw. von der KI analysiert. */
    PROCESSING,
    /** Vollständig von der KI erschlossen. */
    INDEXED,
    /** Nur der PDF-Text ist durchsuchbar – keine KI konfiguriert. */
    TEXT_ONLY,
    /** Verarbeitung fehlgeschlagen, Details in errorMessage. */
    FAILED
}
