-- Rechte je Bereich: lesen (READ), bearbeiten (EDIT) oder zusätzlich löschen (DELETE) – je Mitglied und für alle,
-- wenn der Bereich für alle freigegeben ist. Bisher durften alle bearbeiten und nur Administratoren löschen; EDIT als
-- Vorgabe ändert daran nichts. Administratoren dürfen weiterhin alles.
ALTER TABLE areas ADD COLUMN everyone_access VARCHAR(10) NOT NULL DEFAULT 'EDIT';
ALTER TABLE area_members ADD COLUMN access VARCHAR(10) NOT NULL DEFAULT 'EDIT';
