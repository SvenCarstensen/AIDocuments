package de.aidocuments.document;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Ersetzt Vorschaubilder, die als leere weiße Fläche geraten sind. Das passiert, wenn dem Renderer beim
 * Erzeugen ein Bild-Decoder fehlte (so bei JBIG2-Scans, bevor der Decoder eingebunden war): Die Seite wird dann
 * ohne ihren Inhalt gezeichnet. Läuft bei jedem Start einmal im Hintergrund – nach einem Update mit neuem
 * Decoder heilen sich die betroffenen Bilder so von selbst.
 */
@Component
public class ThumbnailRepair {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailRepair.class);

    private final DocumentRepository documents;
    private final FileStorage storage;
    private final PdfExtractor pdfExtractor;

    public ThumbnailRepair(DocumentRepository documents, FileStorage storage, PdfExtractor pdfExtractor) {
        this.documents = documents;
        this.storage = storage;
        this.pdfExtractor = pdfExtractor;
    }

    /** Im Hintergrund, damit der Start der Anwendung nicht darauf wartet. */
    public void startInBackground() {
        Thread thread = new Thread(this::run, "thumbnail-repair");
        thread.setDaemon(true);
        thread.start();
    }

    void run() {
        int repaired = 0;
        try {
            for (Document doc : documents.findAll()) {
                if (doc.isHasThumbnail() && repair(doc)) {
                    repaired++;
                }
            }
        } catch (RuntimeException e) {
            log.warn("Prüfung der Vorschaubilder abgebrochen: {}", e.toString());
        }
        if (repaired > 0) {
            log.info("{} leere(s) Vorschaubild(er) neu erzeugt", repaired);
        }
    }

    private boolean repair(Document doc) {
        Path thumbnail = storage.thumbnailPath(doc.getId());
        Path fresh = thumbnail.resolveSibling(doc.getId() + ".neu.jpg");
        try {
            if (!Files.exists(thumbnail) || !isBlank(ImageIO.read(thumbnail.toFile()))) {
                return false;
            }
            // Erst daneben erzeugen: Bleibt auch das neue Bild leer (eine wirklich leere erste Seite), ändert sich
            // nichts, und es wird nie ein halb geschriebenes Bild ausgeliefert.
            if (!pdfExtractor.renderThumbnail(storage.resolve(doc.getStoragePath()), fresh)
                    || isBlank(ImageIO.read(fresh.toFile()))) {
                return false;
            }
            Files.move(fresh, thumbnail, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Vorschaubild von {} konnte nicht geprüft werden: {}", doc.getId(), e.toString());
            return false;
        } finally {
            try {
                Files.deleteIfExists(fresh);
            } catch (IOException ignored) {
                // bleibt liegen und wird beim nächsten Lauf überschrieben
            }
        }
    }

    /** Leer heißt: durchgehend (fast) weiß. Geprüft wird ein Raster von Bildpunkten, das genügt und ist schnell. */
    static boolean isBlank(BufferedImage image) {
        if (image == null) {
            return false;
        }
        for (int y = 0; y < image.getHeight(); y += 3) {
            for (int x = 0; x < image.getWidth(); x += 3) {
                int rgb = image.getRGB(x, y);
                if (((rgb >> 16) & 0xFF) < 245 || ((rgb >> 8) & 0xFF) < 245 || (rgb & 0xFF) < 245) {
                    return false;
                }
            }
        }
        return true;
    }
}
