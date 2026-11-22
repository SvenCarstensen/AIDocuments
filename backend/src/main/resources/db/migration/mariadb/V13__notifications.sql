-- Benachrichtigungen per E-Mail: tägliche Übersicht neuer Dokumente (wer will), Hinweise an Admins (abgelaufene
-- Aufbewahrungsfristen, Postfächer, die sich wiederholt nicht abrufen lassen). Gleichbedeutend mit
-- db/migration/sqlite/V13__notifications.sql.
ALTER TABLE users ADD COLUMN notify_new_documents BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN notify_admin BOOLEAN NOT NULL DEFAULT TRUE;
-- bis wann neue Dokumente in einer Übersicht standen (Millisekunden); NULL = noch keine
ALTER TABLE users ADD COLUMN digest_until BIGINT;
-- fehlgeschlagene Abrufe in Folge und wann deswegen zuletzt benachrichtigt wurde (NULL = seit dem letzten Erfolg nicht)
ALTER TABLE mail_accounts ADD COLUMN failures INT NOT NULL DEFAULT 0;
ALTER TABLE mail_accounts ADD COLUMN failure_notified_at BIGINT;
-- über welche abgelaufene Frist schon benachrichtigt wurde – ändert sich die Frist, wird erneut benachrichtigt
CREATE TABLE retention_notices (
    document_id  VARCHAR(64) PRIMARY KEY,
    retain_until VARCHAR(10) NOT NULL,
    notified_at  BIGINT NOT NULL
);
