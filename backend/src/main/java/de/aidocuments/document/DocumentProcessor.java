package de.aidocuments.document;

import de.aidocuments.ai.AiAnalysisService;
import de.aidocuments.ai.AiException;
import de.aidocuments.ai.DocumentAnalysis;
import de.aidocuments.document.PdfExtractor.PdfContent;
import de.aidocuments.document.PdfExtractor.PdfReadException;
import de.aidocuments.search.SearchIndex;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Die Verarbeitungs-Pipeline eines Dokuments: PDF lesen → Vorschaubild → KI-Analyse → speichern → indizieren.
 * Läuft im Hintergrund; der Fortschritt ist am Status des Dokuments ablesbar.
 */
@Component
public class DocumentProcessor {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessor.class);

    private final DocumentRepository documents;
    private final DocumentTextRepository texts;
    private final FileStorage storage;
    private final PdfExtractor pdfExtractor;
    private final AiAnalysisService ai;
    private final DocumentFacets facets;
    private final SearchIndex searchIndex;
    private final ThreadPoolTaskExecutor executor;

    public DocumentProcessor(DocumentRepository documents, DocumentTextRepository texts, FileStorage storage,
                             PdfExtractor pdfExtractor, AiAnalysisService ai, DocumentFacets facets,
                             SearchIndex searchIndex,
                             @Qualifier("documentExecutor") ThreadPoolTaskExecutor executor) {
        this.documents = documents;
        this.texts = texts;
        this.storage = storage;
        this.pdfExtractor = pdfExtractor;
        this.ai = ai;
        this.facets = facets;
        this.searchIndex = searchIndex;
        this.executor = executor;
    }

    public void enqueue(String documentId) {
        executor.execute(() -> {
            try {
                process(documentId);
            } catch (RuntimeException e) {
                log.error("Verarbeitung von Dokument {} abgebrochen", documentId, e);
                finish(documentId, null, null, "Unerwarteter Fehler: " + e.getMessage());
            }
        });
    }

    void process(String documentId) {
        Document doc = documents.findById(documentId).orElse(null);
        if (doc == null) {
            return;
        }
        doc.setStatus(DocumentStatus.PROCESSING);
        doc.setErrorMessage(null);
        documents.save(doc);
        reindex(doc);

        PdfContent pdf;
        try {
            pdf = pdfExtractor.extract(storage.resolve(doc.getStoragePath()), storage.thumbnailPath(documentId));
        } catch (PdfReadException e) {
            finish(documentId, null, null, e.getMessage());
            return;
        }

        if (!ai.isEnabled()) {
            finish(documentId, pdf, null, null);
            return;
        }
        // Zwischenstand sichern: Vorschaubild und Volltextsuche stehen schon bereit, während die KI noch liest.
        doc.setPageCount(pdf.pageCount());
        doc.setHasThumbnail(pdf.thumbnailCreated());
        documents.save(doc);
        texts.save(new DocumentText(documentId, pdf.text()));
        reindex(doc);
        try {
            AiAnalysisService.Result result = ai.analyze(new AiAnalysisService.Request(
                    storage.resolve(doc.getStoragePath()), doc.getOriginalFilename(), doc.getSizeBytes(),
                    pdf.pageCount(), pdf.text(), pdf.textIsSparse(), doc.getMailSubject(), doc.getMailFrom(),
                    facets.correspondents(150).stream().map(FacetCount::name).toList(),
                    facets.tags(300).stream().map(FacetCount::name).toList()));
            finish(documentId, pdf, result, null);
        } catch (AiException e) {
            log.warn("KI-Analyse von {} fehlgeschlagen: {}", doc.getOriginalFilename(), e.getMessage());
            finish(documentId, pdf, null, "KI-Analyse fehlgeschlagen: " + e.getMessage());
        }
    }

    /** Schreibt das Ergebnis zurück. Das Dokument wird frisch geladen, falls es zwischenzeitlich bearbeitet wurde. */
    private void finish(String documentId, PdfContent pdf, AiAnalysisService.Result aiResult, String error) {
        Document doc = documents.findById(documentId).orElse(null);
        if (doc == null) {
            return; // während der Verarbeitung gelöscht
        }
        String fullText = null;
        if (pdf != null) {
            doc.setPageCount(pdf.pageCount());
            doc.setHasThumbnail(pdf.thumbnailCreated());
            fullText = pdf.text();
        }
        if (aiResult != null) {
            DocumentAnalysis analysis = aiResult.analysis();
            doc.setTitle(clip(blankToNull(analysis.title()), 500));
            doc.setSummary(clip(blankToNull(analysis.summary()), 4000));
            doc.setCategory(clip(blankToNull(analysis.category()), 100));
            doc.setCorrespondent(clip(blankToNull(analysis.correspondent()), 255));
            doc.setDocumentDate(DocumentAnalysis.parseDate(analysis.documentDate()));
            doc.setLanguage(clip(blankToNull(analysis.language()), 32));
            doc.replaceTags(normalizeTags(analysis.tags()));
            doc.replaceKeyFacts(normalizeFacts(analysis.keyFacts()));
            boolean transcribed = aiResult.transcribed() && analysis.fullText() != null
                    && !analysis.fullText().isBlank();
            doc.setOcrUsed(transcribed);
            if (transcribed) {
                fullText = analysis.fullText().strip();
            }
        }
        if (doc.getTitle() == null) {
            doc.setTitle(titleFromFilename(doc.getOriginalFilename()));
        }
        if (error != null) {
            doc.setStatus(DocumentStatus.FAILED);
            doc.setErrorMessage(clip(error, 2000));
        } else {
            doc.setStatus(aiResult != null ? DocumentStatus.INDEXED : DocumentStatus.TEXT_ONLY);
            doc.setErrorMessage(aiResult != null ? aiResult.note() : null);
        }
        doc.setProcessedAt(Instant.now());
        if (fullText != null) {
            texts.save(new DocumentText(documentId, fullText));
        }
        documents.save(doc);
        reindex(doc);
    }

    public void reindex(Document doc) {
        String text = texts.findById(doc.getId()).map(DocumentText::content).orElse("");
        searchIndex.index(doc, text);
    }

    static Set<String> normalizeTags(List<String> tags) {
        Set<String> result = new LinkedHashSet<>();
        if (tags != null) {
            for (String tag : tags) {
                if (tag == null) {
                    continue;
                }
                String cleaned = tag.strip().toLowerCase(Locale.GERMAN).replaceAll("\\s+", " ");
                if (!cleaned.isEmpty() && cleaned.length() <= 100 && result.size() < 12) {
                    result.add(cleaned);
                }
            }
        }
        return result;
    }

    private static List<KeyFact> normalizeFacts(List<DocumentAnalysis.Fact> facts) {
        if (facts == null) {
            return List.of();
        }
        return facts.stream()
                .filter(Objects::nonNull)
                .filter(f -> f.label() != null && !f.label().isBlank() && f.value() != null && !f.value().isBlank())
                .limit(12)
                .map(f -> new KeyFact(clip(f.label().strip(), 200), clip(f.value().strip(), 1000)))
                .toList();
    }

    static String titleFromFilename(String filename) {
        String name = filename.replaceAll("(?i)\\.pdf$", "").replace('_', ' ').strip();
        return name.isEmpty() ? filename : name;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String clip(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
