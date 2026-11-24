package de.aidocuments.einvoice;

import de.aidocuments.einvoice.EInvoice.Line;
import de.aidocuments.einvoice.EInvoice.Party;
import de.aidocuments.einvoice.EInvoice.Tax;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Liest elektronische Rechnungen: XRechnung als XML-Datei und ZUGFeRD/Factur-X als PDF mit eingebetteter XML-Datei,
 * jeweils in beiden zugelassenen Schreibweisen (CII und UBL). Es wird nur gelesen, was dasteht – nichts geschätzt.
 * Die Prüfung auf Regelkonformität (Schematron) ist nicht Aufgabe dieser Klasse.
 */
public final class EInvoiceReader {

    /** Größer ist keine Rechnung – und so bleibt eine präparierte Datei harmlos. */
    public static final int MAX_XML_BYTES = 10 * 1024 * 1024;

    /** Namen, unter denen ZUGFeRD, Factur-X und XRechnung die XML-Datei in das PDF legen (Groß-/Kleinschreibung egal). */
    private static final List<String> KNOWN_NAMES = List.of("factur-x.xml", "zugferd-invoice.xml", "xrechnung.xml",
            "zugferd_invoice.xml");

    private static final int MAX_TEXT = 1000;
    /** So viele eingebettete XML-Dateien werden höchstens gelesen … */
    private static final int MAX_EMBEDDED_FILES = 20;
    /** … zusammen höchstens so viele Bytes (entpackt) … */
    private static final long MAX_TOTAL_BYTES = 3L * MAX_XML_BYTES;
    /** … und so viele Einträge des Namensbaums angesehen. */
    private static final int MAX_ENTRIES = 1000;

    /** Die XML-Datei einer Rechnung samt ihrem Namen im PDF. */
    public record EmbeddedXml(String filename, byte[] content) {
    }

    /** Die gelesene Rechnung und die XML-Datei, aus der sie stammt. */
    public record Read(EInvoice invoice, EmbeddedXml xml) {
    }

    private EInvoiceReader() {
    }

    // ---- PDF ----

    /** Die Rechnung aus einem PDF (ZUGFeRD/Factur-X) – {@code null}, wenn keine eingebettete E-Rechnung darin ist. */
    public static Read fromPdf(PDDocument pdf) {
        Found found = find(pdf);
        return found == null ? null : found.read();
    }

    /** Wie {@link #fromPdf(PDDocument)}, für eine Datei – geladen mit den Prüfungen gegen „Zip-Bomben“. */
    public static Read fromPdf(Path file) throws IOException {
        try (PDDocument pdf = de.aidocuments.document.PdfExtractor.load(file)) {
            return fromPdf(pdf);
        }
    }

    /**
     * Nimmt die eingebettete E-Rechnung aus dem PDF (und nur sie; andere Anhänge bleiben) – für Seiten, die von einer
     * Rechnung abgetrennt werden. {@code true}, wenn eine darin war.
     */
    public static boolean removeFrom(PDDocument pdf) {
        Found found = find(pdf);
        if (found == null) {
            return false;
        }
        PDDocumentNameDictionary names = pdf.getDocumentCatalog().getNames();
        List<Entry> entries = entries(names);
        if (entries.size() >= MAX_ENTRIES) {
            return false; // nicht vollständig gelesen – neu aufbauen hieße, Anhänge zu verlieren
        }
        Map<String, PDComplexFileSpecification> rest = new java.util.TreeMap<>();
        for (Entry entry : entries) {
            if (entry.spec().getCOSObject() != found.spec().getCOSObject()) {
                rest.put(entry.key(), entry.spec());
            }
        }
        if (rest.isEmpty()) {
            names.setEmbeddedFiles(null);
        } else {
            PDEmbeddedFilesNameTreeNode tree = new PDEmbeddedFilesNameTreeNode();
            tree.setNames(rest);
            names.setEmbeddedFiles(tree);
        }
        // ZUGFeRD verweist auch im Katalog auf die Datei („Associated Files“)
        COSBase associated = pdf.getDocumentCatalog().getCOSObject().getDictionaryObject(AF);
        if (associated instanceof COSArray array) {
            for (int i = array.size() - 1; i >= 0; i--) {
                if (array.getObject(i) == found.spec().getCOSObject()) {
                    array.remove(i);
                }
            }
        }
        return true;
    }

