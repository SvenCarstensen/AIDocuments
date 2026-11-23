package de.aidocuments.einvoice;

import de.aidocuments.einvoice.EInvoice.Line;
import de.aidocuments.einvoice.EInvoice.Party;
import de.aidocuments.einvoice.EInvoice.Tax;
import de.aidocuments.i18n.Messages;
import de.aidocuments.i18n.Msg;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Currency;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

/**
 * Macht aus einer XML-Rechnung (XRechnung) ein lesbares PDF – zum Ansehen, Drucken und für die Vorschau. Die
 * XML-Datei wird unverändert in das PDF eingebettet (wie bei ZUGFeRD); sie bleibt das maßgebliche Original und lässt
 * sich jederzeit wieder herausholen.
 */
@Component
public class EInvoicePdf {

    private static final float MARGIN = 50;
    private static final float WIDTH = PDRectangle.A4.getWidth() - 2 * MARGIN;
    private static final float BOTTOM = 60;

    private final Messages messages;

    public EInvoicePdf(Messages messages) {
        this.messages = messages;
    }

    /**
     * @param xmlName der Dateiname, unter dem die XML-Datei im PDF liegt
     * @param locale  Sprache der Beschriftungen und Schreibweise von Datum und Beträgen
     */
    public void render(EInvoice invoice, byte[] xml, String xmlName, Locale requested, Path target) throws IOException {
        Locale locale = writable(requested) ? requested : Locale.ENGLISH;
        try (PDDocument pdf = new PDDocument()) {
            new Writer(pdf, invoice, locale).write(xmlName);
            embed(pdf, xml, xmlName);
            PDDocumentInformation info = pdf.getDocumentInformation();
            info.setTitle(heading(invoice, locale));
            info.setAuthor(invoice.seller() == null ? null : invoice.seller().name());
            info.setProducer("AIDocuments");
            info.setCreationDate(GregorianCalendar.getInstance());
            pdf.save(target.toFile());
        }
    }

    /** Beschriftungen, die die Standardschrift (westeuropäische Zeichen) nicht schreiben kann, gibt es auf Englisch. */
    private boolean writable(Locale locale) {
        PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        for (String key : List.of("einvoice.pdf.invoice", "einvoice.pdf.creditNote", "einvoice.pdf.seller",
                "einvoice.pdf.buyer", "einvoice.pdf.issueDate", "einvoice.pdf.dueDate", "einvoice.pdf.buyerReference",
                "einvoice.pdf.vatId", "einvoice.pdf.taxNumber", "einvoice.pdf.position", "einvoice.pdf.description",
                "einvoice.pdf.quantity", "einvoice.pdf.unitPrice", "einvoice.pdf.amount", "einvoice.pdf.net",
                "einvoice.pdf.tax", "einvoice.pdf.total", "einvoice.pdf.due", "einvoice.pdf.payment",
                "einvoice.pdf.reference", "einvoice.pdf.terms", "einvoice.pdf.unit.day", "einvoice.pdf.unit.month",
                "einvoice.pdf.unit.lumpSum", "einvoice.pdf.note")) {
            try {
                bold.encode(label(locale, key, "", "").replaceAll("[\\t\\r\\n\\u00a0\\u202f]", " "));
            } catch (IllegalArgumentException | IOException e) {
                return false;
            }
        }
        return true;
    }

    private String heading(EInvoice invoice, Locale locale) {
        return label(locale, invoice.creditNote() ? "einvoice.pdf.creditNote" : "einvoice.pdf.invoice",
                invoice.number() == null ? "" : invoice.number()).strip();
    }

    private String label(Locale locale, String key, Object... args) {
        return messages.render(Msg.of(key, args), locale);
    }

    private static void embed(PDDocument pdf, byte[] xml, String xmlName) throws IOException {
        PDEmbeddedFile file = new PDEmbeddedFile(pdf, new ByteArrayInputStream(xml));
        file.setSubtype("text/xml");
        file.setSize(xml.length);
        Calendar now = GregorianCalendar.getInstance();
        file.setCreationDate(now);
        file.setModDate(now);
        PDComplexFileSpecification spec = new PDComplexFileSpecification();
        spec.setFile(xmlName);
        spec.setFileUnicode(xmlName);
        spec.setEmbeddedFile(file);
        spec.setEmbeddedFileUnicode(file);
        spec.getCOSObject().setName(org.apache.pdfbox.cos.COSName.getPDFName("AFRelationship"), "Source");
        PDEmbeddedFilesNameTreeNode tree = new PDEmbeddedFilesNameTreeNode();
        tree.setNames(Map.of(xmlName, spec));
        PDDocumentNameDictionary names = new PDDocumentNameDictionary(pdf.getDocumentCatalog());
        names.setEmbeddedFiles(tree);
        pdf.getDocumentCatalog().setNames(names);
    }

