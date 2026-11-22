package de.aidocuments.document;

import de.aidocuments.i18n.Localizable;
import de.aidocuments.i18n.Msg;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.filter.FilterFactory;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Liest Seitenzahl und Textebene eines PDFs und rendert ein Vorschaubild der ersten Seite. */
@Component
public class PdfExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfExtractor.class);
    private static final float THUMBNAIL_WIDTH_PX = 560f;

    public record PdfContent(int pageCount, String text, boolean thumbnailCreated) {

        /** Scans haben keine oder nur eine bruchstückhafte Textebene. */
        public boolean textIsSparse() {
            return text.strip().length() < Math.max(1, pageCount) * 60L;
        }
    }

    /** Das PDF lässt sich nicht lesen; die Meldung steht später – übersetzt – am Dokument. */
    public static class PdfReadException extends Exception implements Localizable {

        private final Msg msg;

        public PdfReadException(Msg msg, Throwable cause) {
            super(msg.toString(), cause);
            this.msg = msg;
        }

        @Override
        public Msg msg() {
            return msg;
        }
    }

    public PdfContent extract(Path file, Path thumbnailTarget) throws PdfReadException {
        try (PDDocument pdf = load(file)) {
            int pages = pdf.getNumberOfPages();
            String text = extractText(pdf);
            boolean thumbnail = pages > 0 && renderThumbnail(pdf, thumbnailTarget);
            return new PdfContent(pages, text, thumbnail);
        } catch (InvalidPasswordException e) {
            throw new PdfReadException(Msg.of("document.error.passwordProtected"), e);
        } catch (IOException e) {
            throw new PdfReadException(Msg.of("document.error.corrupt", Msg.detailOf(e)), e);
        }
    }

    /** Erzeugt nur das Vorschaubild (erste Seite) – etwa um ein missratenes zu ersetzen. */
    public boolean renderThumbnail(Path file, Path thumbnailTarget) {
        try (PDDocument pdf = load(file)) {
            return pdf.getNumberOfPages() > 0 && renderThumbnail(pdf, thumbnailTarget);
        } catch (IOException | RuntimeException e) {
            log.warn("Vorschaubild konnte nicht erzeugt werden: {}", e.toString());
            return false;
        }
    }

    /**
     * Rendert die ersten Seiten als JPEG – für KI-Server, die kein PDF annehmen, aber Bilder lesen können.
     */
    public List<byte[]> renderPagesAsJpeg(Path file, int maxPages, float widthPx) throws IOException {
        List<byte[]> images = new ArrayList<>();
        try (PDDocument pdf = load(file)) {
            PDFRenderer renderer = new PDFRenderer(pdf);
            int pages = Math.min(maxPages, pdf.getNumberOfPages());
            for (int page = 0; page < pages; page++) {
                float pageWidth = pdf.getPage(page).getCropBox().getWidth();
                float scale = pageWidth > 0 ? Math.min(widthPx / pageWidth, 4f) : 1f;
                BufferedImage image = renderer.renderImage(page, safeScale(pdf.getPage(page), scale), ImageType.RGB);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(image, "jpg", out);
                images.add(out.toByteArray());
            }
        }
        return images;
    }

    /** Rendert Seiten einzeln für die Texterkennung – so liegt nie mehr als eine Seite im Speicher. */
    public interface PageImages extends AutoCloseable {

        int pageCount();

        /** Die Seite als Graustufen-PNG – verlustfrei, Kompressionsartefakte stören die Texterkennung. */
        byte[] png(int page) throws IOException;

        @Override
        void close() throws IOException;
    }

    public PageImages openPageImages(Path file, float dpi) throws IOException {
        PDDocument pdf = load(file);
        PDFRenderer renderer = new PDFRenderer(pdf);
        return new PageImages() {
            @Override
            public int pageCount() {
                return pdf.getNumberOfPages();
            }

            @Override
            public byte[] png(int page) throws IOException {
                BufferedImage image = renderer.renderImage(page, safeScale(pdf.getPage(page), dpi / 72f),
                        ImageType.GRAY);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(image, "png", out);
                return out.toByteArray();
            }

            @Override
            public void close() throws IOException {
                pdf.close();
            }
        };
    }

    private static String extractText(PDDocument pdf) {
        try {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String raw = stripper.getText(pdf);
            return raw.replace("\u0000", "")
                    .replaceAll("[ \\t\\x0B\\f]+", " ")
                    .replaceAll(" ?\\R ?", "\n")
                    .replaceAll("\\n{3,}", "\n\n")
                    .strip();
        } catch (IOException | RuntimeException e) {
            log.warn("Textebene konnte nicht gelesen werden: {}", e.toString());
            return "";
        }
    }

    private static boolean renderThumbnail(PDDocument pdf, Path target) {
        try {
            float pageWidth = pdf.getPage(0).getCropBox().getWidth();
            float scale = pageWidth > 0 ? THUMBNAIL_WIDTH_PX / pageWidth : 1f;
            BufferedImage image = new PDFRenderer(pdf).renderImage(0, safeScale(pdf.getPage(0), Math.min(scale, 4f)),
                    ImageType.RGB);
            Files.createDirectories(target.getParent());
            return ImageIO.write(image, "jpg", target.toFile());
        } catch (IOException | RuntimeException e) {
            // ein fehlendes Vorschaubild soll die Verarbeitung nicht aufhalten
            log.warn("Vorschaubild konnte nicht erzeugt werden: {}", e.toString());
            return false;
        }
    }

    /** Höchstens so viele Bildpunkte je gerenderter Seite (≈ DIN A4 bei 450 dpi, rund 100 MB im Speicher). */
    static final long MAX_RENDER_PIXELS = 25_000_000L;

    /**
     * Verkleinert den Maßstab, falls die Seite sonst ein riesiges Bild ergäbe. Eine PDF-Seite darf laut Format über
     * fünf Meter groß sein; ein präpariertes PDF – etwa als E-Mail-Anhang – brächte
     * sonst den Server mit einem OutOfMemoryError zum Stehen.
     */
    static float safeScale(PDPage page, float scale) {
        PDRectangle box = page.getCropBox();
        int rotation = page.getRotation();
        double width = Math.abs(rotation % 180 == 0 ? box.getWidth() : box.getHeight());
        double height = Math.abs(rotation % 180 == 0 ? box.getHeight() : box.getWidth());
        double pixels = width * scale * height * scale;
        if (!(pixels > MAX_RENDER_PIXELS)) {
            return scale;
        }
        return (float) (scale * Math.sqrt(MAX_RENDER_PIXELS / pixels));
    }

    /** Arbeitsspeicher für entpackte Datenströme je PDF; was darüber hinausgeht, landet in einer temporären Datei. */
    private static final long STREAM_CACHE_MEMORY = 64L * 1024 * 1024;

    /** Lädt ein PDF so, dass stark komprimierte Inhalte („Zip-Bomben“) nicht den ganzen Arbeitsspeicher belegen. */
    private static PDDocument load(Path file) throws IOException {
        PDDocument pdf = Loader.loadPDF(file.toFile(), MemoryUsageSetting.setupMixed(STREAM_CACHE_MEMORY).streamCache);
        try {
            checkStreams(pdf);
            return pdf;
        } catch (IOException | RuntimeException e) {
            pdf.close();
            throw e;
        }
    }

    /** Größter entpackter Datenstrom (ohne Bilder), der verarbeitet wird. */
    static final long MAX_DECODED_STREAM_BYTES = 64L * 1024 * 1024;
    /** Längstes zusammenhängendes „Wort“ in einem Datenstrom – PDFBox liest jedes in einem Stück in den Speicher. */
    static final int MAX_TOKEN_BYTES = 1024 * 1024;

    /** Ein Datenstrom überschreitet die Grenzen – das PDF wird abgelehnt. */
    static final class OversizedStreamException extends IOException {
        OversizedStreamException(String message) {
            super(message);
        }
    }

    /**
     * Prüft vor der Verarbeitung alle Datenströme außer Bildern. Ein präpariertes PDF („Zip-Bombe“: wenige MB, entpackt
     * Gigabytes – etwa als E-Mail-Anhang) wird so abgelehnt, bevor PDFBox es im Arbeitsspeicher entpackt. Die Dekoder
     * von PDFBox schreiben dazu in einen Strom, der an der Grenze abbricht; {@code COSStream.createInputStream} würde
     * selbst schon alles in den Speicher entpacken.
     */
    static void checkStreams(PDDocument pdf) throws IOException {
        COSDocument document = pdf.getDocument();
        for (COSObjectKey key : new ArrayList<>(document.getXrefTable().keySet())) {
            COSObject object = document.getObjectFromPool(key);
            if (!(object.getObject() instanceof COSStream stream) || COSName.IMAGE.equals(stream.getCOSName(COSName.SUBTYPE))) {
                continue;
            }
            try {
                checkStream(stream);
            } catch (OversizedStreamException e) {
                throw e;
            } catch (java.io.UncheckedIOException e) {
                if (e.getCause() instanceof OversizedStreamException oversized) {
                    throw oversized;
                }
            } catch (IOException | RuntimeException e) {
                // ein beschädigter einzelner Strom ist kein Grund zur Ablehnung – PDFBox überspringt ihn ohnehin
            }
        }
    }

    private static void checkStream(COSStream stream) throws IOException {
        List<COSName> filters = new ArrayList<>();
        if (stream.getFilters() instanceof COSName name) {
            filters.add(name);
        } else if (stream.getFilters() instanceof org.apache.pdfbox.cos.COSArray array) {
            for (int i = 0; i < array.size(); i++) {
                if (array.getObject(i) instanceof COSName name) {
                    filters.add(name);
                }
            }
        }
        if (filters.stream().anyMatch(IMAGE_FILTERS::contains)) {
            return; // Bilddaten (auch ohne /Subtype /Image) – kein Inhalt für den Parser
        }
        byte[] input = null;
        try (InputStream raw = stream.createRawInputStream()) {
            if (filters.isEmpty()) {
                raw.transferTo(new CheckingOutputStream());
                return;
            }
            for (int i = 0; i < filters.size(); i++) {
                boolean last = i == filters.size() - 1;
                BoundedBuffer buffer = last ? null : new BoundedBuffer();
                OutputStream out = last ? new CheckingOutputStream() : buffer;
                FilterFactory.INSTANCE.getFilter(filters.get(i))
                        .decode(i == 0 ? raw : new java.io.ByteArrayInputStream(input), out, stream, i);
                input = last ? null : buffer.toByteArray();
            }
        }
    }

    private static final Set<COSName> IMAGE_FILTERS = Set.of(COSName.DCT_DECODE, COSName.DCT_DECODE_ABBREVIATION,
            COSName.JPX_DECODE, COSName.JBIG2_DECODE, COSName.CCITTFAX_DECODE, COSName.CCITTFAX_DECODE_ABBREVIATION);

    /** Zwischenstufe bei mehreren Filtern – höchstens {@link #MAX_DECODED_STREAM_BYTES}. */
    private static final class BoundedBuffer extends java.io.ByteArrayOutputStream {
        @Override
        public synchronized void write(byte[] b, int off, int len) {
            if (count + (long) len > MAX_DECODED_STREAM_BYTES) {
                throw new java.io.UncheckedIOException(new OversizedStreamException("Datenstrom entpackt zu groß"));
            }
            super.write(b, off, len);
        }

        @Override
        public synchronized void write(int b) {
            write(new byte[] {(byte) b}, 0, 1);
        }
    }

    /** Zählt die entpackten Bytes und die Länge des aktuellen „Worts“, ohne etwas aufzubewahren. */
    private static final class CheckingOutputStream extends OutputStream {
        private long total;
        private int token;

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            total += len;
            if (total > MAX_DECODED_STREAM_BYTES) {
                throw new OversizedStreamException("Datenstrom entpackt größer als " + MAX_DECODED_STREAM_BYTES + " Bytes");
            }
            for (int i = off; i < off + len; i++) {
                token = isSeparator(b[i]) ? 0 : token + 1;
                if (token > MAX_TOKEN_BYTES) {
                    throw new OversizedStreamException("Datenstrom mit übergroßem Element");
                }
            }
        }
    }

    /** Leerraum und Trennzeichen der PDF-Syntax. */
    private static boolean isSeparator(byte b) {
        return switch (b) {
            case 0, 9, 10, 12, 13, 32, '(', ')', '<', '>', '[', ']', '{', '}', '/', '%' -> true;
            default -> false;
        };
    }
}