    /**
     * Legt eine XML-Rechnung als Anhang in ein PDF (wie bei ZUGFeRD). Bestehende Anhänge bleiben, eine bereits
     * eingebettete E-Rechnung wird nicht ersetzt (dann {@code false}).
     */
    public static boolean attachTo(PDDocument pdf, byte[] xml, String filename) throws IOException {
        if (find(pdf) != null) {
            return false;
        }
        PDEmbeddedFile file = new PDEmbeddedFile(pdf, new ByteArrayInputStream(xml));
        file.setSubtype("text/xml");
        file.setSize(xml.length);
        java.util.Calendar now = java.util.GregorianCalendar.getInstance();
        file.setCreationDate(now);
        file.setModDate(now);
        PDComplexFileSpecification spec = new PDComplexFileSpecification();
        spec.setFile(filename);
        spec.setFileUnicode(filename);
        spec.setEmbeddedFile(file);
        spec.setEmbeddedFileUnicode(file);
        spec.getCOSObject().setName(COSName.getPDFName("AFRelationship"), "Alternative");

        PDDocumentNameDictionary names = pdf.getDocumentCatalog().getNames();
        if (names == null) {
            names = new PDDocumentNameDictionary(pdf.getDocumentCatalog());
        }
        List<Entry> entries = entries(names);
        if (entries.size() >= MAX_ENTRIES) {
            return false;
        }
        Map<String, PDComplexFileSpecification> all = new java.util.TreeMap<>();
        for (Entry entry : entries) {
            all.put(entry.key(), entry.spec());
        }
        String key = filename;
        for (int i = 2; all.containsKey(key); i++) {
            key = i + "-" + filename;
        }
        all.put(key, spec);
        PDEmbeddedFilesNameTreeNode tree = new PDEmbeddedFilesNameTreeNode();
        tree.setNames(all);
        names.setEmbeddedFiles(tree);
        pdf.getDocumentCatalog().setNames(names);
        COSBase associated = pdf.getDocumentCatalog().getCOSObject().getDictionaryObject(AF);
        COSArray array = associated instanceof COSArray existing ? existing : new COSArray();
        array.add(spec);
        pdf.getDocumentCatalog().getCOSObject().setItem(AF, array);
        return true;
    }

    private static final COSName AF = COSName.getPDFName("AF");

    /** Ein Eintrag im Namensbaum der eingebetteten Dateien. */
    private record Entry(String key, String name, PDComplexFileSpecification spec) {
    }

    /** Die gefundene Rechnung und der Eintrag, in dem sie liegt. */
    private record Found(Read read, PDComplexFileSpecification spec) {
    }

    /**
     * Sucht die E-Rechnung unter den eingebetteten XML-Dateien, die bekannten Rechnungsnamen zuerst. Gelesen wird Datei
     * für Datei, bis eine Rechnung gefunden ist – höchstens {@link #MAX_EMBEDDED_FILES} und zusammen höchstens
     * {@link #MAX_TOTAL_BYTES}: So bleibt auch ein präpariertes PDF mit Hunderten Anhängen harmlos.
     */
    private static Found find(PDDocument pdf) {
        List<Entry> candidates = new ArrayList<>();
        try {
            for (Entry entry : entries(pdf.getDocumentCatalog().getNames())) {
                if (entry.name().toLowerCase(Locale.ROOT).endsWith(".xml")) {
                    candidates.add(entry);
                }
            }
        } catch (RuntimeException e) {
            return null; // ein kaputter Namensbaum macht das PDF nicht unlesbar – nur ohne E-Rechnung
        }
        candidates.sort((a, b) -> Boolean.compare(!known(a.name()), !known(b.name())));
        long budget = MAX_TOTAL_BYTES;
        int read = 0;
        for (Entry candidate : candidates) {
            if (read >= MAX_EMBEDDED_FILES || budget <= 0) {
                break;
            }
            PDEmbeddedFile file = embedded(candidate.spec());
            if (file == null) {
                continue;
            }
            read++;
            int limit = (int) Math.min(MAX_XML_BYTES, budget);
            byte[] content;
            try {
                content = readLimited(file, limit);
            } catch (IOException | RuntimeException e) {
                continue;
            }
            if (content == null) {
                budget -= limit; // zu groß für eine Rechnung
                continue;
            }
            budget -= content.length;
            EInvoice invoice = parse(content);
            if (invoice != null) {
                return new Found(new Read(invoice, new EmbeddedXml(candidate.name(), content)), candidate.spec());
            }
        }
        return null;
    }

