package de.aidocuments.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.aidocuments.i18n.MsgCodec;
import de.aidocuments.persistence.SettingsStore;
import de.aidocuments.search.SearchIndex;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Ein Dokument, an dem der Server bei jedem Start hängen bleibt, wird nicht endlos wieder eingereiht. */
class DocumentStartupTest {

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final SearchIndex searchIndex = mock(SearchIndex.class);
    private final DocumentProcessor processor = mock(DocumentProcessor.class);
    private final SettingsStore settings = mock(SettingsStore.class);
    private final Map<String, Object> stored = new HashMap<>();
    private DocumentStartup startup;

    @BeforeEach
    void setUp() {
        when(searchIndex.upToDate()).thenReturn(true);
        when(settings.find(anyString(), any())).thenAnswer(call -> stored.get(call.<String>getArgument(0)));
        doAnswer(call -> stored.put(call.getArgument(0), call.getArgument(1))).when(settings)
                .save(anyString(), any());
        startup = new DocumentStartup(documents, mock(DocumentTextRepository.class), searchIndex, processor,
                mock(ThumbnailRepair.class), mock(DuplicateDetector.class), settings);
    }

    @Test
    void aDocumentInterruptedThreeTimesInARowIsMarkedAsFailed() {
        Document doc = new Document("doc-1", "haengt.pdf", "doc-1.pdf", "abc", 10, DocumentSource.UPLOAD);
        // erster und zweiter Start: wieder eingereiht
        for (int start = 1; start <= 2; start++) {
            doc.setStatus(DocumentStatus.PROCESSING); // der Verarbeiter hat begonnen, dann endete der Server
            startUpWith(doc);
            assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PENDING);
        }
        verify(processor, times(2)).enqueue("doc-1");
        // dritter Start: fehlgeschlagen, nicht noch einmal eingereiht
        doc.setStatus(DocumentStatus.PROCESSING);
        startUpWith(doc);
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(MsgCodec.decode(doc.getErrorMessage()).getFirst().key()).isEqualTo("error.unexpected");
        verify(processor, times(2)).enqueue("doc-1");
        verify(processor).reindex(doc);
        // danach ist der Zähler leer
        assertThat(((DocumentStartup.Interruptions) stored.get(DocumentStartup.INTERRUPTED_ID)).documents())
                .isEmpty();
    }

    @Test
    void aFinishedProcessingInBetweenStartsTheCountAgain() {
        Document doc = new Document("doc-2", "neu.pdf", "doc-2.pdf", "def", 10, DocumentSource.UPLOAD);
        for (int start = 1; start <= 2; start++) {
            doc.setStatus(DocumentStatus.PROCESSING);
            startUpWith(doc);
        }
        // dazwischen einmal fertig geworden (z. B. „Neu verarbeiten“ später erneut angestoßen)
        doc.setProcessedAt(Instant.now());
        doc.setStatus(DocumentStatus.PROCESSING);
        startUpWith(doc);
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PENDING);
        verify(processor, times(3)).enqueue("doc-2");
        verify(processor, never()).reindex(any());
    }

    @Test
    void waitingDocumentsAreSimplyQueuedAgain() {
        Document doc = new Document("doc-3", "wartet.pdf", "doc-3.pdf", "ghi", 10, DocumentSource.UPLOAD);
        for (int start = 1; start <= 5; start++) {
            startUpWith(doc); // PENDING: noch gar nicht angefangen, also nicht unterbrochen
        }
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PENDING);
        verify(processor, times(5)).enqueue(eq("doc-3"));
    }

    private void startUpWith(Document doc) {
        when(documents.findByStatusIn(any())).thenReturn(List.of(doc));
        startup.run(null);
    }
}
