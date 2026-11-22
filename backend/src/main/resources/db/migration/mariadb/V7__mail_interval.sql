-- Eigenes Abrufintervall je Postfach in Minuten (1–1440). NULL = das allgemeine Intervall aus „Abruf &
-- Eingangsordner“. Gleichbedeutend mit db/migration/sqlite/V7__mail_interval.sql.
ALTER TABLE mail_accounts ADD COLUMN interval_minutes INT NULL;