    /** Alle Einträge im Namensbaum (Dateiname aus der Dateiangabe, sonst der Schlüssel), höchstens {@link #MAX_ENTRIES}. */
    private static List<Entry> entries(PDDocumentNameDictionary names) {
        List<Entry> found = new ArrayList<>();
        if (names == null) {
            return found;
        }
        try {
            PDEmbeddedFilesNameTreeNode tree = names.getEmbeddedFiles();
            if (tree != null) {
                collect(tree, found, 0);
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return found;
    }

    private static void collect(org.apache.pdfbox.pdmodel.common.PDNameTreeNode<PDComplexFileSpecification> node,
                                List<Entry> found, int depth) throws IOException {
        if (depth > 10 || found.size() >= MAX_ENTRIES) {
            return;
        }
        Map<String, PDComplexFileSpecification> files = node.getNames();
        if (files != null) {
            for (Map.Entry<String, PDComplexFileSpecification> entry : files.entrySet()) {
                if (found.size() >= MAX_ENTRIES) {
                    return;
                }
                PDComplexFileSpecification spec = entry.getValue();
                if (spec != null && entry.getKey() != null) {
                    String name = spec.getFilename() != null ? spec.getFilename() : entry.getKey();
                    found.add(new Entry(entry.getKey(), name, spec));
                }
            }
        }
        if (node.getKids() != null) {
            for (org.apache.pdfbox.pdmodel.common.PDNameTreeNode<PDComplexFileSpecification> kid : node.getKids()) {
                collect(kid, found, depth + 1);
            }
        }
    }

    private static PDEmbeddedFile embedded(PDComplexFileSpecification spec) {
        for (PDEmbeddedFile file : java.util.Arrays.asList(spec.getEmbeddedFileUnicode(), spec.getEmbeddedFile(),
                spec.getEmbeddedFileUnix(), spec.getEmbeddedFileMac(), spec.getEmbeddedFileDos())) {
            if (file != null) {
                return file;
            }
        }
        return null;
    }

    /** Der Inhalt, wenn er höchstens {@code limit} Bytes groß ist – sonst {@code null}. */
    private static byte[] readLimited(PDEmbeddedFile file, int limit) throws IOException {
        try (InputStream in = file.createInputStream()) {
            byte[] content = in.readNBytes(limit + 1);
            return content.length > limit ? null : content;
        }
    }

    private static boolean known(String filename) {
        return filename != null && KNOWN_NAMES.contains(filename.toLowerCase(Locale.ROOT));
    }

    // ---- XML ----

    /** Eine Datei, die mit „&lt;“ beginnt (nach BOM und Leerraum) – Kandidat für eine XML-Rechnung. */
    public static boolean looksLikeXml(Path file) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(512);
        }
        int i = head.length >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF
                ? 3 : 0;
        while (i < head.length && Character.isWhitespace(head[i])) {
            i++;
        }
        return i < head.length && head[i] == '<';
    }

    /** Die Rechnung aus einer XML-Datei – {@code null}, wenn die Datei keine E-Rechnung ist. */
    public static EInvoice fromXml(Path file) throws IOException {
        if (Files.size(file) > MAX_XML_BYTES) {
            return null;
        }
        return parse(Files.readAllBytes(file));
    }

    /** Liest eine CII- oder UBL-Rechnung; {@code null}, wenn der Inhalt keine ist (oder kein wohlgeformtes XML). */
    public static EInvoice parse(byte[] xml) {
        Element root;
        try {
            root = builder().parse(new ByteArrayInputStream(xml)).getDocumentElement();
        } catch (SAXException | IOException e) {
            return null;
        }
        String ns = root.getNamespaceURI() == null ? "" : root.getNamespaceURI();
        return switch (root.getLocalName()) {
            case "CrossIndustryInvoice" -> cii(root);
            case "Invoice", "CreditNote" -> ns.startsWith("urn:oasis:names:specification:ubl:") ? ubl(root) : null;
            default -> null;
        };
    }

