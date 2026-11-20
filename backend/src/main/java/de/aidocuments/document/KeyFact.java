package de.aidocuments.document;

/** Ein von der KI herausgezogenes Faktum, z. B. "Rechnungsbetrag" → "119,00 €". */
public record KeyFact(String label, String value) {
}
