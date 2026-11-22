package de.aidocuments.document;

import de.aidocuments.document.DocumentDtos.DocumentDto;
import de.aidocuments.document.DocumentDtos.PageDto;
import de.aidocuments.document.DocumentDtos.UpdateDocumentRequest;
import de.aidocuments.history.HistoryEntry;
import de.aidocuments.history.HistoryService;
import de.aidocuments.i18n.Messages;
import de.aidocuments.i18n.Msg;
import de.aidocuments.search.SearchIndex;
import de.aidocuments.web.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final HistoryService history;
    private final DocumentVersions versions;
    private final DocumentShares shares;

    public DocumentService(DocumentRepository documents, DocumentTextRepository texts, FileStorage storage,
                           DocumentProcessor processor, SearchIndex searchIndex, Messages messages,
                           PdfExtractor pdfExtractor, DuplicateDetector duplicates, HistoryService history,
                           DocumentVersions versions, DocumentShares shares) {
        this.versions = versions;
        this.shares = shares;
        this.history = history;
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
        FileStorage.Received received;
        try {
            received = storage.receive(content);
        } catch (FileStorage.TooLargeException e) {
            return new IngestResult(Outcome.REJECTED, null, Msg.of("error.upload.tooLarge"));
        }
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
                // dieselbe Datei kam gleichzeitig über einen zweiten Weg herein (eindeutiger Index auf sha256)
                storage.delete(doc.getStoragePath(), id);
                Document winner = documents.findBySha256(received.sha256()).orElse(null);
                return new IngestResult(Outcome.DUPLICATE, winner, Msg.of("upload.duplicate"));
            }
            searchIndex.index(doc, "");
            history.imported(doc);
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
        HistoryEntry.Details before = HistoryService.detailsOf(doc);
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
        history.editedDetails(doc, before);
        return DocumentDto.of(doc, null, messages);
    }

    /** So lange lässt sich ein Löschen zurücknehmen; danach wird das Dokument endgültig entfernt. */
    static final Duration TRASH_RETENTION = Duration.ofDays(30);

    /**
     * Löscht ein Dokument – zunächst in den Papierkorb: Es verschwindet aus Liste, Suche und Zählern, lässt sich aber
     * über die Historie 30 Tage lang zurückholen. Danach entfernt {@link #purgeTrash()} es endgültig.
     *
     * @param asDuplicate in der Historie als „als Duplikat gelöscht“ vermerken
     */
    @Transactional
    public void delete(String id, boolean asDuplicate) {
        Document doc = require(id);
        doc.setDeletedAt(Instant.now());
        documents.saveToTrash(doc);
        documents.delete(doc);
        searchIndex.delete(id);
        duplicates.forget(id);
        history.deleted(doc, asDuplicate);
    }

    @Transactional
    public void delete(String id) {
        delete(id, false);
    }

    /** Entfernt Dokumente endgültig, die länger als 30 Tage im Papierkorb liegen – einmal je Stunde. */
    @Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
    public void purgeTrash() {
        Instant limit = Instant.now().minus(TRASH_RETENTION);
        for (Document doc : documents.findInTrashDeletedBefore(limit)) {
            try {
                storage.deleteAllFiles(doc.getStoragePath(), doc.getId());
                texts.deleteById(doc.getId());
                versions.forget(doc.getId());
                shares.forget(doc.getId());
                documents.removeFromTrash(doc.getId());
                log.info("Dokument {} endgültig gelöscht (Papierkorb)", doc.getId());
            } catch (RuntimeException e) {
                log.warn("Dokument {} konnte nicht endgültig gelöscht werden: {}", doc.getId(), e.toString());
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Rückgängig machen (aus der Historie)
    // ------------------------------------------------------------------------------------------------

    /** Ob die Daten noch da sind, um den Schritt zurückzunehmen. */
    public boolean canUndo(HistoryEntry entry) {
        return switch (entry.action()) {
            case IMPORTED -> false;
            case DELETED -> {
                Document trashed = documents.findInTrash(entry.documentId()).orElse(null);
                yield trashed != null && documents.findBySha256(trashed.getSha256()).isEmpty();
            }
            case EDITED -> {
                Document doc = documents.findById(entry.documentId()).orElse(null);
                if (doc == null || entry.change() == null) {
                    yield false;
                }
                yield switch (entry.change()) {
                    case DETAILS -> entry.before() != null;
                    case FILE, VERSION_RESTORED -> entry.previousFile() == null
                            ? doc.getOriginalStoragePath() != null
                            : Files.exists(storage.resolve(entry.previousFile()));
                    case ORIGINAL_RESTORED -> entry.previousFile() != null
                            && Files.exists(storage.resolve(entry.previousFile()));
                    case DUPLICATE -> false;
                };
            }
        };
    }

    /** Nimmt den Schritt zurück; geprüft, ob das geht, hat der Aufrufer ({@link #canUndo} und die Historie). */
    @Transactional
    public DocumentDto undo(HistoryEntry entry) throws IOException {
        if (entry.action() == HistoryEntry.Action.DELETED) {
            return restoreDeleted(entry.documentId());
        }
        Document doc = require(entry.documentId());
        switch (entry.change()) {
            case DETAILS -> {
                HistoryEntry.Details before = entry.before();
                doc.setTitle(before.title());
                doc.setSummary(before.summary());
                doc.setCategory(before.category());
                doc.setCorrespondent(before.correspondent());
                doc.setDocumentDate(before.documentDate());
                doc.replaceTags(new java.util.LinkedHashSet<>(before.tags() == null ? List.of() : before.tags()));
                documents.save(doc);
                processor.reindex(doc);
            }
            case FILE, VERSION_RESTORED -> {
                requireIdle(doc);
                versions.keepCurrent(doc, null); // auch die zurückgenommene Fassung bleibt erhalten
                if (entry.previousFile() == null) {
                    revertToOriginal(doc); // davor war es noch das Original
                } else {
                    adoptVersion(doc, entry.previousFile());
                }
                versions.added(doc, DocumentVersions.Kind.UNDONE, null);
            }
            case ORIGINAL_RESTORED -> {
                requireIdle(doc);
                versions.keepCurrent(doc, null);
                // das jetzige ist das Original: wieder beiseitelegen, dann die geänderte Fassung zurückholen
                doc.setOriginalStoragePath(storage.keepOriginal(doc.getStoragePath()));
                documents.save(doc);
                adoptVersion(doc, entry.previousFile());
                versions.added(doc, DocumentVersions.Kind.UNDONE, null);
            }
            default -> throw ApiException.conflict("error.history.notUndoable");
        }
        log.info("Historie: {} an Dokument {} rückgängig gemacht", entry.change(), doc.getId());
        return DocumentDto.of(doc, null, messages);
    }

    private DocumentDto restoreDeleted(String id) {
        Document doc = documents.findInTrash(id).orElse(null);
        if (doc == null) {
            throw ApiException.conflict("error.history.notUndoable");
        }
        if (documents.findBySha256(doc.getSha256()).isPresent()) {
            throw ApiException.conflict("error.history.alreadyExists");
        }
        doc.setDeletedAt(null);
        String text = texts.findById(id).map(DocumentText::content).orElse("");
        duplicates.check(doc, text);
        documents.save(doc);
        documents.removeFromTrash(id);
        processor.reindex(doc);
        log.info("Dokument {} aus dem Papierkorb zurückgeholt", id);
        return DocumentDto.of(doc, null, messages);
    }

    private static void requireIdle(Document doc) {
        if (doc.getStatus() == DocumentStatus.PENDING || doc.getStatus() == DocumentStatus.PROCESSING) {
            throw ApiException.conflict("error.document.alreadyProcessing");
        }
    }

    /** Macht eine aufbewahrte Fassung wieder zur aktuellen; die Kopie selbst wird danach nicht mehr gebraucht. */
    private void adoptVersion(Document doc, String version) throws IOException {
        Path thumbnail = storage.temporaryFile(".jpg");
        try {
            PdfExtractor.PdfContent pdf;
            try {
                pdf = pdfExtractor.extract(storage.resolve(version), thumbnail);
            } catch (PdfExtractor.PdfReadException e) {
                throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, e.msg());
            }
            storage.restore(version, doc.getStoragePath());
            adoptRendering(doc, pdf, thumbnail);
            doc.setSizeBytes(Files.size(storage.resolve(doc.getStoragePath())));
            doc.setModifiedAt(Instant.now());
            documents.save(doc);
            // die Kopie bleibt: Sie ist eine Fassung des Dokuments (siehe DocumentVersions)
            processor.reindex(doc);
        } finally {
            Files.deleteIfExists(thumbnail);
        }
    }

    public DocumentDto notDuplicate(String id) {
        Document doc = require(id);
        duplicates.dismiss(doc);
        documents.save(doc);
        processor.reindex(doc);
        return DocumentDto.of(doc, null, messages);
    }

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
        FileStorage.Received received;
        try {
            received = storage.receive(body);
        } catch (FileStorage.TooLargeException e) {
            throw ApiException.of(org.springframework.http.HttpStatus.CONTENT_TOO_LARGE, "error.upload.tooLarge");
        }
        Path thumbnail = storage.temporaryFile(".jpg");
        try {
            if (received.size() == 0 || !storage.looksLikePdf(received)) {
                throw ApiException.badRequest("upload.notPdf");
            }
            PdfExtractor.PdfContent pdf;
            try {
                pdf = pdfExtractor.extract(received.tempFile(), thumbnail);
            } catch (PdfExtractor.PdfReadException e) {
                throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, e.msg());
            }
            // die Fassung davor aufbewahren, damit sich die Änderung zurücknehmen lässt (beim ersten Mal: das Original)
            String previous = null;
            if (doc.getOriginalStoragePath() == null) {
                String original = storage.keepOriginal(doc.getStoragePath());
                versions.keepCurrent(doc, original);
                doc.setOriginalStoragePath(original);
                documents.save(doc); // erst die Kopie festhalten, dann die Datei ersetzen
            } else {
                previous = storage.keepVersion(doc.getStoragePath());
                versions.keepCurrent(doc, previous);
            }
            storage.replace(doc.getStoragePath(), received);
            adoptRendering(doc, pdf, thumbnail);
            doc.setSizeBytes(received.size());
            doc.setModifiedAt(Instant.now());
            documents.save(doc);
            versions.added(doc, DocumentVersions.Kind.EDITED, null);
            processor.reindex(doc);
            log.info("PDF von Dokument {} geändert, Original aufbewahrt unter {}", id, doc.getOriginalStoragePath());
            history.editedFile(doc, HistoryEntry.Change.FILE, previous);
            return DocumentDto.of(doc, null, messages);
        } finally {
            storage.discard(received);
            Files.deleteIfExists(thumbnail);
        }
    }

    /** Stellt das aufbewahrte Original wieder her; die geänderte Fassung bleibt für ein Rückgängig aufbewahrt. */
    public DocumentDto restoreOriginal(String id) throws IOException {
        Document doc = require(id);
        if (doc.getOriginalStoragePath() == null) {
            throw ApiException.notFound("error.document.fileNotFound");
        }
        requireIdle(doc);
        String previous = storage.keepVersion(doc.getStoragePath());
        versions.keepCurrent(doc, previous);
        revertToOriginal(doc);
        versions.added(doc, DocumentVersions.Kind.ORIGINAL_RESTORED, 1);
        history.editedFile(doc, HistoryEntry.Change.ORIGINAL_RESTORED, previous);
        return DocumentDto.of(doc, null, messages);
    }

    /** Macht das aufbewahrte Original wieder zur aktuellen Fassung; die Kopie wird danach nicht mehr gebraucht. */
    private void revertToOriginal(Document doc) throws IOException {
        String id = doc.getId();
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
            // die Kopie des Originals bleibt: Sie ist die erste Fassung des Dokuments (siehe DocumentVersions)
            processor.reindex(doc);
            log.info("Original von Dokument {} wiederhergestellt", id);
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
            doc.setThumbnailAt(storage.thumbnailWrittenAt(doc.getId()));
        }
        if (!doc.isOcrUsed()) {
            texts.save(new DocumentText(doc.getId(), pdf.text()));
        }
    }

    public List<DocumentVersions.Version> versions(String id) {
        return versions.list(require(id));
    }

    public Path versionFile(String id, int number) {
        return versions.file(require(id), number);
    }

    /**
     * Macht eine ältere Fassung wieder zur aktuellen – als neue Fassung; die bisherige bleibt erhalten und der Schritt
     * lässt sich über die Historie zurücknehmen.
     */
    @Transactional
    public DocumentDto restoreVersion(String id, int number) throws IOException {
        Document doc = require(id);
        requireIdle(doc);
        DocumentVersions.Version target = versions.list(doc).stream().filter(v -> v.number() == number).findFirst()
                .orElseThrow(() -> ApiException.notFound("error.document.versionNotFound"));
        if (target.current()) {
            throw ApiException.conflict("error.document.versionIsCurrent");
        }
        Path thumbnail = storage.temporaryFile(".jpg");
        try {
            PdfExtractor.PdfContent pdf;
            try {
                pdf = pdfExtractor.extract(storage.resolve(target.storagePath()), thumbnail);
            } catch (PdfExtractor.PdfReadException e) {
                throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, e.msg());
            }
            String previous = versions.keepCurrent(doc, null);
            if (doc.getOriginalStoragePath() == null) {
                // bisher unverändert: die erste Fassung ist das Original
                doc.setOriginalStoragePath(versions.list(doc).getFirst().storagePath());
            }
            storage.restore(target.storagePath(), doc.getStoragePath());
            adoptRendering(doc, pdf, thumbnail);
            doc.setSizeBytes(Files.size(storage.resolve(doc.getStoragePath())));
            doc.setModifiedAt(Instant.now());
            documents.save(doc);
            versions.added(doc, DocumentVersions.Kind.VERSION_RESTORED, number);
            processor.reindex(doc);
            history.editedFile(doc, HistoryEntry.Change.VERSION_RESTORED, previous);
            log.info("Fassung {} von Dokument {} wiederhergestellt", number, id);
            return DocumentDto.of(doc, null, messages);
        } finally {
            Files.deleteIfExists(thumbnail);
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
