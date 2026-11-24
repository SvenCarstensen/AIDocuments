package de.aidocuments.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Vorprüfung gegen „Zip-Bomben“, Seitenzahl und Zeitgrenze beim Lesen eines PDFs. */
public class PdfPrescanTest {

    @TempDir
    Path dir;

    @Test
    void anOrdinaryPdfPasses() throws Exception {
        Path file = write(text(3, "Rechnung Nr. 4711 über 128,40 EUR"));
        assertThatCode(() -> PdfPrescan.check(file)).doesNotThrowAnyException();
        PdfExtractor.PdfContent content = new PdfExtractor().extract(file, dir.resolve("vorschau.jpg"));
        assertThat(content.pageCount()).isEqualTo(3);
        assertThat(content.text()).contains("Rechnung Nr. 4711");
    }

    @Test
    void aStreamThatInflatesTooFarIsRejectedBeforePdfBoxLoadsIt() throws Exception {
        Path file = write(flateBomb(80));
        assertThat(Files.size(file)).isLessThan(1024 * 1024);
        assertThatThrownBy(() -> PdfPrescan.check(file)).isInstanceOf(PdfExtractor.OversizedStreamException.class);
        assertThatThrownBy(() -> PdfExtractor.load(file)).isInstanceOf(PdfExtractor.OversizedStreamException.class);
        // auch beim Lesen einer E-Rechnung aus der Datei
        assertThatThrownBy(() -> de.aidocuments.einvoice.EInvoiceReader.fromPdf(file))
                .isInstanceOf(PdfExtractor.OversizedStreamException.class);
        // wie ein anderes unlesbares PDF: abgelehnt mit „beschädigt“
        assertThatThrownBy(() -> new PdfExtractor().extract(file, dir.resolve("vorschau.jpg")))
                .isInstanceOfSatisfying(PdfExtractor.PdfReadException.class,
                        e -> assertThat(e.msg().key()).isEqualTo("document.error.corrupt"));
    }

    @Test
    void chainedFiltersAreFollowedAndJudgedStrictly() throws Exception {
        // zweimal FlateDecode: die erste Stufe ist klein, erst die zweite wird riesig
        byte[] twice = deflate(deflate(zeros(80)));
        assertThat(twice.length).isLessThan(64 * 1024);
        assertThatThrownBy(() -> PdfPrescan.check(write(rawPdf(
                "<< /Length " + twice.length + " /Filter [/FlateDecode /FlateDecode] >>", twice))))
                .isInstanceOf(PdfExtractor.OversizedStreamException.class);
        // erst ASCIIHex, dann FlateDecode
        byte[] hex = (HexFormat.of().formatHex(deflate(zeros(80))) + ">").getBytes(StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> PdfPrescan.check(write(rawPdf(
                "<< /Length " + hex.length + " /Filter [/AHx /Fl] >>", hex))))
                .isInstanceOf(PdfExtractor.OversizedStreamException.class);
        // als Bild gekennzeichnet, aber mit zwei Filtern: die strenge Grenze
        assertThatThrownBy(() -> PdfPrescan.check(write(rawPdf("<< /Type /XObject /Subtype /Image /Width 10 /Height 10"
                + " /Length " + twice.length + " /Filter [/FlateDecode /FlateDecode] >>", twice))))
                .isInstanceOf(PdfExtractor.OversizedStreamException.class);
    }

    @Test
    void hiddenOrEscapedFiltersDoNotFoolTheCheck() throws Exception {
        byte[] bomb = deflate(zeros(80));
        // #xx in Namen
        assertThatThrownBy(() -> PdfPrescan.check(write(rawPdf(
                "<< /Length " + bomb.length + " /Filter /Fl#61teDecode >>", bomb))))
                .isInstanceOf(PdfExtractor.OversizedStreamException.class);
        // /Filter weit vor „stream“ (außer Sichtweite der Suche) oder als indirektes Objekt
        assertThatThrownBy(() -> PdfPrescan.check(write(rawPdf("<< /Filter /FlateDecode /Pad (" + " ".repeat(10_000)
                + ") /Length " + bomb.length + " >>", bomb))))
                .isInstanceOf(PdfExtractor.OversizedStreamException.class);
        assertThatThrownBy(() -> PdfPrescan.check(write(rawPdf(
                "<< /Length " + bomb.length + " /Filter 9 0 R >>", bomb))))
                .isInstanceOf(PdfExtractor.OversizedStreamException.class);
        // rohes Deflate ohne zlib-Kopf
        byte[] raw = deflate(zeros(80), true);
        assertThatThrownBy(() -> PdfPrescan.check(write(rawPdf(
                "<< /Length " + raw.length + " /Filter /FlateDecode >>", raw))))
                .isInstanceOf(PdfExtractor.OversizedStreamException.class);
    }

