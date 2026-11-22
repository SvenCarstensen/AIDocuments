-- Filter je Postfach: nur E-Mails, deren Betreff und Absender passen, werden ausgewertet.
-- Gleichbedeutend mit db/migration/mariadb/V5__mail_filter.sql. Je Spalte ein Begriff pro Zeile; NULL = keine Einschränkung.
ALTER TABLE mail_accounts ADD COLUMN subject_include TEXT;
ALTER TABLE mail_accounts ADD COLUMN subject_exclude TEXT;
ALTER TABLE mail_accounts ADD COLUMN sender_include TEXT;
ALTER TABLE mail_accounts ADD COLUMN sender_exclude TEXT;
