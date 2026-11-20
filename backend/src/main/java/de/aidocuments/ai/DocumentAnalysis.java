package de.aidocuments.ai;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/**
 * Strukturierte Antwort der KI. Aus dieser Klasse leitet das SDK das JSON-Schema ab;
 * die Beschreibungen sind Teil des Prompts.
 */
public record DocumentAnalysis(

        @JsonPropertyDescription("Kurzer, aussagekräftiger Titel, an dem man das Dokument in einer Liste sofort "
                + "erkennt, z. B. 'Stromrechnung März 2025' oder 'Mietvertrag Musterstraße 12'. Ohne Dateiendung.")
        String title,

        @JsonPropertyDescription("Zusammenfassung in zwei bis vier Sätzen: Worum geht es, was ist das Wesentliche, "
                + "was muss der Empfänger ggf. tun und bis wann?")
        String summary,

        @JsonPropertyDescription("Genau eine Dokumentart aus der in der Aufgabe genannten Liste.")
        String category,

        @JsonPropertyDescription("Aussteller bzw. Absender des Dokuments (Firma, Behörde oder Person), in der "
                + "gebräuchlichen Kurzform ohne Anschrift. Leerer String, wenn nicht erkennbar.")
        String correspondent,

        @JsonPropertyDescription("Datum des Dokuments (Ausstellungs- bzw. Briefdatum) im Format JJJJ-MM-TT. "
                + "Leerer String, wenn das Dokument kein Datum trägt.")
        String documentDate,

        @JsonPropertyDescription("Sprache des Dokuments als ISO-639-1-Code, z. B. 'de' oder 'en'.")
        String language,

        @JsonPropertyDescription("Drei bis sechs Schlagworte in Kleinbuchstaben, unter denen jemand das Dokument "
                + "später suchen würde. Themen, keine Wiederholung von Dokumentart oder Absender.")
        List<String> tags,

        @JsonPropertyDescription("Die wichtigsten konkreten Angaben des Dokuments als Bezeichnung-Wert-Paare, "
                + "z. B. Rechnungsnummer, Betrag, Fälligkeit, Kundennummer, Vertragslaufzeit, Aktenzeichen, IBAN. "
                + "Höchstens zehn; leere Liste, wenn es keine gibt.")
        List<Fact> keyFacts,

        @JsonPropertyDescription("Nur befüllen, wenn die Aufgabe eine Transkription verlangt: der vollständige "
                + "Text des Dokuments in Lesereihenfolge, Seiten durch eine Leerzeile getrennt. Sonst leerer String.")
        String fullText) {

    public record Fact(
            @JsonPropertyDescription("Bezeichnung, z. B. 'Rechnungsbetrag'") String label,
            @JsonPropertyDescription("Wert so, wie er im Dokument steht, z. B. '119,00 €'") String value) {
    }
}
