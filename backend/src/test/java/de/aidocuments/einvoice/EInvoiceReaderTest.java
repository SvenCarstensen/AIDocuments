package de.aidocuments.einvoice;

import static org.assertj.core.api.Assertions.assertThat;

import de.aidocuments.einvoice.EInvoice.Line;
import de.aidocuments.einvoice.EInvoice.Party;
import de.aidocuments.einvoice.EInvoice.Tax;
import de.aidocuments.i18n.TestMessages;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** E-Rechnungen: exakt das, was in der XML-Datei steht – in beiden Schreibweisen, als XML und im PDF. */
class EInvoiceReaderTest {

    @TempDir
    Path dir;

    @Test
    void readsAnXRechnungInCiiSyntaxExactly() throws IOException {
        EInvoice invoice = EInvoiceReader.parse(resource("xrechnung-cii.xml"));

        assertThat(invoice).isNotNull();
        assertThat(invoice.syntax()).isEqualTo("CII");
        assertThat(invoice.profile()).endsWith("xrechnung_3.0");
        assertThat(invoice.typeCode()).isEqualTo("380");
        assertThat(invoice.creditNote()).isFalse();
        assertThat(invoice.number()).isEqualTo("RE-2026-004711");
        assertThat(invoice.issueDate()).isEqualTo(LocalDate.of(2026, 11, 15));
        assertThat(invoice.dueDate()).isEqualTo(LocalDate.of(2026, 12, 15));
        assertThat(invoice.currency()).isEqualTo("EUR");
        assertThat(invoice.seller()).isEqualTo(new Party("Stadtwerke Musterstadt GmbH", "DE123456789",
                "201/113/40209", "Am Kraftwerk 1", "12345", "Musterstadt", "DE"));
        assertThat(invoice.buyer().name()).isEqualTo("Erika Mustermann");
        assertThat(invoice.buyerReference()).isEqualTo("04011000-12345-34");
        // Beträge als Dezimalzahl, wie geschrieben – nicht als Gleitkommazahl
        assertThat(invoice.netAmount()).isEqualTo(new BigDecimal("166.20"));
        assertThat(invoice.taxAmount()).isEqualTo(new BigDecimal("28.58")); // nicht die Angabe in CHF
        assertThat(invoice.grossAmount()).isEqualTo(new BigDecimal("194.78"));
        assertThat(invoice.amountDue()).isEqualTo(new BigDecimal("144.78")); // nach der Anzahlung
        assertThat(invoice.iban()).isEqualTo("DE02120300000000202051");
        assertThat(invoice.bic()).isEqualTo("BYLADEM1001");
        assertThat(invoice.paymentReference()).isEqualTo("RE-2026-004711");
        assertThat(invoice.paymentTerms()).isEqualTo("Zahlbar bis 15.12.2026 ohne Abzug.");
        assertThat(invoice.taxes()).containsExactly(
                new Tax("S", new BigDecimal("19.00"), new BigDecimal("141.20"), new BigDecimal("26.83")),
                new Tax("S", new BigDecimal("7.00"), new BigDecimal("25.00"), new BigDecimal("1.75")));
        assertThat(invoice.lines()).hasSize(3);
        assertThat(invoice.lines().getFirst()).isEqualTo(new Line("1", "Arbeitspreis Strom 01.10.–31.10.2026",
                new BigDecimal("412.5"), "KWH", new BigDecimal("0.3120"), new BigDecimal("128.70"),
                new BigDecimal("19")));
        assertThat(invoice.lines().get(2).name()).isEqualTo("Ratgeber „Energie sparen im Haushalt“");
        assertThat(invoice.identity()).isEqualTo("de123456789|re-2026-004711|2026-11-15");
    }

