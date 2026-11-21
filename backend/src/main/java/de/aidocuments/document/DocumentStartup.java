package de.aidocuments.document;

import de.aidocuments.search.SearchIndex;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Bringt beim Start Suchindex und Datenbank in Einklang und setzt unterbrochene Verarbeitungen fort. */
@Component
public class DocumentStartup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DocumentStartup.class);

    private final DocumentRepository documents;
    private final DocumentTextRepository texts;
    private final SearchIndex searchIndex;
    private final DocumentProcessor processor;
    private final ThumbnailRepair thumbnailRepair;

    public DocumentStartup(DocumentRepository documents, DocumentTextRepository texts, SearchIndex searchIndex,
                           DocumentProcessor processor, ThumbnailRepair thumbnailRepair) {
        this.documents = documents;
        this.texts = texts;
        this.searchIndex = searchIndex;
        this.processor = processor;
        this.thumbnailRepair = thumbnailRepair;
    }

    @Override
    public void run(ApplicationArguments args) {
        long inDatabase = documents.count();
        if (searchIndex.size() != inDatabase) {
            log.info("Suchindex ({} Einträge) weicht von der Datenbank ({} Dokumente) ab – wird neu aufgebaut",
                    searchIndex.size(), inDatabase);
            Map<String, String> fullTexts = texts.findAll().stream()
                    .collect(Collectors.toMap(DocumentText::documentId, DocumentText::content));
            searchIndex.rebuild(documents.findAll(), fullTexts);
        }
        List<Document> unfinished = documents.findByStatusIn(
                List.of(DocumentStatus.PENDING, DocumentStatus.PROCESSING));
        if (!unfinished.isEmpty()) {
            log.info("{} unterbrochene Verarbeitung(en) werden fortgesetzt", unfinished.size());
            for (Document doc : unfinished) {
                // Was beim Beenden mitten in der Analyse steckte, wartet jetzt wieder – und soll auch so aussehen.
                if (doc.getStatus() == DocumentStatus.PROCESSING) {
                    doc.setStatus(DocumentStatus.PENDING);
                    documents.save(doc);
                }
                processor.enqueue(doc.getId());
            }
        }
        thumbnailRepair.startInBackground();
    }
}
