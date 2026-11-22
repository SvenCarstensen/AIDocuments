-- Wiederkehrende Wiedervorlagen: Ist ein Termin erledigt, entsteht der nächste so viele Monate später. NULL = einmalig.
ALTER TABLE reminders ADD COLUMN repeat_months INTEGER;

-- Verträge: Laufzeit, Kündigungsfrist und automatische Verlängerung eines Dokuments. Vor Ablauf der Kündigungsfrist
-- entsteht eine Wiedervorlage (reminder_id); läuft die Laufzeit ab, ohne dass gekündigt wurde, verlängert ein täglicher
-- Lauf sie um renewal_months und legt die nächste Wiedervorlage an.
CREATE TABLE contracts (
    document_id    VARCHAR(64) PRIMARY KEY,
    partner        VARCHAR(255),
    -- ISO-Datum (JJJJ-MM-TT)
    start_date     VARCHAR(10),
    -- Ende der laufenden Laufzeit; NULL = unbefristet (dann gilt die Kündigungsfrist ab heute)
    end_date       VARCHAR(10),
    -- Kündigungsfrist in Monaten vor dem Laufzeitende; NULL = jederzeit zum Laufzeitende
    notice_months  INTEGER,
    -- verlängert sich ohne Kündigung um so viele Monate; NULL = endet
    renewal_months INTEGER,
    amount         VARCHAR(100),
    -- so viele Tage vor dem Ende der Kündigungsfrist wird erinnert
    reminder_days  INTEGER NOT NULL,
    -- gekündigt (zum Laufzeitende): keine Verlängerung, keine Erinnerung mehr
    cancelled      BOOLEAN NOT NULL,
    reminder_id    VARCHAR(64),
    assignee_id    VARCHAR(64),
    created_by     VARCHAR(255),
    created_at     BIGINT,
    updated_at     BIGINT
);