    @Test
    void largeImagesWithASingleFilterAreAllowed() throws Exception {
        // ein Bild darf größer sein als ein anderer Datenstrom (eine Seite in hoher Auflösung)
        byte[] image = deflate(zeros(70));
        assertThatCode(() -> PdfPrescan.check(write(rawPdf("<< /Type /XObject /Subtype /Image /Width 5000"
                + " /Height 4900 /BitsPerComponent 8 /ColorSpace /DeviceRGB /Length " + image.length
                + " /Filter /FlateDecode >>", image)))).doesNotThrowAnyException();
    }

    @Test
    void pdfsWithMoreThanTheAllowedPagesAreRejected() throws Exception {
        Path file;
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < DocumentPages.MAX_PAGES + 1; i++) {
                document.addPage(new PDPage());
            }
            file = dir.resolve("viele-seiten.pdf");
            document.save(file.toFile());
        }
        assertThatThrownBy(() -> new PdfExtractor().extract(file, dir.resolve("vorschau.jpg")))
                .isInstanceOfSatisfying(PdfExtractor.PdfReadException.class, e -> {
                    assertThat(e.msg().key()).isEqualTo("error.pages.tooMany");
                    assertThat(e.msg()).isEqualTo(de.aidocuments.i18n.Msg.of("error.pages.tooMany",
                            DocumentPages.MAX_PAGES));
                });
    }

    @Test
    void readingTheTextStopsWhenTheTimeIsUp() throws Exception {
        Path file = write(text(5, "Seite mit Text"));
        assertThatThrownBy(() -> new PdfExtractor(Duration.ZERO).extract(file, dir.resolve("vorschau.jpg")))
                .isInstanceOfSatisfying(PdfExtractor.PdfReadException.class, e -> {
                    assertThat(e.msg().key()).isEqualTo("document.error.corrupt");
                    assertThat(e.getCause()).isInstanceOf(PdfExtractor.TextTimeoutException.class);
                });
        // mit genug Zeit geht es
        assertThat(new PdfExtractor(Duration.ofMinutes(1)).extract(file, dir.resolve("vorschau.jpg")).text())
                .contains("Seite mit Text");
    }

    // ---------------------------------------------------------------------------------------------------------

    /** Ein PDF mit einem Datenstrom aus {@code megabytes} MB Nullen, mit FlateDecode gepackt (wenige KB). */
    public static byte[] flateBomb(int megabytes) throws IOException {
        byte[] data = deflate(zeros(megabytes));
        return rawPdf("<< /Length " + data.length + " /Filter /FlateDecode >>", data);
    }

    /** Ein minimales PDF, dessen Seite den angegebenen Datenstrom als Inhalt hat. */
    static byte[] rawPdf(String dictionary, byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("%PDF-1.7\n"
                + "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
                + "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n"
                + "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>\nendobj\n"
                + "4 0 obj\n" + dictionary + "\nstream\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(data);
        out.write(("\nendstream\nendobj\ntrailer\n<< /Root 1 0 R /Size 5 >>\n%%EOF\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }

    private static Zeros zeros(int megabytes) {
        return new Zeros(megabytes);
    }

    private record Zeros(int megabytes) {
    }

    private static byte[] deflate(Zeros zeros) throws IOException {
        return deflate(zeros, false);
    }

    private static byte[] deflate(Zeros zeros, boolean nowrap) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, nowrap);
        try (DeflaterOutputStream zip = new DeflaterOutputStream(out, deflater)) {
            byte[] block = new byte[1024 * 1024];
            for (int i = 0; i < zeros.megabytes(); i++) {
                zip.write(block);
            }
        } finally {
            deflater.end();
        }
        return out.toByteArray();
    }

    private static byte[] deflate(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream zip = new DeflaterOutputStream(out)) {
            zip.write(data);
        }
        return out.toByteArray();
    }

    private static byte[] text(int pages, String line) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(60, 720);
                    content.showText(line);
                    content.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private Path write(byte[] content) throws IOException {
        Path file = Files.createTempFile(dir, "probe", ".pdf");
        Files.write(file, content);
        return file;
    }
}
