package de.aidocuments.document;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Macht aus einem Foto oder Bild (JPEG, PNG, HEIC) ein einseitiges PDF – oder aus mehreren eines mit je einer Seite –,
 * damit es wie jedes andere Dokument abgelegt, per Texterkennung gelesen und von der KI analysiert wird.
 *
 * <p>JPEGs werden unverändert eingebettet (kein erneutes Komprimieren); die Drehung aus den EXIF-Daten setzt die
 * Seitenmatrix um. PNGs werden verlustfrei übernommen. HEIC (iPhone-Fotos) kann Java nicht lesen – dafür wird
 * {@code heif-convert} (libheif, im Docker-Image enthalten) oder unter macOS {@code sips} verwendet.
 */
@Component
public class ImageToPdf {

    private static final Logger log = LoggerFactory.getLogger(ImageToPdf.class);

    /** Mehr Bildpunkte werden nicht angenommen – ein winziges PNG kann sonst beim Entpacken Gigabytes belegen. */
    static final long MAX_PIXELS = 60_000_000L;

    /** Die längere Seite der erzeugten Seite in Punkt (wie DIN A4). */
    private static final float LONG_SIDE = PDRectangle.A4.getHeight();

    private static final long CONVERTER_TIMEOUT_SECONDS = 60;

    public enum Kind {
        JPEG, PNG, HEIC
    }

    /** Warum ein Bild nicht übernommen wurde – der Schlüssel ist die Meldung an den Benutzer. */
    public static final class Rejected extends Exception {
        private final String messageKey;

        Rejected(String messageKey) {
            super(messageKey);
            this.messageKey = messageKey;
        }

        public String messageKey() {
            return messageKey;
        }
    }

    /** Das Bild ist kleiner als verlangt (etwa ein Logo in einer E-Mail) und wird stillschweigend übergangen. */
    public static final class TooSmall extends Exception {
        TooSmall() {
            super("Bild zu klein");
        }
    }

    private final List<String> heicConverter;

    public ImageToPdf() {
        this.heicConverter = findHeicConverter();
    }

    /** Erkennt das Bildformat am Dateianfang; {@code null}, wenn es keines der unterstützten ist. */
    public static Kind detect(Path file) throws IOException {
        byte[] head = new byte[32];
        int read;
        try (InputStream in = Files.newInputStream(file)) {
            read = in.readNBytes(head, 0, head.length);
        }
        if (read >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return Kind.JPEG;
        }
        if (read >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G'
                && head[4] == 0x0D && head[5] == 0x0A && head[6] == 0x1A && head[7] == 0x0A) {
            return Kind.PNG;
        }
        // ISO-Basismedienformat: Box "ftyp" mit einer HEIF-Marke
        if (read >= 12 && new String(head, 4, 4, StandardCharsets.ISO_8859_1).equals("ftyp")) {
            String brand = new String(head, 8, 4, StandardCharsets.ISO_8859_1);
            if (List.of("heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1").contains(brand)) {
                return Kind.HEIC;
            }
        }
        return null;
    }

    /**
     * Schreibt das Bild als einseitiges PDF nach {@code target}.
     *
     * @param minSide kürzeste Seite in Bildpunkten, die das Bild mindestens haben muss (0 = keine Untergrenze)
     */
    public void convert(Path image, Kind kind, Path target, int minSide) throws IOException, Rejected, TooSmall {
        convertAll(List.of(new Image(image, kind)), target, minSide);
    }

    /** Ein Bild und sein Format. */
    public record Image(Path file, Kind kind) {
    }

    /** Höchstens so viele Fotos werden zu einem Dokument zusammengefasst. */
    public static final int MAX_COMBINED = 100;

    /**
     * Schreibt die Bilder in dieser Reihenfolge als Seiten eines PDFs nach {@code target} – etwa die fotografierten
     * Seiten eines Briefs.
     */
    public void convertAll(List<Image> images, Path target, int minSide) throws IOException, Rejected, TooSmall {
        if (images.isEmpty() || images.size() > MAX_COMBINED) {
            throw new Rejected("upload.tooManyImages");
        }
        try (PDDocument pdf = new PDDocument()) {
            for (Image image : images) {
                addPage(pdf, image, minSide);
            }
            pdf.getDocumentInformation().setCreator("AIDocuments");
            pdf.save(target.toFile());
        }
    }

    private void addPage(PDDocument pdf, Image image, int minSide) throws IOException, Rejected, TooSmall {
        Path jpeg = null;
        try {
            Path source = image.file();
            Kind sourceKind = image.kind();
            if (sourceKind == Kind.HEIC) {
                jpeg = source.resolveSibling(source.getFileName() + ".jpg");
                decodeHeic(source, jpeg);
                source = jpeg;
                sourceKind = Kind.JPEG;
            }
            int[] size = dimensions(source);
            if ((long) size[0] * size[1] > MAX_PIXELS) {
                throw new Rejected("upload.imageTooLarge");
            }
            if (Math.min(size[0], size[1]) < minSide) {
                throw new TooSmall();
            }
            int orientation = sourceKind == Kind.JPEG ? ExifOrientation.read(source) : 1;
            write(pdf, source, sourceKind, orientation);
        } finally {
            if (jpeg != null) {
                Files.deleteIfExists(jpeg);
            }
        }
    }