    /** Schreibt Seite für Seite von oben nach unten. */
    private final class Writer {

        private final PDDocument pdf;
        private final EInvoice invoice;
        private final Locale locale;
        private final Locale format;
        private final PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        private final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        private final Map<Character, Boolean> encodable = new HashMap<>();
        private PDPageContentStream out;
        private float y;

        Writer(PDDocument pdf, EInvoice invoice, Locale locale) {
            this.pdf = pdf;
            this.invoice = invoice;
            this.locale = locale;
            this.format = Messages.formatLocale(locale);
        }

        void write(String xmlName) throws IOException {
            newPage();
            try {
                text(heading(invoice, locale), bold, 18, MARGIN, y);
                y -= 34;

                float top = y;
                party(label(locale, "einvoice.pdf.seller"), invoice.seller(), MARGIN, 250);
                float leftEnd = y;
                y = top;
                List<String[]> facts = new ArrayList<>();
                facts.add(new String[] {label(locale, "einvoice.pdf.issueDate"), date(invoice.issueDate())});
                facts.add(new String[] {label(locale, "einvoice.pdf.dueDate"), date(invoice.dueDate())});
                facts.add(new String[] {label(locale, "einvoice.pdf.buyerReference"), invoice.buyerReference()});
                for (String[] fact : facts) {
                    if (fact[1] != null) {
                        text(fact[0], bold, 9, 330, y);
                        text(fact[1], regular, 9, 445, y);
                        y -= 13;
                    }
                }
                y = Math.min(leftEnd, y) - 14;
                party(label(locale, "einvoice.pdf.buyer"), invoice.buyer(), MARGIN, 250);
                y -= 14;

                lines();
                totals();
                payment();
                note(label(locale, "einvoice.pdf.note", xmlName, invoice.syntax()
                        + (invoice.profile() == null ? "" : ", " + invoice.profile())));
            } finally {
                out.close();
            }
        }

        private void party(String title, Party party, float x, float width) throws IOException {
            text(title, bold, 9, x, y);
            y -= 13;
            if (party == null) {
                return;
            }
            List<String> rows = new ArrayList<>();
            rows.add(party.name());
            rows.add(party.street());
            rows.add(join(" ", party.postcode(), party.city()));
            rows.add(party.country());
            if (party.vatId() != null) {
                rows.add(label(locale, "einvoice.pdf.vatId") + " " + party.vatId());
            }
            if (party.taxNumber() != null) {
                rows.add(label(locale, "einvoice.pdf.taxNumber") + " " + party.taxNumber());
            }
            for (String row : rows) {
                if (row != null && !row.isBlank()) {
                    for (String part : wrap(row, regular, 9, width)) {
                        text(part, regular, 9, x, y);
                        y -= 12;
                    }
                }
            }
        }

        private void lines() throws IOException {
            if (invoice.lines() == null || invoice.lines().isEmpty()) {
                return;
            }
            float[] right = {MARGIN + 22, 0, MARGIN + 340, MARGIN + 420, MARGIN + WIDTH};
            header(right);
            int position = 0;
            for (Line line : invoice.lines()) {
                position++;
                List<String> name = wrap(line.name() == null ? "" : line.name(), regular, 9, 230);
                if (y - (name.size() * 12 + 4) < BOTTOM) {
                    newPage();
                    header(right);
                }
                textRight(line.id() != null ? line.id() : String.valueOf(position), regular, 9, right[0], y);
                for (int i = 0; i < name.size(); i++) {
                    text(name.get(i), regular, 9, MARGIN + 32, y - i * 12);
                }
                textRight(join(" ", number(line.quantity()), unit(line.unit())), regular, 9, right[2], y);
                textRight(money(line.netPrice()), regular, 9, right[3], y);
                textRight(money(line.netAmount()), regular, 9, right[4], y);
                y -= name.size() * 12 + 4;
            }
            rule();
            y -= 16;
        }

        private void header(float[] right) throws IOException {
            ensure(30);
            textRight(label(locale, "einvoice.pdf.position"), bold, 9, right[0], y);
            text(label(locale, "einvoice.pdf.description"), bold, 9, MARGIN + 32, y);
            textRight(label(locale, "einvoice.pdf.quantity"), bold, 9, right[2], y);
            textRight(label(locale, "einvoice.pdf.unitPrice"), bold, 9, right[3], y);
            textRight(label(locale, "einvoice.pdf.amount"), bold, 9, right[4], y);
            y -= 5;
            rule();
            y -= 12;
        }

