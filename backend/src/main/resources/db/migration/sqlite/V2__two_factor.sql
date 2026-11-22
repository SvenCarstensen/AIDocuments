-- Zwei-Faktor-Anmeldung (TOTP, Authenticator-App). Gleichbedeutend mit db/migration/mariadb/V2__two_factor.sql.
-- totp_secret: Schlüssel, verschlüsselt mit secret.key; gesetzt = Zwei-Faktor-Anmeldung an.
-- totp_last_step: zuletzt verwendetes 30-Sekunden-Intervall – jeder Code gilt nur einmal.
-- recovery_codes: SHA-256 der noch unbenutzten Wiederherstellungscodes als JSON-Liste.
ALTER TABLE users ADD COLUMN totp_secret TEXT;
ALTER TABLE users ADD COLUMN totp_last_step BIGINT;
ALTER TABLE users ADD COLUMN recovery_codes TEXT;
