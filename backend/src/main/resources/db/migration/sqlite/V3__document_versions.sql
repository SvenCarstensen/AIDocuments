-- Fassungen der PDF-Datei eines Dokuments. Gleichbedeutend mit db/migration/mariadb/V3__document_versions.sql.
-- Jede Änderung am PDF (bearbeitet, Original oder ältere Fassung wiederhergestellt, rückgängig gemacht) ergibt eine
-- neue Fassung; keine wird überschrieben. storage_path: Datei der Fassung im Datenverzeichnis; NULL = die aktuelle
-- Fassung, also die Datei des Dokuments selbst. Gibt es zu einem Dokument keine Zeile, hat es nur seine erste Fassung.
CREATE TABLE document_versions (
    id            VARCHAR(64) PRIMARY KEY,
    document_id   VARCHAR(64) NOT NULL,
    version_no    INTEGER     NOT NULL,
    storage_path  TEXT,
    size_bytes    BIGINT,
    page_count    INTEGER,
    kind          VARCHAR(32) NOT NULL,
    restored_from INTEGER,
    created_at    BIGINT,
    created_by    TEXT
);
CREATE UNIQUE INDEX document_versions_number ON document_versions (document_id, version_no);
