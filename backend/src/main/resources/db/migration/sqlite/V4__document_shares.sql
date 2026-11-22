-- Links, mit denen nicht angemeldete Personen genau ein Dokument öffnen (und, wenn erlaubt, das PDF bearbeiten).
-- Gleichbedeutend mit db/migration/mariadb/V4__document_shares.sql.
-- token_hash: SHA-256 des Links (gesucht wird nur darüber); token_encrypted: derselbe Link, verschlüsselt mit
-- secret.key, damit angemeldete Anwender ihn erneut kopieren können. expires_at NULL = unbegrenzt gültig.
CREATE TABLE document_shares (
    id              VARCHAR(64)  PRIMARY KEY,
    document_id     VARCHAR(64)  NOT NULL,
    token_hash      VARCHAR(64)  NOT NULL,
    token_encrypted TEXT         NOT NULL,
    recipient       VARCHAR(200) NOT NULL,
    can_edit        BOOLEAN      NOT NULL DEFAULT 0,
    created_at      BIGINT       NOT NULL,
    created_by      TEXT,
    expires_at      BIGINT,
    last_access_at  BIGINT,
    access_count    INTEGER      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX document_shares_token ON document_shares (token_hash);
CREATE INDEX document_shares_document ON document_shares (document_id);
