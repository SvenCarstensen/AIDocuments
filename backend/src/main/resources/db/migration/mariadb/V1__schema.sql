-- Schema für eine externe MariaDB. Gleichbedeutend mit db/migration/sqlite/V1__schema.sql –
-- Änderungen immer in beiden Fassungen als neue Version (V2__…) anlegen.
-- utf8mb4_bin: Texte werden genau verglichen und sortiert, wie in der eingebauten SQLite-Datenbank.
-- Zeitpunkte als Millisekunden seit 1970 (UTC), Listen und verschachtelte Werte als JSON-Text.

CREATE TABLE documents (
    id                    VARCHAR(64) PRIMARY KEY,
    original_filename     TEXT,
    storage_path          TEXT,
    sha256                VARCHAR(64) NOT NULL,
    size_bytes            BIGINT      NOT NULL,
    page_count            INTEGER,
    source                VARCHAR(32),
    status                VARCHAR(32),
    error_message         TEXT,
    title                 TEXT,
    summary               TEXT,
    category              TEXT,
    correspondent         TEXT,
    document_date         VARCHAR(10),
    language              TEXT,
    ocr_used              BOOLEAN     NOT NULL DEFAULT 0,
    has_thumbnail         BOOLEAN     NOT NULL DEFAULT 0,
    mail_account_id       VARCHAR(64),
    mail_subject          TEXT,
    mail_from             TEXT,
    mail_received_at      BIGINT,
    created_at            BIGINT,
    processed_at          BIGINT,
    original_storage_path TEXT,
    modified_at           BIGINT,
    thumbnail_at          BIGINT,
    deleted_at            BIGINT,
    fingerprint           TEXT,
    duplicate_of          VARCHAR(64),
    not_duplicate_of      TEXT,
    tags                  TEXT,
    key_facts             TEXT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;
CREATE UNIQUE INDEX documents_sha256 ON documents (sha256);
CREATE INDEX documents_status ON documents (status);
CREATE INDEX documents_created_at ON documents (created_at);
CREATE INDEX documents_processed_at ON documents (processed_at);
CREATE INDEX documents_duplicate_of ON documents (duplicate_of);
CREATE INDEX documents_mail_account_id ON documents (mail_account_id);

-- Papierkorb: gelöschte Dokumente mit allen Angaben, 30 Tage lang wiederherstellbar
CREATE TABLE deleted_documents (
    id                    VARCHAR(64) PRIMARY KEY,
    original_filename     TEXT,
    storage_path          TEXT,
    sha256                VARCHAR(64) NOT NULL,
    size_bytes            BIGINT      NOT NULL,
    page_count            INTEGER,
    source                VARCHAR(32),
    status                VARCHAR(32),
    error_message         TEXT,
    title                 TEXT,
    summary               TEXT,
    category              TEXT,
    correspondent         TEXT,
    document_date         VARCHAR(10),
    language              TEXT,
    ocr_used              BOOLEAN     NOT NULL DEFAULT 0,
    has_thumbnail         BOOLEAN     NOT NULL DEFAULT 0,
    mail_account_id       VARCHAR(64),
    mail_subject          TEXT,
    mail_from             TEXT,
    mail_received_at      BIGINT,
    created_at            BIGINT,
    processed_at          BIGINT,
    original_storage_path TEXT,
    modified_at           BIGINT,
    thumbnail_at          BIGINT,
    deleted_at            BIGINT,
    fingerprint           TEXT,
    duplicate_of          VARCHAR(64),
    not_duplicate_of      TEXT,
    tags                  TEXT,
    key_facts             TEXT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;
CREATE INDEX deleted_documents_deleted_at ON deleted_documents (deleted_at);

-- Volltext getrennt, damit Listenabfragen ihn nicht mitladen
CREATE TABLE document_texts (
    document_id VARCHAR(64) PRIMARY KEY,
    content     LONGTEXT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

CREATE TABLE history (
    id              VARCHAR(64) PRIMARY KEY,
    occurred_at     BIGINT      NOT NULL,
    action          VARCHAR(16) NOT NULL,
    document_id     VARCHAR(64),
    document_title  TEXT,
    source          VARCHAR(32),
    origin          TEXT,
    change_type     VARCHAR(32),
    user_name       TEXT,
    before_details  TEXT,
    previous_file   TEXT,
    undone_at       BIGINT,
    undone_by       TEXT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;
CREATE INDEX history_occurred_at ON history (occurred_at);
CREATE INDEX history_document_id ON history (document_id, occurred_at);

CREATE TABLE users (
    id            VARCHAR(64)  PRIMARY KEY,
    username      VARCHAR(255) NOT NULL,
    password_hash TEXT,
    role          VARCHAR(16),
    enabled       BOOLEAN      NOT NULL DEFAULT 1,
    created_at    BIGINT,
    last_login_at BIGINT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;
CREATE UNIQUE INDEX users_username ON users (username);

CREATE TABLE mail_accounts (
    id                 VARCHAR(64) PRIMARY KEY,
    name               TEXT,
    host               TEXT,
    port               INTEGER     NOT NULL DEFAULT 0,
    security           VARCHAR(16),
    username           TEXT,
    password_encrypted TEXT,
    folder             TEXT,
    import_since       VARCHAR(10),
    enabled            BOOLEAN     NOT NULL DEFAULT 0,
    uid_validity       BIGINT,
    last_uid           BIGINT,
    last_sync_at       BIGINT,
    last_sync_status   VARCHAR(16),
    last_sync_message  TEXT,
    created_at         BIGINT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

-- Schon geprüfte Dateien des Eingangsordners; Schlüssel ist der SHA-256 des absoluten Pfads
CREATE TABLE imported_files (
    path_hash   CHAR(64) PRIMARY KEY,
    path        TEXT     NOT NULL,
    size        BIGINT   NOT NULL,
    modified_at BIGINT   NOT NULL,
    outcome     VARCHAR(32),
    checked_at  BIGINT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

-- In der Oberfläche gespeicherte Einstellungen, je Bereich ein Datensatz ("ai", "import") als JSON
CREATE TABLE settings (
    id   VARCHAR(64) PRIMARY KEY,
    data TEXT NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;
