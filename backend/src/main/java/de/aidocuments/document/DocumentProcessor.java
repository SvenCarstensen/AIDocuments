package de.aidocuments.document;

import de.aidocuments.ai.AiAnalysisService;
import de.aidocuments.ai.AiException;
import de.aidocuments.ai.BuiltinOcrService;
import de.aidocuments.ai.DocumentAnalysis;
import de.aidocuments.document.PdfExtractor.PdfContent;
import de.aidocuments.document.PdfExtractor.PdfReadException;
import de.aidocuments.i18n.Messages;
import de.aidocuments.i18n.Msg;
import de.aidocuments.i18n.MsgCodec;
import de.aidocuments.search.SearchIndex;
import de.aidocuments.settings.AiSettingsService;
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
    private final BuiltinOcrService builtinOcr;
    private final DocumentFacets facets;
    private final SearchIndex searchIndex;
    private final ThreadPoolTaskExecutor executor;
    private final ThreadPoolTaskExecutor aiExecutor;
    private final AiSettingsService aiSettings;
    private final DuplicateDetector duplicates;
    private final Messages messages;

    public DocumentProcessor(DocumentRepository documents, DocumentTextRepository texts, FileStorage storage,
                             PdfExtractor pdfExtractor, AiAnalysisService ai, BuiltinOcrService builtinOcr,
                             DocumentFacets facets,
                             SearchIndex searchIndex,
                             @Qualifier("documentExecutor") ThreadPoolTaskExecutor executor,
                             @Qualifier("aiExecutor") ThreadPoolTaskExecutor aiExecutor, AiSettingsService aiSettings,
                             DuplicateDetector duplicates, Messages messages) {
        this.duplicates = duplicates;
        this.aiExecutor = aiExecutor;
        this.aiSettings = aiSettings;
        this.messages = messages;
        this.documents = documents;
        this.texts = texts;
        this.storage = storage;
        this.pdfExtractor = pdfExtractor;
        this.ai = ai;
        this.builtinOcr = builtinOcr;
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
                finish(documentId, null, null, Msg.of("error.unexpected", Msg.detailOf(e)), null);
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
            finish(documentId, null, null, e.msg(), null);
            return;
        }

        boolean aiEnabled = ai.isEnabled();
        boolean scan = pdf.textIsSparse() && builtinOcr.isAvailable();
        // Ohne KI liest die eingebaute Texterkennung Scans; mit KI nur, wenn zuerst ein schnelles Ergebnis gewünscht ist.
        boolean quickOcr = scan && (!aiEnabled || aiSettings.current().quickOcrFirst());
        if (!aiEnabled && !quickOcr) {
            finish(documentId, pdf, null, null, null);
            return;
        }
        // Zwischenstand sichern: Vorschaubild und Volltextsuche stehen schon bereit, während die KI noch liest.
        doc.setPageCount(pdf.pageCount());
        doc.setHasThumbnail(pdf.thumbnailCreated());
        documents.save(doc);
        texts.save(new DocumentText(documentId, pdf.text()));
        reindex(doc);

        BuiltinOcrService.Result ocr = null;
        if (quickOcr) {
            try {
                ocr = builtinOcr.transcribe(storage.resolve(doc.getStoragePath()), pdf.pageCount());
            } catch (AiException e) {
                log.warn("Texterkennung von {} fehlgeschlagen: {}", doc.getOriginalFilename(), messages.forLog(e.msg()));
                if (!aiEnabled) {
                    finish(documentId, pdf, null, Msg.of("document.error.aiFailed", e.msg()), null);
                    return;
                }
            }
            if (!aiEnabled) {
                finishTranscribed(documentId, pdf, ocr);
                return;
            }
            if (ocr != null && !ocr.text().isBlank()) {
                // schnelles erstes Ergebnis: der erkannte Text ist sofort durchsuchbar, die KI verfeinert danach
                Document current = documents.findById(documentId).orElse(null);
                if (current == null) {
                    return;
                }
                current.setOcrUsed(true);
                documents.save(current);
                texts.save(new DocumentText(documentId, ocr.text().strip()));
                reindex(current);
            }
        }
        BuiltinOcrService.Result quickText = ocr;
        aiExecutor.execute(() -> {
            try {
                analyze(documentId, pdf, quickText);
            } catch (RuntimeException e) {
                log.error("KI-Analyse von Dokument {} abgebrochen", documentId, e);
                finish(documentId, pdf, null, Msg.of("error.unexpected", Msg.detailOf(e)), quickText);
            }
        });
    }

    /**
     * Die KI-Analyse, in ihrer eigenen Warteschlange. Liegt schon ein Text der eingebauten Texterkennung vor, bekommt
     * die KI diesen Text statt der Seitenbilder – sie verfeinert dann nur noch die Erschließung, das geht schneller.
     */
    private void analyze(String documentId, PdfContent pdf, BuiltinOcrService.Result ocr) {
        Document doc = documents.findById(documentId).orElse(null);
        if (doc == null) {
            return; // inzwischen gelöscht
        }
        boolean ocrText = ocr != null && !ocr.text().isBlank();
        try {
            AiAnalysisService.Result result = ai.analyze(new AiAnalysisService.Request(
                    storage.resolve(doc.getStoragePath()), doc.getOriginalFilename(), doc.getSizeBytes(),
                    pdf.pageCount(), ocrText ? ocr.text() : pdf.text(), !ocrText && pdf.textIsSparse(),
                    doc.getMailSubject(), doc.getMailFrom(),
                    facets.correspondents(150).stream().map(FacetCount::name).toList(),
                    facets.tags(300).stream().map(FacetCount::name).toList()));
            finish(documentId, pdf, result, null, ocr);
        } catch (AiException e) {
            log.warn("KI-Analyse von {} fehlgeschlagen: {}", doc.getOriginalFilename(), messages.forLog(e.msg()));
            finish(documentId, pdf, null, Msg.of("document.error.aiFailed", e.msg()), ocr);
        }
    }

    /** Ergebnis der eingebauten Texterkennung: nur der Text, keine Erschließung. */
    private void finishTranscribed(String documentId, PdfContent pdf, BuiltinOcrService.Result ocr) {
        Document doc = documents.findById(documentId).orElse(null);
        if (doc == null || ocr == null) {
            return; // während der Verarbeitung gelöscht
        }
        doc.setPageCount(pdf.pageCount());
        doc.setHasThumbnail(pdf.thumbnailCreated());
        if (doc.getTitle() == null) {
            doc.setTitle(titleFromFilename(doc.getOriginalFilename()));
        }
        boolean transcribed = !ocr.text().isBlank();
        doc.setOcrUsed(transcribed);
        doc.setStatus(DocumentStatus.TEXT_ONLY);
        doc.setErrorMessage(ocr.notes().isEmpty() ? null : MsgCodec.encode(ocr.notes()));
        doc.setProcessedAt(Instant.now());
        String text = transcribed ? ocr.text().strip() : pdf.text();
        texts.save(new DocumentText(documentId, text));
        duplicates.check(doc, text);
        documents.save(doc);
        reindex(doc);
    }

    /**
     * Schreibt das Ergebnis zurück. Das Dokument wird frisch geladen, falls es zwischenzeitlich bearbeitet wurde.
     * Fehlermeldung und Hinweise werden sprachneutral gespeichert – wer sie später liest, steht hier nicht fest.
     */
    private void finish(String documentId, PdfContent pdf, AiAnalysisService.Result aiResult, Msg error,
                        BuiltinOcrService.Result ocr) {
        Document doc = documents.findById(documentId).orElse(null);
        if (doc == null) {
            return; // während der Verarbeitung gelöscht
        }
        String fullText = null;
        boolean ocrText = ocr != null && !ocr.text().isBlank();
        if (pdf != null) {
            doc.setPageCount(pdf.pageCount());
            doc.setHasThumbnail(pdf.thumbnailCreated());
            // Text der eingebauten Texterkennung bleibt, auch wenn die KI danach scheitert
            fullText = ocrText ? ocr.text().strip() : pdf.text();
            doc.setOcrUsed(ocrText);
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
            doc.setOcrUsed(transcribed || ocrText);
            if (transcribed) {
                fullText = analysis.fullText().strip();
            }
        }
        if (doc.getTitle() == null) {
            doc.setTitle(titleFromFilename(doc.getOriginalFilename()));
        }
        if (error != null) {
            doc.setStatus(DocumentStatus.FAILED);
            doc.setErrorMessage(MsgCodec.encode(error));
        } else {
            doc.setStatus(aiResult != null ? DocumentStatus.INDEXED : DocumentStatus.TEXT_ONLY);
            doc.setErrorMessage(aiResult != null ? MsgCodec.encode(aiResult.notes()) : null);
        }
        doc.setProcessedAt(Instant.now());
        if (fullText != null) {
            texts.save(new DocumentText(documentId, fullText));
            duplicates.check(doc, fullText);
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