    @Test
    void readsAnXRechnungInUblSyntaxExactly() throws IOException {
        EInvoice invoice = EInvoiceReader.parse(resource("xrechnung-ubl.xml"));

        assertThat(invoice).isNotNull();
        assertThat(invoice.syntax()).isEqualTo("UBL");
        assertThat(invoice.number()).isEqualTo("2026-0815");
        assertThat(invoice.issueDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(invoice.dueDate()).isEqualTo(LocalDate.of(2026, 12, 4));
        // der eingetragene Name der Firma geht dem Handelsnamen vor
        assertThat(invoice.seller()).isEqualTo(new Party("Kontorbedarf Schmidt e.K.", "DE987654321", "20/123/45678",
                "Hafenstraße 12", "24103", "Kiel", "DE"));
        assertThat(invoice.buyer().name()).isEqualTo("Stadt Flensburg");
        assertThat(invoice.buyerReference()).isEqualTo("991-12345-67");
        assertThat(invoice.netAmount()).isEqualTo(new BigDecimal("207.90"));
        assertThat(invoice.taxAmount()).isEqualTo(new BigDecimal("39.50")); // nicht die Angabe in USD
        assertThat(invoice.grossAmount()).isEqualTo(new BigDecimal("247.40"));
        assertThat(invoice.amountDue()).isEqualTo(new BigDecimal("247.40"));
        assertThat(invoice.iban()).isEqualTo("DE75512108001245126199");
        assertThat(invoice.bic()).isEqualTo("SOGEDEFFXXX");
        assertThat(invoice.paymentReference()).isEqualTo("2026-0815");
        assertThat(invoice.paymentTerms()).isEqualTo("14 Tage netto");
        assertThat(invoice.taxes()).containsExactly(new Tax("S", new BigDecimal("19"), new BigDecimal("207.90"),
                new BigDecimal("39.50")));
        assertThat(invoice.lines()).extracting(Line::name, Line::quantity, Line::netAmount).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Druckerpapier A4, 500 Blatt", new BigDecimal("10"),
                        new BigDecimal("49.90")),
                org.assertj.core.groups.Tuple.tuple("Toner schwarz", new BigDecimal("2"), new BigDecimal("158.00")));
    }

    @Test
    void creditNotesAreRecognized() throws IOException {
        EInvoice credit = EInvoiceReader.parse(resource("gutschrift-ubl.xml"));

        assertThat(credit.creditNote()).isTrue();
        assertThat(credit.typeCode()).isEqualTo("381");
        assertThat(credit.number()).isEqualTo("GS-2026-12");
        assertThat(credit.dueDate()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(credit.seller().name()).isEqualTo("Kontorbedarf Schmidt e.K.");
        assertThat(credit.seller().vatId()).isNull();
        assertThat(credit.identity()).isEqualTo("kontorbedarf schmidt e.k.|gs-2026-12|2026-11-21");
        assertThat(credit.lines()).singleElement().extracting(Line::quantity).isEqualTo(new BigDecimal("1"));
    }

    @Test
    void otherXmlIsNoInvoiceAndExternalEntitiesAreNeverRead() throws IOException {
        Path secret = Files.writeString(dir.resolve("geheim.txt"), "GEHEIM");
        for (String xml : List.of(
                "<?xml version=\"1.0\"?><Invoice><ID>1</ID></Invoice>", // ohne UBL-Namensraum
                "<html><body>Rechnung</body></html>",
                "<rsm:CrossIndustryInvoice xmlns:rsm=\"x\"><nicht geschlossen>",
                "kein XML",
                "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"" + secret.toUri() + "\">]>"
                        + "<rsm:CrossIndustryInvoice xmlns:rsm=\"urn:x\"><rsm:ExchangedDocument><ram:ID xmlns:ram=\"urn:y\">"
                        + "&x;</ram:ID></rsm:ExchangedDocument></rsm:CrossIndustryInvoice>")) {
            assertThat(EInvoiceReader.parse(xml.getBytes(StandardCharsets.UTF_8))).as(xml).isNull();
        }
        // eine Rechnung, die kaum etwas enthält, ist trotzdem eine – nur eben mit Lücken
        EInvoice minimal = EInvoiceReader.parse(("<rsm:CrossIndustryInvoice xmlns:rsm=\"urn:un:unece:uncefact:data:"
                + "standard:CrossIndustryInvoice:100\"/>").getBytes(StandardCharsets.UTF_8));
        assertThat(minimal).isNotNull();
        assertThat(minimal.number()).isNull();
        assertThat(minimal.identity()).isNull();
        assertThat(minimal.lines()).isEmpty();
    }

    @Test
    void looksLikeXmlAcceptsByteOrderMarkAndLeadingSpace() throws IOException {
        Path withBom = dir.resolve("a.xml");
        Files.write(withBom, concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, "\n  <x/>".getBytes()));
        assertThat(EInvoiceReader.looksLikeXml(withBom)).isTrue();
        Path text = Files.writeString(dir.resolve("b.txt"), "Rechnung");
        assertThat(EInvoiceReader.looksLikeXml(text)).isFalse();
        Path empty = Files.write(dir.resolve("c.xml"), new byte[0]);
        assertThat(EInvoiceReader.looksLikeXml(empty)).isFalse();
    }

    @Test
    void zugferdPdfIsFoundEvenNextToOtherAttachmentsInANestedNameTree() throws IOException {
        byte[] invoiceXml = resource("xrechnung-cii.xml");
        Path pdf = dir.resolve("zugferd.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream out = new PDPageContentStream(doc, page)) {
                out.beginText();
                out.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                out.newLineAtOffset(50, 700);
                out.showText("Rechnung RE-2026-004711");
                out.endText();
            }
            PDEmbeddedFilesNameTreeNode first = new PDEmbeddedFilesNameTreeNode();
            first.setNames(Map.of("anlage.xml", spec(doc, "anlage.xml", "<anlage>Leistungsnachweis</anlage>".getBytes())));
            PDEmbeddedFilesNameTreeNode second = new PDEmbeddedFilesNameTreeNode();
            second.setNames(Map.of("factur-x.xml", spec(doc, "factur-x.xml", invoiceXml)));
            PDEmbeddedFilesNameTreeNode root = new PDEmbeddedFilesNameTreeNode();
            root.setKids(List.of(first, second));
            PDDocumentNameDictionary names = new PDDocumentNameDictionary(doc.getDocumentCatalog());
            names.setEmbeddedFiles(root);
            doc.getDocumentCatalog().setNames(names);
            doc.save(pdf.toFile());
        }

        EInvoiceReader.Read read = EInvoiceReader.fromPdf(pdf);
        assertThat(read).isNotNull();
        assertThat(read.xml().filename()).isEqualTo("factur-x.xml");
        assertThat(read.xml().content()).isEqualTo(invoiceXml);
        assertThat(read.invoice()).isEqualTo(EInvoiceReader.parse(invoiceXml));

        Path plain = dir.resolve("plain.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(plain.toFile());
        }
        assertThat(EInvoiceReader.fromPdf(plain)).isNull();
    }

    @Test
    void xmlInvoiceBecomesAReadablePdfThatCarriesTheUnchangedXml() throws IOException {
        byte[] xml = resource("xrechnung-cii.xml");
        EInvoice invoice = EInvoiceReader.parse(xml);
        Path pdf = dir.resolve("rechnung.pdf");

        new EInvoicePdf(TestMessages.create()).render(invoice, xml, "RE-2026-004711.xml", Locale.GERMAN, pdf);

        EInvoiceReader.Read read = EInvoiceReader.fromPdf(pdf);
        assertThat(read.xml().filename()).isEqualTo("RE-2026-004711.xml");
        assertThat(read.xml().content()).isEqualTo(xml); // Byte für Byte das Original
        assertThat(read.invoice()).isEqualTo(invoice);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Rechnung RE-2026-004711", "Stadtwerke Musterstadt GmbH", "USt-IdNr. DE123456789",
                    "Erika Mustermann", "15.11.2026", "15.12.2026", "Arbeitspreis Strom", "412,5 kWh",
                    "Ratgeber „Energie sparen im Haushalt“", "166,20 €", "Umsatzsteuer 19 %", "26,83 €",
                    "Umsatzsteuer 7 %", "194,78 €", "Zu zahlen", "144,78 €", "DE02120300000000202051",
                    "Zahlbar bis 15.12.2026 ohne Abzug.", "RE-2026-004711.xml");
            assertThat(doc.getDocumentInformation().getTitle()).isEqualTo("Rechnung RE-2026-004711");
        }

        // englisch, und eine Gutschrift heißt so
        Path credit = dir.resolve("credit.pdf");
        byte[] creditXml = resource("gutschrift-ubl.xml");
        new EInvoicePdf(TestMessages.create()).render(EInvoiceReader.parse(creditXml), creditXml, "gs.xml",
                Locale.ENGLISH, credit);
        try (PDDocument doc = Loader.loadPDF(credit.toFile())) {
            assertThat(new PDFTextStripper().getText(doc)).contains("Credit note GS-2026-12", "21 Nov 2026",
                    "Rücknahme Toner schwarz (beschädigt)", "€94.01");
        }
    }

    @Test
    void manyLinesFlowOntoFurtherPagesAndUnknownCharactersDoNotBreakThePdf() throws IOException {
        String line = """
                <ram:IncludedSupplyChainTradeLineItem>
                  <ram:SpecifiedTradeProduct><ram:Name>Position %d – Ελληνικά 漢字 ✓ Łódź</ram:Name></ram:SpecifiedTradeProduct>
                  <ram:SpecifiedLineTradeDelivery><ram:BilledQuantity unitCode="HUR">1</ram:BilledQuantity></ram:SpecifiedLineTradeDelivery>
                </ram:IncludedSupplyChainTradeLineItem>""";
        StringBuilder lines = new StringBuilder();
        for (int i = 1; i <= 120; i++) {
            lines.append(line.formatted(i));
        }
        String xml = new String(resource("xrechnung-cii.xml"), StandardCharsets.UTF_8)
                .replaceFirst("<rsm:SupplyChainTradeTransaction>", "<rsm:SupplyChainTradeTransaction>" + lines);
        EInvoice invoice = EInvoiceReader.parse(xml.getBytes(StandardCharsets.UTF_8));
        assertThat(invoice.lines()).hasSize(123);
        Path pdf = dir.resolve("lang.pdf");
        new EInvoicePdf(TestMessages.create()).render(invoice, xml.getBytes(StandardCharsets.UTF_8), "lang.xml",
                Locale.GERMAN, pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(2);
            // Unbekanntes wird zu „?“, Buchstaben mit Zeichen zu ihrem Grundbuchstaben
            assertThat(new PDFTextStripper().getText(doc)).contains("Position 120 – ???????? ?? ? Lódz", "194,78 €");
        }
        // Beschriftungen in einer Schrift, die die Standardschrift nicht kennt, gibt es auf Englisch
        new EInvoicePdf(TestMessages.create()).render(invoice, xml.getBytes(StandardCharsets.UTF_8), "lang.xml",
                Locale.forLanguageTag("ru"), pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            assertThat(new PDFTextStripper().getText(doc)).startsWith("Invoice RE-2026-004711");
        }
    }

    // --- Hilfen ---

    private static PDComplexFileSpecification spec(PDDocument doc, String name, byte[] content) throws IOException {
        PDEmbeddedFile file = new PDEmbeddedFile(doc, new ByteArrayInputStream(content));
        file.setSubtype("text/xml");
        PDComplexFileSpecification spec = new PDComplexFileSpecification();
        spec.setFile(name);
        spec.setEmbeddedFile(file);
        return spec;
    }

    static byte[] resource(String name) throws IOException {
        try (InputStream in = EInvoiceReaderTest.class.getResourceAsStream("/einvoice/" + name)) {
            return in.readAllBytes();
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