        private void totals() throws IOException {
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[] {label(locale, "einvoice.pdf.net"), money(invoice.netAmount())});
            for (Tax tax : invoice.taxes() == null ? List.<Tax>of() : invoice.taxes()) {
                rows.add(new String[] {label(locale, "einvoice.pdf.tax", percent(tax.rate())), money(tax.amount())});
            }
            if (invoice.taxes() == null || invoice.taxes().isEmpty()) {
                rows.add(new String[] {label(locale, "einvoice.pdf.tax", ""), money(invoice.taxAmount())});
            }
            rows.add(new String[] {label(locale, "einvoice.pdf.total"), money(invoice.grossAmount())});
            boolean differs = invoice.amountDue() != null && (invoice.grossAmount() == null
                    || invoice.amountDue().compareTo(invoice.grossAmount()) != 0);
            if (differs) {
                rows.add(new String[] {label(locale, "einvoice.pdf.due"), money(invoice.amountDue())});
            }
            ensure(rows.size() * 14 + 10);
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i)[1] == null) {
                    continue;
                }
                boolean emphasis = i == rows.size() - 1;
                text(rows.get(i)[0].strip(), emphasis ? bold : regular, 9.5f, MARGIN + 270, y);
                textRight(rows.get(i)[1], emphasis ? bold : regular, 9.5f, MARGIN + WIDTH, y);
                y -= 14;
            }
            y -= 12;
        }

        private void payment() throws IOException {
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[] {"IBAN", invoice.iban()});
            rows.add(new String[] {"BIC", invoice.bic()});
            rows.add(new String[] {label(locale, "einvoice.pdf.reference"), invoice.paymentReference()});
            rows.add(new String[] {label(locale, "einvoice.pdf.terms"), invoice.paymentTerms()});
            if (rows.stream().allMatch(row -> row[1] == null)) {
                return;
            }
            ensure(40);
            text(label(locale, "einvoice.pdf.payment"), bold, 9, MARGIN, y);
            y -= 13;
            for (String[] row : rows) {
                if (row[1] == null) {
                    continue;
                }
                List<String> value = new ArrayList<>();
                for (String paragraph : row[1].split("\n")) {
                    value.addAll(wrap(paragraph, regular, 9, WIDTH - 120));
                }
                ensure(value.size() * 12);
                text(row[0], bold, 9, MARGIN, y);
                for (String part : value) {
                    text(part, regular, 9, MARGIN + 120, y);
                    y -= 12;
                }
            }
        }

        private void note(String note) throws IOException {
            List<String> parts = wrap(note, regular, 7.5f, WIDTH);
            ensure(parts.size() * 10 + 20);
            y -= 14;
            for (String part : parts) {
                out.setNonStrokingColor(0.4f, 0.4f, 0.4f);
                text(part, regular, 7.5f, MARGIN, y);
                out.setNonStrokingColor(0f, 0f, 0f);
                y -= 10;
            }
        }

        // ---- Seiten und Text ----

        private void ensure(float height) throws IOException {
            if (y - height < BOTTOM) {
                newPage();
            }
        }

        private void newPage() throws IOException {
            if (out != null) {
                out.close();
            }
            PDPage page = new PDPage(PDRectangle.A4);
            pdf.addPage(page);
            out = new PDPageContentStream(pdf, page);
            y = PDRectangle.A4.getHeight() - MARGIN - 10;
        }

        private void rule() throws IOException {
            out.setLineWidth(0.5f);
            out.moveTo(MARGIN, y);
            out.lineTo(MARGIN + WIDTH, y);
            out.stroke();
        }

        private void text(String value, PDType1Font font, float size, float x, float baseline) throws IOException {
            if (value == null || value.isEmpty()) {
                return;
            }
            out.beginText();
            out.setFont(font, size);
            out.newLineAtOffset(x, baseline);
            out.showText(safe(value, font));
            out.endText();
        }

        private void textRight(String value, PDType1Font font, float size, float right, float baseline)
                throws IOException {
            if (value == null || value.isEmpty()) {
                return;
            }
            text(value, font, size, right - width(safe(value, font), font, size), baseline);
        }

        private float width(String value, PDType1Font font, float size) throws IOException {
            return font.getStringWidth(value) / 1000 * size;
        }

        /** Zeilenumbruch an Leerzeichen; überlange Wörter werden hart getrennt. */
        private List<String> wrap(String value, PDType1Font font, float size, float max) throws IOException {
            List<String> result = new ArrayList<>();
            StringBuilder lineText = new StringBuilder();
            for (String word : safe(value, font).split(" ")) {
                String candidate = lineText.isEmpty() ? word : lineText + " " + word;
                if (width(candidate, font, size) <= max) {
                    lineText.setLength(0);
                    lineText.append(candidate);
                    continue;
                }
                if (!lineText.isEmpty()) {
                    result.add(lineText.toString());
                    lineText.setLength(0);
                }
                while (width(word, font, size) > max && word.length() > 1) {
                    int cut = word.length() - 1;
                    while (cut > 1 && width(word.substring(0, cut), font, size) > max) {
                        cut--;
                    }
                    result.add(word.substring(0, cut));
                    word = word.substring(cut);
                }
                lineText.append(word);
            }
            if (!lineText.isEmpty() || result.isEmpty()) {
                result.add(lineText.toString());
            }
            return result;
        }

        /** Die Standardschrift kennt nur westeuropäische Zeichen (WinAnsi); andere werden zu „?“. */
        private String safe(String value, PDType1Font font) {
            StringBuilder result = new StringBuilder(value.length());
            for (char c : value.replaceAll("[\\t\\r\\n\\u00a0\\u202f]", " ").toCharArray()) {
                boolean ok = encodable.computeIfAbsent(c, key -> {
                    try {
                        font.encode(String.valueOf(key));
                        return true;
                    } catch (IllegalArgumentException | IOException e) {
                        return false;
                    }
                });
                result.append(ok ? c : fallback(c, font));
            }
            return result.toString();
        }

        /** Ein Zeichen außerhalb der Standardschrift: der Grundbuchstabe (ł → l, ś → s), sonst „?“. */
        private char fallback(char c, PDType1Font font) {
            char base = switch (c) {
                case 'Ł' -> 'L';
                case 'ł' -> 'l';
                case 'Đ' -> 'D';
                case 'đ' -> 'd';
                case 'ı' -> 'i';
                case 'Ħ' -> 'H';
                case 'ħ' -> 'h';
                default -> {
                    String decomposed = java.text.Normalizer.normalize(String.valueOf(c), java.text.Normalizer.Form.NFD);
                    yield decomposed.length() > 1 && decomposed.charAt(0) < 0x250 ? decomposed.charAt(0) : '?';
                }
            };
            if (base == '?') {
                return '?';
            }
            return encodable.computeIfAbsent(base, key -> {
                try {
                    font.encode(String.valueOf(key));
                    return true;
                } catch (IllegalArgumentException | IOException e) {
                    return false;
                }
            }) ? base : '?';
        }

        // ---- Schreibweisen ----

        private String date(LocalDate date) {
            return date == null ? null : DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(format)
                    .format(date);
        }

        private String money(BigDecimal amount) {
            if (amount == null) {
                return null;
            }
            try {
                NumberFormat currency = NumberFormat.getCurrencyInstance(format);
                Currency code = Currency.getInstance(invoice.currency());
                currency.setCurrency(code);
                currency.setMinimumFractionDigits(Math.max(code.getDefaultFractionDigits(), Math.min(amount.scale(), 4)));
                currency.setMaximumFractionDigits(Math.max(code.getDefaultFractionDigits(), Math.min(amount.scale(), 4)));
                return currency.format(amount);
            } catch (IllegalArgumentException | NullPointerException e) {
                return join(" ", number(amount), invoice.currency());
            }
        }

        private String number(BigDecimal value) {
            if (value == null) {
                return null;
            }
            NumberFormat number = NumberFormat.getNumberInstance(format);
            number.setMaximumFractionDigits(Math.max(0, Math.min(value.stripTrailingZeros().scale(), 6)));
            return number.format(value);
        }

        private String percent(BigDecimal rate) {
            if (rate == null) {
                return "";
            }
            NumberFormat percent = NumberFormat.getPercentInstance(format);
            percent.setMaximumFractionDigits(2);
            return percent.format(rate.movePointLeft(2));
        }

        /** Häufige Einheiten (UN/ECE Rec. 20) lesbar; sonst der Code. */
        private String unit(String code) {
            if (code == null) {
                return null;
            }
            return switch (code) {
                case "C62", "H87", "EA", "XPP" -> null;
                case "HUR" -> "h";
                case "DAY" -> label(locale, "einvoice.pdf.unit.day");
                case "MON" -> label(locale, "einvoice.pdf.unit.month");
                case "KGM" -> "kg";
                case "MTR" -> "m";
                case "MTK" -> "m²";
                case "LTR" -> "l";
                case "KWH" -> "kWh";
                case "LS" -> label(locale, "einvoice.pdf.unit.lumpSum");
                default -> code;
            };
        }

        private static String join(String separator, String a, String b) {
            if (a == null || a.isBlank()) {
                return b;
            }
            return b == null || b.isBlank() ? a : a + separator + b;
        }
    }
}
