package de.aidocuments.document;

import de.aidocuments.search.SearchIndex;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Erkennt Dokumente, die inhaltlich dasselbe Schreiben sind (siehe {@link ContentFingerprint}). Das spätere der
 * beiden wird als mögliches Duplikat des früheren vermerkt; der Anwender entscheidet, ob er es löscht oder als
 * eigenständig bestätigt. Inhaltsgleiche Dateien kommen gar nicht erst an – die erkennt schon der Import.
 */
@Component
public class DuplicateDetector {

    private static final Logger log = LoggerFactory.getLogger(DuplicateDetector.class);

    private final DocumentRepository documents;
    private final DocumentTextRepository texts;
    private final SearchIndex searchIndex;

    public DuplicateDetector(DocumentRepository documents, DocumentTextRepository texts, SearchIndex searchIndex) {
        this.documents = documents;
        this.texts = texts;
        this.searchIndex = searchIndex;
    }

    /**
     * Setzt Fingerabdruck und Duplikat-Vermerk des Dokuments neu (gespeichert wird es vom Aufrufer). Ist es selbst das
     * frühere von zweien, wird stattdessen das spätere vermerkt – und gleich gespeichert.
     */
    public void check(Document doc, String text) {
        ContentFingerprint fingerprint = ContentFingerprint.of(text);
        doc.setFingerprint(fingerprint);
        doc.setDuplicateOf(null);
        if (fingerprint == null) {
            return;
        }
        Document best = null;
        double bestScore = 0;
        for (Document other : documents.findByFingerprintIsNotNull()) {
            // nur innerhalb des Bereichs: sonst verwiese der Vermerk auf ein Dokument, das der Leser nicht sehen darf
            if (other.getId().equals(doc.getId()) || !other.getAreaId().equals(doc.getAreaId()) || dismissed(doc, other)
                    || !fingerprint.sameDocumentAs(other.getFingerprint())) {
                continue;
            }
            double score = fingerprint.textSimilarity(other.getFingerprint());
            if (score > bestScore) {
                best = other;
                bestScore = score;
            }
        }
        if (best == null) {
            return;
        }
        if (!best.getCreatedAt().isAfter(doc.getCreatedAt())) {
            doc.setDuplicateOf(best.getId());
        } else if (best.getDuplicateOf() == null) {
            best.setDuplicateOf(doc.getId());
            documents.save(best);
            reindex(best);
        }
        log.info("Mögliches Duplikat erkannt: {} und {} (Textähnlichkeit {} %)", doc.getId(), best.getId(),
                Math.round(bestScore * 100));
    }

    /** Der Anwender sagt: kein Duplikat. Das Paar wird künftig nicht mehr gemeldet. */
    public void dismiss(Document doc) {
        if (doc.getDuplicateOf() != null) {
            doc.getNotDuplicateOf().add(doc.getDuplicateOf());
            doc.setDuplicateOf(null);
        }
    }

    /** Ein Dokument wurde gelöscht: Was als sein Duplikat vermerkt war, ist jetzt eigenständig. */
    public void forget(String deletedId) {
        for (Document other : documents.findByDuplicateOf(deletedId)) {
            other.setDuplicateOf(null);
            documents.save(other);
            reindex(other);
        }
    }

    /**
     * Gleicht den ganzen Bestand ab – beim Start im Hintergrund, damit auch Dokumente aus der Zeit vor der Erkennung
     * einen Fingerabdruck bekommen. Fertig verarbeitete Dokumente ohne Fingerabdruck erhalten einen; danach wird jedes
     * Dokument mit den früheren verglichen.
     */
    public void scanAll() {
        List<Document> all = new ArrayList<>(documents.findAll());
        int fingerprinted = 0;
        for (Document doc : all) {
            if (doc.getFingerprint() == null && doc.getStatus() != DocumentStatus.PENDING
                    && doc.getStatus() != DocumentStatus.PROCESSING) {
                ContentFingerprint fingerprint = texts.findById(doc.getId())
                        .map(t -> ContentFingerprint.of(t.content())).orElse(null);
                if (fingerprint != null) {
                    doc.setFingerprint(fingerprint);
                    documents.save(doc);
                    fingerprinted++;
                }
            }
        }
        all.sort(Comparator.comparing(Document::getCreatedAt));
        int found = 0;
        for (int i = 0; i < all.size(); i++) {
            Document doc = all.get(i);
            if (doc.getFingerprint() == null || doc.getDuplicateOf() != null) {
                continue;
            }
            for (int j = 0; j < i; j++) {
                Document earlier = all.get(j);
                if (earlier.getFingerprint() != null && earlier.getDuplicateOf() == null && !dismissed(doc, earlier)
                        && doc.getFingerprint().sameDocumentAs(earlier.getFingerprint())) {
                    doc.setDuplicateOf(earlier.getId());
                    documents.save(doc);
                    reindex(doc);
                    found++;
                    break;
                }
            }
        }
        if (fingerprinted > 0 || found > 0) {
            log.info("Duplikatabgleich: {} Fingerabdrücke erstellt, {} neue mögliche Duplikate", fingerprinted, found);
        }
    }

    /** Im Hintergrund, damit der Start der Anwendung nicht darauf wartet. */
    public void scanAllInBackground() {
        Thread thread = new Thread(() -> {
            try {
                scanAll();
            } catch (RuntimeException | OutOfMemoryError | StackOverflowError e) {
                log.warn("Duplikatabgleich abgebrochen: {}", e.toString());
            }
        }, "duplicate-scan");
        thread.setDaemon(true);
        thread.start();
    }

    private static boolean dismissed(Document a, Document b) {
        return a.getNotDuplicateOf().contains(b.getId()) || b.getNotDuplicateOf().contains(a.getId());
    }

    private void reindex(Document doc) {
        searchIndex.index(doc, texts.findById(doc.getId()).map(DocumentText::content).orElse(""));
    }
}
