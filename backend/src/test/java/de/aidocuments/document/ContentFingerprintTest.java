package de.aidocuments.document;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Dasselbe Schreiben zweimal gescannt ist ein Duplikat – der Brief vom nächsten Monat nicht. */
class ContentFingerprintTest {

    private static String invoice(String month, String date, String amount) {
        return """
                Stadtwerke Musterhausen GmbH, Lindenallee 12, 12345 Musterhausen
                Rechnung für den Monat %s über Ihren Stromverbrauch im Tarif Musterstrom Plus
                Rechnungsdatum: %s
                Sehr geehrte Kundin, sehr geehrter Kunde, für den Abrechnungszeitraum stellen wir Ihnen den folgenden
                Betrag in Rechnung. Der Betrag wird wie vereinbart von Ihrem Konto abgebucht. Bei Fragen erreichen Sie
                unseren Kundenservice montags bis freitags von acht bis achtzehn Uhr unter der bekannten Rufnummer.
                Gesamtbetrag: %s EUR
                Mit freundlichen Grüßen, Ihre Stadtwerke Musterhausen
                """.formatted(month, date, amount);
    }

    @Test
    void theSameLetterScannedTwiceIsADuplicateDespiteRecognitionErrors() {
        ContentFingerprint scan = ContentFingerprint.of(invoice("März", "14.03.2026", "54,29"));
        ContentFingerprint rescan = ContentFingerprint.of(invoice("März", "14.03.2026", "54,29")
                .replace("Kundenservice", "Kundenserv1ce").replace("abgebucht", "abgcbucht"));

        assertThat(scan.sameDocumentAs(rescan)).isTrue();
        assertThat(scan.dates()).containsExactly("14.03.2026");
        assertThat(scan.amounts()).containsExactly("54,29");
    }

    @Test
    void theNextLetterOfASeriesIsNoDuplicate() {
        ContentFingerprint march = ContentFingerprint.of(invoice("März", "14.03.2026", "54,29"));
        ContentFingerprint april = ContentFingerprint.of(invoice("April", "14.04.2026", "61,10"));

        assertThat(march.textSimilarity(april)).isGreaterThan(0.75);
        assertThat(march.sameDocumentAs(april)).isFalse();
    }

    @Test
    void shortTextsAreNotCompared() {
        assertThat(ContentFingerprint.of("Nur ein paar Wörter")).isNull();
        assertThat(ContentFingerprint.of(null)).isNull();
    }
}
