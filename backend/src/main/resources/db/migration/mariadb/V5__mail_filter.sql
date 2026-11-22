-- Filter je Postfach: nur E-Mails, deren Betreff und Absender passen, werden ausgewertet.
-- Gleichbedeutend mit db/migration/sqlite/V5__mail_filter.sql. Je Spalte ein Begriff pro Zeile; NULL = keine Einschränkung.
ALTER TABLE mail_accounts
    ADD COLUMN subject_include TEXT NULL,
    ADD COLUMN subject_exclude TEXT NULL,
    ADD COLUMN sender_include  TEXT NULL,
    ADD COLUMN sender_exclude  TEXT NULL;
