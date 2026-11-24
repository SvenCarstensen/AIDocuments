package de.aidocuments.document;

import de.aidocuments.i18n.Msg;
import de.aidocuments.i18n.MsgCodec;
import de.aidocuments.persistence.SettingsStore;
import de.aidocuments.search.SearchIndex;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Bringt beim Start Suchindex und Datenbank in Einklang und setzt unterbrochene Verarbeitungen fort.
 *
 * <p>Ein Dokument, dessen Verarbeitung {@value #MAX_INTERRUPTIONS}-mal hintereinander durch das Ende des Servers
 * unterbrochen wurde (etwa weil ein präpariertes PDF ihn jedes Mal zum Absturz bringt), wird nicht wieder eingereiht,
 * sondern als fehlgeschlagen markiert – neu verarbeiten lässt es sich von Hand.
 */
@Component
public class DocumentStartup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DocumentStartup.class);
    static final String INTERRUPTED_ID = "processingInterrupted";
    static final int MAX_INTERRUPTIONS = 3;

    /** Je Dokument: wie oft hintereinander unterbrochen, und sein Verarbeitungsstand dabei (null = noch nie fertig). */
    record Interruption(int count, Long processedAt) {
    }

    record Interruptions(Map<String, Interruption> documents) {
    }

    private final DocumentRepository documents;
    private final DocumentTextRepository texts;
    private final SearchIndex searchIndex;
    private final DocumentProcessor processor;
    private final ThumbnailRepair thumbnailRepair;
    private final DuplicateDetector duplicates;
    private final SettingsStore settings;

    public DocumentStartup(DocumentRepository documents, DocumentTextRepository texts, SearchIndex searchIndex,
                           DocumentProcessor processor, ThumbnailRepair thumbnailRepair,
                           DuplicateDetector duplicates, SettingsStore settings) {
        this.settings = settings;
        this.duplicates = duplicates;
        this.documents = documents;
        this.texts = texts;
        this.searchIndex = searchIndex;
        this.processor = processor;
        this.thumbnailRepair = thumbnailRepair;
    }

    @Override
    public void run(ApplicationArguments args) {
        long inDatabase = documents.count();
        if (searchIndex.size() != inDatabase || !searchIndex.upToDate()) {
            log.info("Suchindex ({} Einträge) weicht von der Datenbank ({} Dokumente) ab oder hat einen älteren Aufbau"
                    + " – wird neu aufgebaut", searchIndex.size(), inDatabase);
            Map<String, String> fullTexts = texts.findAll().stream()
                    .collect(Collectors.toMap(DocumentText::documentId, DocumentText::content));
            searchIndex.rebuild(documents.findAll(), fullTexts);
        }
        List<Document> unfinished = documents.findByStatusIn(
                List.of(DocumentStatus.PENDING, DocumentStatus.PROCESSING));
        Interruptions stored = settings.find(INTERRUPTED_ID, Interruptions.class);
        Map<String, Interruption> previous = stored == null || stored.documents() == null ? Map.of()
                : stored.documents();
        Map<String, Interruption> current = new HashMap<>();
        if (!unfinished.isEmpty()) {
            log.info("{} unterbrochene Verarbeitung(en) werden fortgesetzt", unfinished.size());
            for (Document doc : unfinished) {
                // Was beim Beenden mitten in der Analyse steckte, wartet jetzt wieder – und soll auch so aussehen.
                if (doc.getStatus() == DocumentStatus.PROCESSING) {
                    Long processedAt = doc.getProcessedAt() == null ? null : doc.getProcessedAt().toEpochMilli();
                    Interruption last = previous.get(doc.getId());
                    int count = last != null && Objects.equals(last.processedAt(), processedAt) ? last.count() + 1 : 1;
                    if (count >= MAX_INTERRUPTIONS) {
                        // nicht endlos von vorn: Bei jedem Start wieder dasselbe Dokument, an dem der Server hängt
                        log.warn("Verarbeitung von Dokument {} ({}) wurde {}-mal unterbrochen – als fehlgeschlagen "
                                + "markiert", doc.getId(), doc.getOriginalFilename(), count);
                        doc.setStatus(DocumentStatus.FAILED);
                        doc.setErrorMessage(MsgCodec.encode(Msg.of("error.unexpected",
                                Msg.literal("processing was interrupted " + count + " times"))));
                        doc.setProcessedAt(Instant.now());
                        processor.reindex(doc);
                        documents.save(doc);
                        continue;
                    }
                    current.put(doc.getId(), new Interruption(count, processedAt));
                    doc.setStatus(DocumentStatus.PENDING);
                    documents.save(doc);
                }
                processor.enqueue(doc.getId());
            }
        }
        if (!current.isEmpty() || !previous.isEmpty()) {
            settings.save(INTERRUPTED_ID, new Interruptions(current));
        }
        thumbnailRepair.startInBackground();
        duplicates.scanAllInBackground();
    }
}
