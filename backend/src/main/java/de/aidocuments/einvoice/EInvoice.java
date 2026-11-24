package de.aidocuments.einvoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Die Angaben einer elektronischen Rechnung (XRechnung, ZUGFeRD/Factur-X), so wie sie in der XML-Datei stehen –
 * ohne KI, ohne Raten. Was die Rechnung nicht enthält, bleibt {@code null}.
 *
 * @param syntax       {@code CII} (UN/CEFACT Cross Industry Invoice) oder {@code UBL} (OASIS Universal Business Language)
 * @param profile      die Kennung der Spezifikation, etwa {@code urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0}
 * @param typeCode     Rechnungsart nach UNTDID 1001: 380 Rechnung, 381 Gutschrift, 384 Rechnungskorrektur …
 * @param buyerReference Käuferreferenz, bei öffentlichen Auftraggebern die Leitweg-ID
 * @param lineTotal    Summe der Positionen (vor Nachlässen und Zuschlägen auf Rechnungsebene)
 * @param allowanceTotal Nachlässe auf Rechnungsebene
 * @param chargeTotal  Zuschläge auf Rechnungsebene
 * @param netAmount    Summe ohne Umsatzsteuer
 * @param grossAmount  Summe mit Umsatzsteuer
 * @param prepaidAmount bereits gezahlt (Anzahlungen)
 * @param amountDue    der zu zahlende Betrag (nach Anzahlungen und Rundung)
 */
public record EInvoice(
        String syntax,
        String profile,
        String typeCode,
        String number,
        LocalDate issueDate,
        LocalDate dueDate,
        String currency,
        Party seller,
        Party buyer,
        String buyerReference,
        BigDecimal lineTotal,
        BigDecimal allowanceTotal,
        BigDecimal chargeTotal,
        BigDecimal netAmount,
        BigDecimal taxAmount,
        BigDecimal grossAmount,
        BigDecimal prepaidAmount,
        BigDecimal amountDue,
        String iban,
        String bic,
        String paymentReference,
        String paymentTerms,
        List<Tax> taxes,
        List<Line> lines) {

    /** Höchstens so viele Positionen werden gespeichert – die vollständige Rechnung bleibt in der XML-Datei. */
    public static final int MAX_LINES = 500;
    /** Höchstens so viele Zeilen der Steueraufschlüsselung (eine echte Rechnung hat eine Handvoll). */
    public static final int MAX_TAXES = 50;

    /** Verkäufer oder Käufer. */
    public record Party(String name, String vatId, String taxNumber, String street, String postcode, String city,
                        String country) {
    }

    /** Eine Zeile der Umsatzsteuer-Aufschlüsselung. */
    public record Tax(String category, BigDecimal rate, BigDecimal base, BigDecimal amount) {
    }

    /** Eine Rechnungsposition. */
    public record Line(String id, String name, BigDecimal quantity, String unit, BigDecimal netPrice,
                       BigDecimal netAmount, BigDecimal taxRate) {
    }

    /** Gutschriften (381, 261, 262, 396, 532) – alles andere gilt als Rechnung. */
    public boolean creditNote() {
        return typeCode != null && List.of("381", "261", "262", "296", "308", "396", "420", "458", "532")
                .contains(typeCode.strip());
    }

    /**
     * Woran sich dieselbe Rechnung wiedererkennen lässt, die auf zwei Wegen kommt (als PDF und als XML-Datei):
     * Verkäufer (Umsatzsteuer-ID, sonst Name), Rechnungsnummer und Datum. Ohne Rechnungsnummer oder Verkäufer
     * {@code null} – dann ließen sich Rechnungen verschiedener Absender nicht auseinanderhalten. Ein Schlüssel, der
     * nicht in die Spalte passt, wird als Ganzes gehasht (statt gekürzt: Sonst fielen Nummer und Datum weg).
     */
    public String identity() {
        if (number == null || number.isBlank()) {
            return null;
        }
        String issuer = seller == null ? "" : seller.vatId() != null ? seller.vatId() : nz(seller.name());
        if (issuer.isBlank()) {
            return null;
        }
        String key = String.join("|", normalize(issuer), normalize(number), issueDate == null ? "" : issueDate.toString());
        return key.length() <= 250 ? key : "sha256:" + sha256(key);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String normalize(String value) {
        return value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
