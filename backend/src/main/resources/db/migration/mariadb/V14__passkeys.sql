-- Passkeys (WebAuthn): Anmeldung ohne Passwort. Gespeichert ist nur der öffentliche Teil – der private Schlüssel
-- verlässt das Gerät bzw. den Passwortmanager nie. Gleichbedeutend mit db/migration/sqlite/V14__passkeys.sql.
CREATE TABLE passkeys (
    id              VARCHAR(64) PRIMARY KEY,
    user_id         VARCHAR(64) NOT NULL,
    -- Kennung des Schlüssels (base64url), wie sie der Browser bei der Anmeldung nennt
    credential_id   VARCHAR(512) NOT NULL UNIQUE,
    -- öffentlicher Schlüssel samt AAGUID (CBOR, base64)
    credential_data TEXT NOT NULL,
    sign_count      BIGINT NOT NULL,
    uv_initialized  BOOLEAN NOT NULL,
    backup_eligible BOOLEAN NOT NULL,
    backup_state    BOOLEAN NOT NULL,
    transports      VARCHAR(255),
    name            VARCHAR(100) NOT NULL,
    created_at      BIGINT NOT NULL,
    last_used_at    BIGINT
);
CREATE INDEX passkeys_user ON passkeys (user_id);
