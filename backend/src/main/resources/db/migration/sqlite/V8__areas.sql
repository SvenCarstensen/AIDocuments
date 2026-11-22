-- Bereiche: Jedes Dokument liegt in genau einem Bereich; Benutzer sehen die Bereiche, in denen sie Mitglied sind,
-- und die für alle freigegebenen. Administratoren sehen alles. Gleichbedeutend mit db/migration/mariadb/V8__areas.sql.
CREATE TABLE areas (
    id         VARCHAR(64) PRIMARY KEY,
    -- NULL nur beim vorgegebenen Bereich „general“: Die Oberfläche zeigt dann den übersetzten Namen
    name       VARCHAR(120),
    everyone   BOOLEAN NOT NULL DEFAULT 0,
    created_at BIGINT
);
CREATE TABLE area_members (
    area_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (area_id, user_id)
);
CREATE INDEX area_members_user ON area_members (user_id);

-- Alles Bisherige kommt in den allgemeinen Bereich, den alle sehen – es ändert sich also zunächst nichts.
INSERT INTO areas (id, name, everyone, created_at) VALUES ('general', NULL, 1, 0);
ALTER TABLE documents ADD COLUMN area_id VARCHAR(64) NOT NULL DEFAULT 'general';
ALTER TABLE deleted_documents ADD COLUMN area_id VARCHAR(64) NOT NULL DEFAULT 'general';
ALTER TABLE history ADD COLUMN area_id VARCHAR(64) NOT NULL DEFAULT 'general';
ALTER TABLE mail_accounts ADD COLUMN area_id VARCHAR(64) NOT NULL DEFAULT 'general';
CREATE INDEX documents_area_id ON documents (area_id);
CREATE INDEX history_area_id ON history (area_id, occurred_at);