    private static void write(PDDocument pdf, Path source, Kind kind, int orientation) throws IOException, Rejected {
        PDImageXObject image;
        try {
            if (kind == Kind.JPEG) {
                image = JPEGFactory.createFromByteArray(pdf, Files.readAllBytes(source));
            } else {
                BufferedImage decoded = ImageIO.read(source.toFile());
                if (decoded == null) {
                    throw new Rejected("upload.imageUnreadable");
                }
                image = LosslessFactory.createFromImage(pdf, decoded);
            }
        } catch (IOException | IllegalArgumentException e) {
            log.debug("Bild nicht lesbar: {}", e.toString());
            throw new Rejected("upload.imageUnreadable");
        }
        boolean swapped = orientation >= 5 && orientation <= 8;
        float shownWidth = swapped ? image.getHeight() : image.getWidth();
        float shownHeight = swapped ? image.getWidth() : image.getHeight();
        float scale = LONG_SIDE / Math.max(shownWidth, shownHeight);
        float w = shownWidth * scale;
        float h = shownHeight * scale;
        PDPage page = new PDPage(new PDRectangle(w, h));
        pdf.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
            content.drawImage(image, placement(orientation, w, h));
        }
    }

    /**
     * Bildet das Einheitsquadrat des Bildes (u nach rechts, v nach oben; v = 1 ist die oberste gespeicherte Zeile) so
     * auf die Seite ab, wie die EXIF-Ausrichtung es verlangt.
     */
    static Matrix placement(int orientation, float w, float h) {
        return switch (orientation) {
            case 2 -> new Matrix(-w, 0, 0, h, w, 0);     // gespiegelt
            case 3 -> new Matrix(-w, 0, 0, -h, w, h);    // 180°
            case 4 -> new Matrix(w, 0, 0, -h, 0, h);     // senkrecht gespiegelt
            case 5 -> new Matrix(0, -h, -w, 0, w, h);    // transponiert
            case 6 -> new Matrix(0, -h, w, 0, 0, h);     // 90° im Uhrzeigersinn
            case 7 -> new Matrix(0, h, w, 0, 0, 0);      // quer transponiert
            case 8 -> new Matrix(0, h, -w, 0, w, 0);     // 90° gegen den Uhrzeigersinn
            default -> new Matrix(w, 0, 0, h, 0, 0);
        };
    }

    /** Breite und Höhe, ohne das Bild zu entpacken. */
    private static int[] dimensions(Path file) throws IOException, Rejected {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers = in == null ? java.util.Collections.emptyIterator() : ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new Rejected("upload.imageUnreadable");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                return new int[] {reader.getWidth(0), reader.getHeight(0)};
            } catch (IOException e) {
                throw new Rejected("upload.imageUnreadable");
            } finally {
                reader.dispose();
            }
        }
    }

    // --- HEIC ---

    public boolean heicSupported() {
        return heicConverter != null;
    }

    private void decodeHeic(Path heic, Path jpeg) throws IOException, Rejected {
        if (heicConverter == null) {
            throw new Rejected("upload.heicUnsupported");
        }
        List<String> command = new java.util.ArrayList<>(heicConverter);
        if (heicConverter.getFirst().endsWith("sips")) {
            command.addAll(List.of(heic.toString(), "--out", jpeg.toString()));
        } else {
            command.addAll(List.of(heic.toString(), jpeg.toString()));
        }
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            if (!process.waitFor(CONVERTER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new Rejected("upload.imageUnreadable");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("HEIC-Umwandlung unterbrochen", e);
        }
        if (process.exitValue() != 0 || !Files.isRegularFile(jpeg) || Files.size(jpeg) == 0) {
            throw new Rejected("upload.imageUnreadable");
        }
    }

    private static List<String> findHeicConverter() {
        String path = System.getenv("PATH");
        List<String> dirs = new java.util.ArrayList<>(path == null ? List.of() : List.of(path.split(java.io.File.pathSeparator)));
        dirs.addAll(List.of("/usr/bin", "/usr/local/bin", "/opt/homebrew/bin"));
        for (String name : List.of("heif-convert", "heif-dec")) {
            for (String dir : dirs) {
                Path candidate = Path.of(dir, name);
                if (Files.isExecutable(candidate)) {
                    log.info("HEIC-Fotos werden mit {} umgewandelt", candidate);
                    return List.of(candidate.toString(), "-q", "92");
                }
            }
        }
        Path sips = Path.of("/usr/bin/sips");
        if (Files.isExecutable(sips)) {
            log.info("HEIC-Fotos werden mit {} umgewandelt", sips);
            return List.of(sips.toString(), "-s", "format", "jpeg");
        }
        log.info("Kein HEIC-Umwandler gefunden (heif-convert) – HEIC-Fotos werden abgelehnt");
        return null;
    }
}
