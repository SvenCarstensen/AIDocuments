-- E-Rechnungen (ZUGFeRD/Factur-X, XRechnung): die exakt ausgelesenen Angaben als JSON und ein Schlüssel aus
-- Verkäufer, Rechnungsnummer und Datum, an dem dieselbe Rechnung auf zweitem Weg erkannt wird.
-- Gleichbedeutend mit db/migration/sqlite/V15__e_invoices.sql.
ALTER TABLE documents ADD COLUMN e_invoice MEDIUMTEXT;
ALTER TABLE documents ADD COLUMN e_invoice_key VARCHAR(255);
ALTER TABLE deleted_documents ADD COLUMN e_invoice MEDIUMTEXT;
ALTER TABLE deleted_documents ADD COLUMN e_invoice_key VARCHAR(255);
CREATE INDEX documents_e_invoice_key ON documents (e_invoice_key);
