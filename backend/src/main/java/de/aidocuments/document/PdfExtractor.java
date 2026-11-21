package de.aidocuments.document;

import de.aidocuments.i18n.Localizable;
import de.aidocuments.i18n.Msg;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
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
        try (PDDocument pdf = Loader.loadPDF(file.toFile())) {
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
        try (PDDocument pdf = Loader.loadPDF(file.toFile())) {
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
        try (PDDocument pdf = Loader.loadPDF(file.toFile())) {
            PDFRenderer renderer = new PDFRenderer(pdf);
            int pages = Math.min(maxPages, pdf.getNumberOfPages());
            for (int page = 0; page < pages; page++) {
                float pageWidth = pdf.getPage(page).getCropBox().getWidth();
                float scale = pageWidth > 0 ? Math.min(widthPx / pageWidth, 4f) : 1f;
                BufferedImage image = renderer.renderImage(page, scale, ImageType.RGB);
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
        PDDocument pdf = Loader.loadPDF(file.toFile());
        PDFRenderer renderer = new PDFRenderer(pdf);
        return new PageImages() {
            @Override
            public int pageCount() {
                return pdf.getNumberOfPages();
            }

            @Override
            public byte[] png(int page) throws IOException {
                BufferedImage image = renderer.renderImageWithDPI(page, dpi, ImageType.GRAY);
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
            BufferedImage image = new PDFRenderer(pdf).renderImage(0, Math.min(scale, 4f), ImageType.RGB);
            Files.createDirectories(target.getParent());
            return ImageIO.write(image, "jpg", target.toFile());
        } catch (IOException | RuntimeException e) {
            // ein fehlendes Vorschaubild soll die Verarbeitung nicht aufhalten
            log.warn("Vorschaubild konnte nicht erzeugt werden: {}", e.toString());
            return false;
        }
    }
}
