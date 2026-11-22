-- Zeitmarke je Postfach: Beginn des letzten erfolgreichen Abrufs (Epoch-Millisekunden). E-Mails, die davor empfangen
-- wurden, werden nicht mehr ausgewertet – auch nicht, wenn der UID-Stand verloren geht. Gleichbedeutend mit
-- db/migration/sqlite/V6__mail_checked_until.sql. NULL = noch kein erfolgreicher Abruf.
ALTER TABLE mail_accounts ADD COLUMN checked_until BIGINT NULL;
