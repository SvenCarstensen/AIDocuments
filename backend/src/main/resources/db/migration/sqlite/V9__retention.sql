-- Aufbewahrungsfristen: Regeln je Kategorie (Jahre, gezählt ab dem Ende des Jahres des Dokumentdatums) und das
-- daraus folgende Datum, bis zu dem ein Dokument nicht gelöscht werden kann. Das Datum wird nie früher – auch nicht,
-- wenn sich Kategorie oder Regel ändern. Gleichbedeutend mit db/migration/mariadb/V9__retention.sql.
CREATE TABLE retention_rules (
    id         VARCHAR(64) PRIMARY KEY,
    category   VARCHAR(100) NOT NULL,
    years      INT NOT NULL,
    created_at BIGINT
);
-- ISO-Datum (JJJJ-MM-TT), damit Vergleiche als Text stimmen; NULL = keine Frist
ALTER TABLE documents ADD COLUMN retain_until VARCHAR(10);
ALTER TABLE deleted_documents ADD COLUMN retain_until VARCHAR(10);
CREATE INDEX documents_retain_until ON documents (retain_until);
