package de.aidocuments.document;

import de.aidocuments.document.DocumentDtos.DocumentDto;
import de.aidocuments.document.DocumentDtos.PageDto;
import de.aidocuments.document.DocumentDtos.UpdateDocumentRequest;
import de.aidocuments.i18n.Messages;
import de.aidocuments.i18n.Msg;
import de.aidocuments.search.SearchIndex;
import de.aidocuments.web.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    public enum Outcome {
        CREATED, DUPLICATE, REJECTED
    }

    public record MailOrigin(String accountId, String subject, String from, Instant receivedAt) {
    }

    /** @param message warum nichts Neues entstand – sprachneutral; {@code null} bei {@link Outcome#CREATED} */
    public record IngestResult(Outcome outcome, Document document, Msg message) {
    }

    private final DocumentRepository documents;
    private final DocumentTextRepository texts;
    private final FileStorage storage;
    private final DocumentProcessor processor;
    private final SearchIndex searchIndex;
    private final Messages messages;

    private final PdfExtractor pdfExtractor;
    private final DuplicateDetector duplicates;

    public DocumentService(DocumentRepository documents, DocumentTextRepository texts, FileStorage storage,
                           DocumentProcessor processor, SearchIndex searchIndex, Messages messages,
                           PdfExtractor pdfExtractor, DuplicateDetector duplicates) {
        this.duplicates = duplicates;
        this.messages = messages;
        this.pdfExtractor = pdfExtractor;
        this.documents = documents;
        this.texts = texts;
        this.storage = storage;
        this.processor = processor;
        this.searchIndex = searchIndex;
    }

    /**
     * Nimmt ein PDF entgegen (E-Mail-Anhang, Eingangsordner oder Upload), speichert es und stößt die
     * Verarbeitung an. Inhaltsgleiche Dateien werden über ihre Prüfsumme erkannt und nicht doppelt abgelegt.
     */
    public IngestResult ingest(InputStream content, String filename, DocumentSource source, MailOrigin origin)
            throws IOException {
        String cleanName = sanitizeFilename(filename);
        FileStorage.Received received = storage.receive(content);
        try {
            if (received.size() == 0 || !storage.looksLikePdf(received)) {
                return new IngestResult(Outcome.REJECTED, null, Msg.of("upload.notPdf"));
            }
            Document existing = documents.findBySha256(received.sha256()).orElse(null);
            if (existing != null) {
                return new IngestResult(Outcome.DUPLICATE, existing, Msg.of("upload.duplicate"));
            }
            String id = UUID.randomUUID().toString();
            Document doc = new Document(id, cleanName, storage.commit(received, id), received.sha256(),
                    received.size(), source);
            doc.setTitle(DocumentProcessor.titleFromFilename(cleanName));
            if (origin != null) {
                doc.setMailOrigin(origin.accountId(), origin.subject(), origin.from(), origin.receivedAt());
            }
            try {
                documents.save(doc);
            } catch (DuplicateKeyException e) {
                // dieselbe Datei kam gleichzeitig über einen zweiten Weg herein
                storage.delete(doc.getStoragePath(), id);
                Document winner = documents.findBySha256(received.sha256()).orElse(null);
                return new IngestResult(Outcome.DUPLICATE, winner, Msg.of("upload.duplicate"));
            }
            searchIndex.index(doc, "");
            processor.enqueue(id);
            log.info("Neues Dokument {} ({}, {} Bytes)", cleanName, source, received.size());
            return new IngestResult(Outcome.CREATED, doc, null);
        } finally {
            storage.discard(received);
        }
    }

    public PageDto<DocumentDto> search(SearchIndex.Criteria criteria) {
        SearchIndex.Result result = searchIndex.search(criteria);
        List<String> ids = result.hits().stream().map(SearchIndex.Hit::id).toList();
        Map<String, Document> byId = documents.findAllById(ids).stream()
                .collect(Collectors.toMap(Document::getId, Function.identity()));
        List<DocumentDto> items = result.hits().stream()
                .map(hit -> {
                    Document doc = byId.get(hit.id());
                    return doc == null ? null : DocumentDto.of(doc, hit.snippet(), messages);
                })
                .filter(Objects::nonNull)
                .toList();
        return new PageDto<>(items, result.total(), criteria.page(), criteria.size());
    }

    public Document require(String id) {
        return documents.findById(id).orElseThrow(() -> ApiException.notFound("error.document.notFound"));
    }

    public DocumentDto get(String id) {
        return DocumentDto.of(require(id), null, messages);
    }

    public String text(String id) {
        require(id);
        return texts.findById(id).map(DocumentText::content).orElse("");
    }

    public DocumentDto update(String id, UpdateDocumentRequest request) {
        Document doc = require(id);
        if (request.title() != null && !request.title().isBlank()) {
            doc.setTitle(request.title().strip());
        }
        doc.setSummary(blankToNull(request.summary()));
        doc.setCategory(blankToNull(request.category()));
        doc.setCorrespondent(blankToNull(request.correspondent()));
        doc.setDocumentDate(request.documentDate());
        doc.replaceTags(DocumentProcessor.normalizeTags(request.tags()));
        documents.save(doc);
        processor.reindex(doc);
        return DocumentDto.of(doc, null, messages);
    }

    public void delete(String id) {
        Document doc = require(id);
        documents.delete(doc);
        texts.deleteById(id);
        searchIndex.delete(id);
        storage.delete(doc.getStoragePath(), doc.getOriginalStoragePath(), id);
        duplicates.forget(id);
    }

    public DocumentDto notDuplicate(String id) {
        Document doc = require(id);
        duplicates.dismiss(doc);
        documents.save(doc);
        processor.reindex(doc);
        return DocumentDto.of(doc, null, messages);
    }

    /** Größte Datei, die als geänderte Fassung angenommen wird – wie beim Hochladen gilt ein großzügiges Limit. */
    private static final long MAX_EDITED_BYTES = 250L * 1024 * 1024;

    /**
     * Ersetzt das PDF durch eine geänderte Fassung (etwa mit Markierungen oder Notizen aus dem Betrachter). Beim
     * ersten Mal wird das Original als Kopie aufbewahrt – es geht nie verloren und lässt sich wiederherstellen.
     * Titel, Schlagworte und übrige Angaben bleiben; Text und Suchindex folgen der neuen Fassung, außer der Text
     * stammt aus der Texterkennung (die ein geändertes PDF nicht besser machen würde).
     */
    public DocumentDto replaceFile(String id, InputStream body) throws IOException {
        Document doc = require(id);
        if (doc.getStatus() == DocumentStatus.PENDING || doc.getStatus() == DocumentStatus.PROCESSING) {
            throw ApiException.conflict("error.document.alreadyProcessing");
        }
        FileStorage.Received received = storage.receive(body);
        Path thumbnail = storage.temporaryFile(".jpg");
        try {
            if (received.size() > MAX_EDITED_BYTES) {
                throw ApiException.badRequest("error.upload.tooLarge");
            }
            if (received.size() == 0 || !storage.looksLikePdf(received)) {
                throw ApiException.badRequest("upload.notPdf");
            }
            PdfExtractor.PdfContent pdf;
            try {
                pdf = pdfExtractor.extract(received.tempFile(), thumbnail);
            } catch (PdfExtractor.PdfReadException e) {
                throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, e.msg());
            }
            if (doc.getOriginalStoragePath() == null) {
                doc.setOriginalStoragePath(storage.keepOriginal(doc.getStoragePath()));
                documents.save(doc); // erst die Kopie festhalten, dann die Datei ersetzen
            }
            storage.replace(doc.getStoragePath(), received);
            adoptRendering(doc, pdf, thumbnail);
            doc.setSizeBytes(received.size());
            doc.setModifiedAt(Instant.now());
            documents.save(doc);
            processor.reindex(doc);
            log.info("PDF von Dokument {} geändert, Original aufbewahrt unter {}", id, doc.getOriginalStoragePath());
            return DocumentDto.of(doc, null, messages);
        } finally {
            storage.discard(received);
            Files.deleteIfExists(thumbnail);
        }
    }

    /** Stellt das aufbewahrte Original wieder her; die geänderte Fassung wird verworfen. */
    public DocumentDto restoreOriginal(String id) throws IOException {
        Document doc = require(id);
        if (doc.getOriginalStoragePath() == null) {
            throw ApiException.notFound("error.document.fileNotFound");
        }
        if (doc.getStatus() == DocumentStatus.PENDING || doc.getStatus() == DocumentStatus.PROCESSING) {
            throw ApiException.conflict("error.document.alreadyProcessing");
        }
        Path thumbnail = storage.temporaryFile(".jpg");
        try {
            PdfExtractor.PdfContent pdf;
            try {
                pdf = pdfExtractor.extract(storage.resolve(doc.getOriginalStoragePath()), thumbnail);
            } catch (PdfExtractor.PdfReadException e) {
                throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, e.msg());
            }
            storage.restore(doc.getOriginalStoragePath(), doc.getStoragePath());
            adoptRendering(doc, pdf, thumbnail);
            doc.setSizeBytes(Files.size(storage.resolve(doc.getStoragePath())));
            String copy = doc.getOriginalStoragePath();
            doc.setOriginalStoragePath(null);
            doc.setModifiedAt(null);
            documents.save(doc);
            Files.deleteIfExists(storage.resolve(copy)); // die Datei ist jetzt selbst wieder das Original
            processor.reindex(doc);
            log.info("Original von Dokument {} wiederhergestellt", id);
            return DocumentDto.of(doc, null, messages);
        } finally {
            Files.deleteIfExists(thumbnail);
        }
    }

    /** Seitenzahl, Vorschaubild und – bei Text aus der Textebene – den Text der neuen Fassung übernehmen. */
    private void adoptRendering(Document doc, PdfExtractor.PdfContent pdf, Path thumbnail) throws IOException {
        doc.setPageCount(pdf.pageCount());
        if (pdf.thumbnailCreated()) {
            Files.move(thumbnail, storage.thumbnailPath(doc.getId()), StandardCopyOption.REPLACE_EXISTING);
            doc.setHasThumbnail(true);
        }
        if (!doc.isOcrUsed()) {
            texts.save(new DocumentText(doc.getId(), pdf.text()));
        }
    }

    public Path originalFile(String id) {
        Document doc = require(id);
        if (doc.getOriginalStoragePath() == null) {
            throw ApiException.notFound("error.document.fileNotFound");
        }
        Path path = storage.resolve(doc.getOriginalStoragePath());
        if (!Files.exists(path)) {
            throw ApiException.notFound("error.document.fileNotFound");
        }
        return path;
    }

    public DocumentDto reprocess(String id) {
        Document doc = require(id);
        if (doc.getStatus() == DocumentStatus.PENDING || doc.getStatus() == DocumentStatus.PROCESSING) {
            throw ApiException.conflict("error.document.alreadyProcessing");
        }
        doc.setStatus(DocumentStatus.PENDING);
        doc.setErrorMessage(null);
        documents.save(doc);
        processor.reindex(doc);
        processor.enqueue(id);
        return DocumentDto.of(doc, null, messages);
    }

    /** Schickt alle Dokumente erneut durch die Pipeline, die (noch) nicht von der KI erschlossen sind. */
    public int reprocessUnanalyzed() {
        List<Document> candidates = documents.findByStatusIn(
                List.of(DocumentStatus.TEXT_ONLY, DocumentStatus.FAILED));
        candidates.forEach(doc -> reprocess(doc.getId()));
        return candidates.size();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    static String sanitizeFilename(String filename) {
        String name = filename == null ? "" : filename;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("\\p{Cntrl}", "").strip();
        if (name.isEmpty()) {
            name = "Dokument.pdf";
        }
        if (name.length() > 200) {
            name = name.substring(0, 196) + ".pdf";
        }
        return name;
    }
}
