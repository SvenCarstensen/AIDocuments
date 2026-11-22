-- Wiedervorlage: Termine an Dokumenten, wer sich kümmert und ob es erledigt ist. Vorschläge der KI (aus den wichtigen
-- Angaben eines Dokuments) werden erst durch Bestätigen zu einem Termin; ein verworfener Vorschlag bleibt als
-- DISMISSED vermerkt, damit er nicht wiederkommt. Gleichbedeutend mit db/migration/sqlite/V10__reminders.sql.
CREATE TABLE reminders (
    id          VARCHAR(64) PRIMARY KEY,
    document_id VARCHAR(64) NOT NULL,
    -- ISO-Datum (JJJJ-MM-TT)
    due_date    VARCHAR(10) NOT NULL,
    note        VARCHAR(500),
    assignee_id VARCHAR(64),
    -- OPEN, DONE oder DISMISSED
    status      VARCHAR(16) NOT NULL,
    -- USER (von Hand) oder SUGGESTION (bestätigter oder verworfener Vorschlag)
    source      VARCHAR(16) NOT NULL,
    created_by  VARCHAR(255),
    created_at  BIGINT,
    done_at     BIGINT,
    done_by     VARCHAR(255),
    -- wann die Erinnerung per E-Mail verschickt wurde; NULL = noch nicht
    notified_at BIGINT
);
CREATE INDEX reminders_document ON reminders (document_id);
CREATE INDEX reminders_due ON reminders (status, due_date);

-- Für Erinnerungen per E-Mail: Adresse und Sprache der Mails (die zuletzt in der Oberfläche verwendete)
ALTER TABLE users ADD COLUMN email VARCHAR(255);
ALTER TABLE users ADD COLUMN locale VARCHAR(35);