    /** Ohne DTD und externe Entitäten: Eine Rechnung braucht beides nicht, ein Angreifer schon (XXE). */
    private static DocumentBuilder builder() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler()); // still: kein Text auf stderr
            return builder;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- CII (ZUGFeRD, Factur-X, XRechnung in CII-Schreibweise) ----

    private static EInvoice cii(Element root) {
        Element document = child(root, "ExchangedDocument");
        Element transaction = child(root, "SupplyChainTradeTransaction");
        Element agreement = child(transaction, "ApplicableHeaderTradeAgreement");
        Element settlement = child(transaction, "ApplicableHeaderTradeSettlement");
        Element totals = child(settlement, "SpecifiedTradeSettlementHeaderMonetarySummation");
        String currency = text(settlement, "InvoiceCurrencyCode");

        Element means = null;
        for (Element candidate : children(settlement, "SpecifiedTradeSettlementPaymentMeans")) {
            if (means == null || child(candidate, "PayeePartyCreditorFinancialAccount") != null) {
                means = candidate;
                if (child(candidate, "PayeePartyCreditorFinancialAccount") != null) {
                    break;
                }
            }
        }
        LocalDate due = null;
        List<String> terms = new ArrayList<>();
        for (Element term : children(settlement, "SpecifiedTradePaymentTerms")) {
            if (due == null) {
                due = ciiDate(child(term, "DueDateDateTime"));
            }
            String description = text(term, "Description");
            if (description != null && terms.size() < 20) {
                terms.add(description);
            }
        }

        List<Tax> taxes = new ArrayList<>();
        for (Element tax : children(settlement, "ApplicableTradeTax")) {
            if (taxes.size() >= EInvoice.MAX_TAXES) {
                break;
            }
            taxes.add(new Tax(text(tax, "CategoryCode"), amount(tax, "RateApplicablePercent"),
                    amount(tax, "BasisAmount"), amount(tax, "CalculatedAmount")));
        }
        List<Line> lines = new ArrayList<>();
        for (Element item : children(transaction, "IncludedSupplyChainTradeLineItem")) {
            if (lines.size() >= EInvoice.MAX_LINES) {
                break;
            }
            Element delivery = child(item, "SpecifiedLineTradeDelivery");
            Element lineSettlement = child(item, "SpecifiedLineTradeSettlement");
            Element quantity = child(delivery, "BilledQuantity");
            lines.add(new Line(
                    text(child(item, "AssociatedDocumentLineDocument"), "LineID"),
                    text(child(item, "SpecifiedTradeProduct"), "Name"),
                    decimal(quantity),
                    attribute(quantity, "unitCode"),
                    amount(child(child(item, "SpecifiedLineTradeAgreement"), "NetPriceProductTradePrice"),
                            "ChargeAmount"),
                    amount(child(lineSettlement, "SpecifiedTradeSettlementLineMonetarySummation"), "LineTotalAmount"),
                    amount(child(lineSettlement, "ApplicableTradeTax"), "RateApplicablePercent")));
        }

        Element account = child(means, "PayeePartyCreditorFinancialAccount");
        return new EInvoice("CII",
                text(child(child(root, "ExchangedDocumentContext"), "GuidelineSpecifiedDocumentContextParameter"), "ID"),
                text(document, "TypeCode"),
                text(document, "ID"),
                ciiDate(child(document, "IssueDateTime")),
                due,
                currency,
                ciiParty(child(agreement, "SellerTradeParty")),
                ciiParty(child(agreement, "BuyerTradeParty")),
                text(agreement, "BuyerReference"),
                amount(totals, "LineTotalAmount"),
                amount(totals, "AllowanceTotalAmount"),
                amount(totals, "ChargeTotalAmount"),
                amount(totals, "TaxBasisTotalAmount"),
                amountIn(totals, "TaxTotalAmount", currency),
                amount(totals, "GrandTotalAmount"),
                amount(totals, "TotalPrepaidAmount"),
                amount(totals, "DuePayableAmount"),
                iban(account == null ? null : first(text(account, "IBANID"), text(account, "ProprietaryID"))),
                text(child(means, "PayeeSpecifiedCreditorFinancialInstitution"), "BICID"),
                text(settlement, "PaymentReference"),
                terms.isEmpty() ? null : clip(String.join("\n", terms)),
                taxes,
                lines);
    }

    private static Party ciiParty(Element party) {
        if (party == null) {
            return null;
        }
        String vatId = null;
        String taxNumber = null;
        for (Element registration : children(party, "SpecifiedTaxRegistration")) {
            Element id = child(registration, "ID");
            String scheme = attribute(id, "schemeID");
            if ("VA".equalsIgnoreCase(scheme) && vatId == null) {
                vatId = text(id);
            } else if ("FC".equalsIgnoreCase(scheme) && taxNumber == null) {
                taxNumber = text(id);
            }
        }
        Element address = child(party, "PostalTradeAddress");
        return new Party(text(party, "Name"), vatId, taxNumber, text(address, "LineOne"),
                text(address, "PostcodeCode"), text(address, "CityName"), text(address, "CountryID"));
    }

    /** Datum im CII-Format: {@code <udt:DateTimeString format="102">20261130</udt:DateTimeString>}. */
    private static LocalDate ciiDate(Element holder) {
        Element value = child(holder, "DateTimeString");
        String text = text(value);
        if (text == null) {
            return null;
        }
        String format = attribute(value, "format");
        try {
            if (format == null || "102".equals(format)) {
                return text.length() == 8 ? LocalDate.parse(text, DateTimeFormatter.BASIC_ISO_DATE) : isoDate(text);
            }
            return null; // 610 (Monat) und 616 (Woche) sind kein Tag
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // ---- UBL (XRechnung in UBL-Schreibweise, Peppol) ----

    private static EInvoice ubl(Element root) {
        boolean credit = "CreditNote".equals(root.getLocalName());
        String currency = text(root, "DocumentCurrencyCode");
        List<Element> paymentMeans = children(root, "PaymentMeans");
        Element means = null;
        for (Element candidate : paymentMeans) {
            if (means == null || child(candidate, "PayeeFinancialAccount") != null) {
                means = candidate;
                if (child(candidate, "PayeeFinancialAccount") != null) {
                    break;
                }
            }
        }
        LocalDate due = isoDate(text(root, "DueDate"));
        if (due == null) {
            for (Element candidate : paymentMeans) {
                due = isoDate(text(candidate, "PaymentDueDate"));
                if (due != null) {
                    break;
                }
            }
        }
        List<String> terms = new ArrayList<>();
        for (Element term : children(root, "PaymentTerms")) {
            String note = text(term, "Note");
            if (note != null && terms.size() < 20) {
                terms.add(note);
            }
        }

        BigDecimal taxAmount = null;
        List<Tax> taxes = new ArrayList<>();
        for (Element total : children(root, "TaxTotal")) {
            Element amount = child(total, "TaxAmount");
            String amountCurrency = attribute(amount, "currencyID");
            if (taxAmount != null || (currency != null && amountCurrency != null && !currency.equals(amountCurrency))) {
                continue; // die zweite Angabe in Buchungswährung
            }
            taxAmount = decimal(amount);
            for (Element subtotal : children(total, "TaxSubtotal")) {
                if (taxes.size() >= EInvoice.MAX_TAXES) {
                    break;
                }
                Element category = child(subtotal, "TaxCategory");
                taxes.add(new Tax(text(category, "ID"), amount(category, "Percent"), amount(subtotal, "TaxableAmount"),
                        amount(subtotal, "TaxAmount")));
            }
        }
        List<Line> lines = new ArrayList<>();
        for (Element line : children(root, credit ? "CreditNoteLine" : "InvoiceLine")) {
            if (lines.size() >= EInvoice.MAX_LINES) {
                break;
            }
            Element quantity = child(line, credit ? "CreditedQuantity" : "InvoicedQuantity");
            Element item = child(line, "Item");
            lines.add(new Line(text(line, "ID"), text(item, "Name"), decimal(quantity), attribute(quantity, "unitCode"),
                    amount(child(line, "Price"), "PriceAmount"), amount(line, "LineExtensionAmount"),
                    amount(child(item, "ClassifiedTaxCategory"), "Percent")));
        }

        Element totals = child(root, "LegalMonetaryTotal");
        Element account = child(means, "PayeeFinancialAccount");
        return new EInvoice("UBL",
                text(root, "CustomizationID"),
                first(text(root, credit ? "CreditNoteTypeCode" : "InvoiceTypeCode"), credit ? "381" : null),
                text(root, "ID"),
                isoDate(text(root, "IssueDate")),
                due,
                currency,
                ublParty(child(child(root, "AccountingSupplierParty"), "Party")),
                ublParty(child(child(root, "AccountingCustomerParty"), "Party")),
                text(root, "BuyerReference"),
                amount(totals, "LineExtensionAmount"),
                amount(totals, "AllowanceTotalAmount"),
                amount(totals, "ChargeTotalAmount"),
                amount(totals, "TaxExclusiveAmount"),
                taxAmount,
                amount(totals, "TaxInclusiveAmount"),
                amount(totals, "PrepaidAmount"),
                amount(totals, "PayableAmount"),
                iban(text(account, "ID")),
                text(child(account, "FinancialInstitutionBranch"), "ID"),
                text(means, "PaymentID"),
                terms.isEmpty() ? null : clip(String.join("\n", terms)),
                taxes,
                lines);
    }

    private static Party ublParty(Element party) {
        if (party == null) {
            return null;
        }
        String vatId = null;
        String taxNumber = null;
        for (Element scheme : children(party, "PartyTaxScheme")) {
            String kind = text(child(scheme, "TaxScheme"), "ID");
            if ("VAT".equalsIgnoreCase(kind) && vatId == null) {
                vatId = text(scheme, "CompanyID");
            } else if (!"VAT".equalsIgnoreCase(kind) && taxNumber == null) {
                taxNumber = text(scheme, "CompanyID");
            }
        }
        Element address = child(party, "PostalAddress");
        return new Party(
                first(text(child(party, "PartyLegalEntity"), "RegistrationName"),
                        text(child(party, "PartyName"), "Name")),
                vatId, taxNumber, text(address, "StreetName"), text(address, "PostalZone"),
                text(address, "CityName"), text(child(address, "Country"), "IdentificationCode"));
    }

    // ---- Hilfen: Elemente nach lokalem Namen, Präfixe und Namensräume sind gleichgültig ----

    private static Element child(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        if (parent == null) {
            return result;
        }
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && localName.equals(element.getLocalName())) {
                result.add(element);
            }
        }
        return result;
    }

    private static String text(Element parent, String localName) {
        return text(child(parent, localName));
    }

    private static String text(Element element) {
        if (element == null) {
            return null;
        }
        String value = element.getTextContent();
        return value == null || value.isBlank() ? null : clip(value.strip());
    }

    private static String attribute(Element element, String name) {
        if (element == null || !element.hasAttribute(name)) {
            return null;
        }
        String value = element.getAttribute(name).strip();
        return value.isEmpty() ? null : clip(value);
    }

    private static BigDecimal amount(Element parent, String localName) {
        return decimal(child(parent, localName));
    }

    /** Ein Betrag in der Rechnungswährung – manche Rechnungen nennen die Steuer zusätzlich in Buchungswährung. */
    private static BigDecimal amountIn(Element parent, String localName, String currency) {
        BigDecimal fallback = null;
        for (Element element : children(parent, localName)) {
            String elementCurrency = attribute(element, "currencyID");
            if (currency != null && currency.equals(elementCurrency)) {
                return decimal(element);
            }
            if (fallback == null && (elementCurrency == null || currency == null)) {
                fallback = decimal(element);
            }
        }
        return fallback;
    }

    private static BigDecimal decimal(Element element) {
        String text = text(element);
        if (text == null || text.length() > 40) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate isoDate(String text) {
        if (text == null) {
            return null;
        }
        try {
            return LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** IBAN ohne Leerzeichen, in Großbuchstaben – nur, wenn sie danach aussieht. */
    private static String iban(String value) {
        if (value == null) {
            return null;
        }
        String compact = value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        return compact.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]{8,30}") ? compact : value;
    }

    private static String first(String a, String b) {
        return a != null ? a : b;
    }

    private static String clip(String value) {
        return value.length() <= MAX_TEXT ? value : value.substring(0, MAX_TEXT - 1) + "…";
    }
}
