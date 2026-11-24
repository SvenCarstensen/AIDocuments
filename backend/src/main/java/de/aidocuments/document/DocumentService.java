package de.aidocuments.document;

import de.aidocuments.area.AreaAccess;
import de.aidocuments.area.Permission;
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
        /** IGNORED: bewusst übergangen, kein Fehler – etwa ein kleines Bild (Logo) in einer E-Mail */
        CREATED, DUPLICATE, REJECTED, IGNORED
    }

    /** Kürzeste Bildseite, ab der ein Bild aus einer E-Mail übernommen wird – kleinere sind Logos und Symbole. */
    static final int MIN_MAIL_IMAGE_SIDE = 500;

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
    private final ImageToPdf imageToPdf;
    private final DuplicateDetector duplicates;
    private final HistoryService history;
    private final DocumentVersions versions;
    private final DocumentShares shares;
    private final AreaAccess access;

    private final de.aidocuments.reminder.ReminderRepository reminders;
    private final de.aidocuments.contract.ContractRepository contracts;
    private final de.aidocuments.einvoice.EInvoicePdf eInvoicePdf;

    public DocumentService(DocumentRepository documents, DocumentTextRepository texts, FileStorage storage,
                           DocumentProcessor processor, SearchIndex searchIndex, Messages messages,
                           PdfExtractor pdfExtractor, DuplicateDetector duplicates, HistoryService history,
                           DocumentVersions versions, DocumentShares shares, AreaAccess access,
                           de.aidocuments.reminder.ReminderRepository reminders, ImageToPdf imageToPdf,
                           de.aidocuments.contract.ContractRepository contracts,
                           de.aidocuments.einvoice.EInvoicePdf eInvoicePdf) {
        this.contracts = contracts;
        this.eInvoicePdf = eInvoicePdf;
        this.imageToPdf = imageToPdf;
        this.reminders = reminders;
        this.access = access;
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
     * Nimmt ein PDF oder ein Foto entgegen (E-Mail-Anhang, Eingangsordner oder Upload), speichert es und stößt die
     * Verarbeitung an. Fotos (JPEG, PNG, HEIC) werden dabei zu einem einseitigen PDF. Inhaltsgleiche Dateien werden
     * über ihre Prüfsumme erkannt und nicht doppelt abgelegt.
     *
     * @param areaId der Bereich, in den das Dokument kommt – geprüft hat ihn der Aufrufer
     */
    public IngestResult ingest(InputStream content, String filename, DocumentSource source, MailOrigin origin,
                               String areaId) throws IOException {
        String cleanName = sanitizeFilename(filename);
        FileStorage.Received received;
        FileStorage.Received pdf = null;
        try {
            received = storage.receive(content);
        } catch (FileStorage.TooLargeException e) {
            return new IngestResult(Outcome.REJECTED, null, Msg.of("error.upload.tooLarge"));
        }
        try {
            if (received.size() == 0) {
                return new IngestResult(Outcome.REJECTED, null, Msg.of("upload.notSupported"));
            }
            boolean isPdf = storage.looksLikePdf(received);
            ImageToPdf.Kind image = isPdf ? null : ImageToPdf.detect(received.tempFile());
            boolean xml = !isPdf && image == null && de.aidocuments.einvoice.EInvoiceReader.looksLikeXml(
                    received.tempFile());
            if (!isPdf && image == null && !xml) {
                return new IngestResult(Outcome.REJECTED, null, Msg.of("upload.notSupported"));
            }
            Document existing = documents.findBySha256(received.sha256()).orElse(null);
            if (existing != null) {
                return new IngestResult(Outcome.DUPLICATE, existing, Msg.of("upload.duplicate"));
            }
            FirstLook look = isPdf ? firstLook(received.tempFile()) : null;
            if (look != null && look.pages() > DocumentPages.MAX_PAGES) {
                return new IngestResult(Outcome.REJECTED, null, Msg.of("error.pages.tooMany", DocumentPages.MAX_PAGES));
            }
            de.aidocuments.einvoice.EInvoice early = look == null ? null : look.invoice();
            if (xml) {
                early = de.aidocuments.einvoice.EInvoiceReader.fromXml(received.tempFile());
            }
            Document sameInvoice = sameInvoice(early, areaId);
            if (sameInvoice != null) {
                // dieselbe Rechnung kam schon – als ZUGFeRD-PDF und XML-Datei in derselben E-Mail, oder in anderer
                // Reihenfolge (erst die XML-Datei, dann das PDF)
                return new IngestResult(Outcome.DUPLICATE, sameInvoice, Msg.of("upload.duplicate"));
            }
            if (xml) {
                de.aidocuments.einvoice.EInvoice invoice = early;
                if (invoice == null) {
                    // andere XML-Dateien in E-Mails (Signaturen, Formulardaten …) sind kein Fehler
                    return source == DocumentSource.MAIL ? new IngestResult(Outcome.IGNORED, null, null)
                            : new IngestResult(Outcome.REJECTED, null, Msg.of("upload.xmlNotEInvoice"));
                }
                early = invoice;
                Path converted = storage.tempSibling(received, ".pdf");
                eInvoicePdf.render(invoice, Files.readAllBytes(received.tempFile()), xmlName(cleanName),
                        source == DocumentSource.UPLOAD ? messages.locale() : labelLocale(invoice), converted);
                pdf = storage.converted(received, converted);
                cleanName = asPdfName(cleanName);
                log.info("E-Rechnung {} ({}) als PDF dargestellt", cleanName, invoice.syntax());
            } else if (image != null) {
                Path converted = storage.tempSibling(received, ".pdf");
                try {
                    imageToPdf.convert(received.tempFile(), image, converted,
                            source == DocumentSource.MAIL ? MIN_MAIL_IMAGE_SIDE : 0);
                } catch (ImageToPdf.TooSmall e) {
                    Files.deleteIfExists(converted);
                    return new IngestResult(Outcome.IGNORED, null, null);
                } catch (ImageToPdf.Rejected e) {
                    Files.deleteIfExists(converted);
                    return new IngestResult(Outcome.REJECTED, null, Msg.of(e.messageKey()));
                }
                pdf = storage.converted(received, converted);
                cleanName = asPdfName(cleanName);
                log.info("Bild {} ({}) in ein PDF umgewandelt", cleanName, image);
            }
            return store(pdf != null ? pdf : received, cleanName, source, origin, areaId, null, early);
        } finally {
            storage.discard(received);
            if (pdf != null) {
                storage.discard(pdf);
            }
        }
    }

    /** Dieselbe E-Rechnung im selben Bereich (siehe {@code EInvoice.identity}) – oder {@code null}. */
    private Document sameInvoice(de.aidocuments.einvoice.EInvoice invoice, String areaId) {
        String key = invoice == null ? null : invoice.identity();
        return key == null ? null : documents.findByEInvoiceKey(key, areaId).orElse(null);
    }

    /** Öffnet eine Datei (erneut) zum Lesen. */
    @FunctionalInterface
    public interface Opener {
        InputStream open() throws IOException;
    }

    /**
     * Ein PDF und eine XML-Rechnung, die zusammen kamen (dieselbe E-Mail, derselbe Upload, derselbe Name im
     * Eingangsordner). Ist das PDF dieselbe Rechnung – es trägt noch keine eingebettete und nennt ihre Nummer –, kommt
     * die XML-Datei als Anhang hinein: ein Dokument, das aussieht wie vom Absender und die exakten Angaben trägt. Das
     * PDF bleibt dabei Byte für Byte erhalten (der Anhang wird angefügt, nichts umgeschrieben).
     *
     * @return {@code null}, wenn beides nicht zusammengehört – dann nimmt der Aufrufer die Dateien einzeln auf
     */
    public IngestResult ingestPair(Opener pdfFile, String pdfName, Opener xmlFile, String xmlName,
                                   DocumentSource source, MailOrigin origin, String areaId) throws IOException {
        FileStorage.Received xml = null;
        FileStorage.Received pdf = null;
        Path attached = null;
        try {
            try (InputStream in = xmlFile.open()) {
                xml = storage.receive(in);
            } catch (FileStorage.TooLargeException e) {
                return null;
            }
            if (xml.size() == 0 || xml.size() > de.aidocuments.einvoice.EInvoiceReader.MAX_XML_BYTES
                    || !de.aidocuments.einvoice.EInvoiceReader.looksLikeXml(xml.tempFile())) {
                return null;
            }
            byte[] xmlBytes = Files.readAllBytes(xml.tempFile());
            de.aidocuments.einvoice.EInvoice invoice = de.aidocuments.einvoice.EInvoiceReader.parse(xmlBytes);
            if (invoice == null || invoice.number() == null) {
                return null;
            }
            try (InputStream in = pdfFile.open()) {
                pdf = storage.receive(in);
            } catch (FileStorage.TooLargeException e) {
                return null;
            }
            if (pdf.size() == 0 || !storage.looksLikePdf(pdf) || documents.findBySha256(pdf.sha256()).isPresent()) {
                return null; // ein schon vorhandenes PDF meldet die Aufnahme einzeln als Duplikat
            }
            attached = storage.tempSibling(pdf, ".pdf");
            try (org.apache.pdfbox.pdmodel.PDDocument document = PdfExtractor.load(pdf.tempFile())) {
                if (document.isEncrypted() || !mentions(document, invoice.number())
                        || !de.aidocuments.einvoice.EInvoiceReader.attachTo(document, xmlBytes,
                        xmlName(sanitizeFilename(xmlName)))) {
                    return null;
                }
                try (java.io.OutputStream out = Files.newOutputStream(attached)) {
                    document.saveIncremental(out);
                }
            } catch (IOException | RuntimeException e) {
                log.warn("XML-Rechnung ließ sich nicht an {} anhängen: {}", pdfName, e.toString());
                return null;
            }
            Document same = sameInvoice(invoice, areaId);
            if (same != null) {
                return new IngestResult(Outcome.DUPLICATE, same, Msg.of("upload.duplicate"));
            }
            // die Prüfsumme bleibt die des PDFs: Kommt es noch einmal allein, ist es ein Duplikat
            FileStorage.Received combined = storage.converted(pdf, attached);
            attached = null;
            storage.discard(pdf);
            pdf = combined;
            log.info("XML-Rechnung {} an {} angehängt", xmlName, pdfName);
            return store(combined, sanitizeFilename(pdfName), source, origin, areaId, null, invoice);
        } finally {
            if (xml != null) {
                storage.discard(xml);
            }
            if (pdf != null) {
                storage.discard(pdf);
            }
            if (attached != null) {
                Files.deleteIfExists(attached);
            }
        }
    }

    /** Ob die Rechnungsnummer im Text der ersten Seiten steht (Leerraum und Groß-/Kleinschreibung egal). */
    private static boolean mentions(org.apache.pdfbox.pdmodel.PDDocument pdf, String number) {
        try {
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            stripper.setEndPage(Math.min(pdf.getNumberOfPages(), 10));
            String text = stripper.getText(pdf).replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
            String wanted = number.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
            return !wanted.isEmpty() && text.contains(wanted);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Unter mehreren Dateien genau ein PDF und genau eine XML-Datei (nach Dateiendung)? Dann ihre Stellen
     * {@code [pdf, xml]}, sonst {@code null} – bei mehr Dateien wäre nicht klar, welche zusammengehören.
     */
    public static int[] invoicePair(List<String> filenames) {
        int pdf = -1;
        int xml = -1;
        for (int i = 0; i < filenames.size(); i++) {
            String name = filenames.get(i) == null ? "" : filenames.get(i).toLowerCase(java.util.Locale.ROOT);
            if (name.endsWith(".pdf")) {
                if (pdf >= 0) {
                    return null;
                }
                pdf = i;
            } else if (name.endsWith(".xml")) {
                if (xml >= 0) {
                    return null;
                }
                xml = i;
            }
        }
        return pdf >= 0 && xml >= 0 ? new int[] {pdf, xml} : null;
    }

    /** Eine Datei, die zusammen mit anderen hereinkommt. */
    public record Upload(String filename, InputStream content) {
    }

    /**
     * Ein PDF mit mitgebrachten Angaben aufnehmen (Import eines Exports): Titel, Kategorie, Schlagworte usw. stammen aus
     * dem Export, nicht von der KI. Der Text wird aus dem PDF gelesen, sofern {@code knownText} ihn nicht mitbringt.
     */
    IngestResult ingestImported(InputStream content, String filename, DocumentSource source, MailOrigin origin,
                                String areaId, java.util.function.Consumer<Document> details, String knownText,
                                boolean ocrUsed) throws IOException {
        String cleanName = asPdfName(sanitizeFilename(filename));
        FileStorage.Received received;
        try {
            received = storage.receive(content);
        } catch (FileStorage.TooLargeException e) {
            return new IngestResult(Outcome.REJECTED, null, Msg.of("error.upload.tooLarge"));
        }
        try {
            if (received.size() == 0 || !storage.looksLikePdf(received)) {
                return new IngestResult(Outcome.REJECTED, null, Msg.of("upload.notSupported"));
            }
            Document existing = documents.findBySha256(received.sha256()).orElse(null);
            if (existing != null) {
                return new IngestResult(Outcome.DUPLICATE, existing, Msg.of("upload.duplicate"));
            }
            IngestResult result = store(received, cleanName, source, origin, areaId, details, null);
            if (result.outcome() == Outcome.CREATED) {
                processor.processImported(result.document().getId(), knownText, ocrUsed);
            }
            return result;
        } finally {
            storage.discard(received);
        }
    }

    /**
     * Fasst mehrere Fotos (etwa die abfotografierten Seiten eines Briefs) in dieser Reihenfolge zu einem Dokument mit je
     * einer Seite zusammen. Alle müssen Bilder sein. Als dieselben Fotos gilt dieselbe Folge – sie wird kein zweites Mal
     * abgelegt.
     */
    public IngestResult ingestCombined(List<Upload> uploads, DocumentSource source, String areaId) throws IOException {
        List<FileStorage.Received> received = new java.util.ArrayList<>();
        Path combined = null;
        FileStorage.Received pdf = null;
        try {
            List<ImageToPdf.Image> images = new java.util.ArrayList<>();
            java.security.MessageDigest digest = sha256();
            for (Upload upload : uploads) {
                FileStorage.Received file;
                try {
                    file = storage.receive(upload.content());
                } catch (FileStorage.TooLargeException e) {
                    return new IngestResult(Outcome.REJECTED, null, Msg.of("error.upload.tooLarge"));
                }
                received.add(file);
                ImageToPdf.Kind kind = file.size() == 0 ? null : ImageToPdf.detect(file.tempFile());
                if (kind == null) {
                    return new IngestResult(Outcome.REJECTED, null, Msg.of("upload.combineImagesOnly"));
                }
                images.add(new ImageToPdf.Image(file.tempFile(), kind));
                digest.update(file.sha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
            String sha = java.util.HexFormat.of().formatHex(digest.digest());
            Document existing = documents.findBySha256(sha).orElse(null);
            if (existing != null) {
                return new IngestResult(Outcome.DUPLICATE, existing, Msg.of("upload.duplicate"));
            }
            combined = storage.tempSibling(received.getFirst(), ".pdf");
            try {
                imageToPdf.convertAll(images, combined, 0);
            } catch (ImageToPdf.TooSmall e) {
                throw new IllegalStateException(e); // ohne Untergrenze nicht möglich
            } catch (ImageToPdf.Rejected e) {
                return new IngestResult(Outcome.REJECTED, null, Msg.of(e.messageKey()));
            }
            pdf = new FileStorage.Received(combined, sha, Files.size(combined));
            String name = asPdfName(sanitizeFilename(uploads.getFirst().filename()));
            log.info("{} Fotos zu einem PDF zusammengefasst ({})", images.size(), name);
            return store(pdf, name, source, null, areaId);
        } finally {
            received.forEach(storage::discard);
            if (pdf != null) {
                storage.discard(pdf);
            } else if (combined != null) {
                Files.deleteIfExists(combined);
            }
        }
    }

    private static java.security.MessageDigest sha256() {
        try {
            return java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Legt eine empfangene (oder umgewandelte) PDF-Datei als neues Dokument ab und reiht es zur Verarbeitung ein. */
    private IngestResult store(FileStorage.Received stored, String cleanName, DocumentSource source, MailOrigin origin,
                               String areaId) throws IOException {
        return store(stored, cleanName, source, origin, areaId, null, null);
    }

    /**
     * @param details mitgebrachte Angaben (Import) – dann keine Analyse, sondern {@link DocumentProcessor#processImported}
     * @param invoice die E-Rechnung, schon beim Ablegen gelesen: So erkennt eine gleich danach eintreffende XML-Datei
     *                derselben Rechnung (oft im selben E-Mail) sie, bevor die Verarbeitung fertig ist
     */
    private IngestResult store(FileStorage.Received stored, String cleanName, DocumentSource source, MailOrigin origin,
                               String areaId, java.util.function.Consumer<Document> details,
                               de.aidocuments.einvoice.EInvoice invoice) throws IOException {
        String id = UUID.randomUUID().toString();
        Document doc = new Document(id, cleanName, storage.commit(stored, id), stored.sha256(),
                stored.size(), source);
        doc.setTitle(DocumentProcessor.titleFromFilename(cleanName));
        doc.setAreaId(areaId);
        doc.setEInvoice(invoice);
        if (origin != null) {
            doc.setMailOrigin(origin.accountId(), origin.subject(), origin.from(), origin.receivedAt());
        }
        if (details != null) {
            details.accept(doc);
        }
        try {
            documents.save(doc);
        } catch (DuplicateKeyException e) {
            // dieselbe Datei kam gleichzeitig über einen zweiten Weg herein (eindeutiger Index auf sha256)
            storage.delete(doc.getStoragePath(), id);
            Document winner = documents.findBySha256(stored.sha256()).orElse(null);
            return new IngestResult(Outcome.DUPLICATE, winner, Msg.of("upload.duplicate"));
        }
        searchIndex.index(doc, "");
        history.imported(doc);
        if (details == null) {
            processor.enqueue(id);
        }
        log.info("Neues Dokument {} ({}, {} Bytes)", cleanName, source, stored.size());
        return new IngestResult(Outcome.CREATED, doc, null);
    }

    public PageDto<DocumentDto> search(SearchIndex.Criteria requested) {
        return search(requested, false);
    }

    /** @param retentionExpired nur Dokumente, deren Aufbewahrungsfrist abgelaufen ist (die jetzt gelöscht werden dürfen) */
    public PageDto<DocumentDto> search(SearchIndex.Criteria requested, boolean retentionExpired) {
        SearchIndex.Criteria criteria = effective(requested, retentionExpired, requested.page(), requested.size());
        SearchIndex.Result result = searchIndex.search(criteria);
        List<String> ids = result.hits().stream().map(SearchIndex.Hit::id).toList();
        Map<String, Document> byId = documents.findAllById(ids).stream()
                .collect(Collectors.toMap(Document::getId, Function.identity()));
        Map<String, Permission> permissions = access.permissions();
        List<DocumentDto> items = result.hits().stream()
                .map(hit -> {
                    Document doc = byId.get(hit.id());
                    return doc == null ? null : DocumentDto.of(doc, hit.snippet(), messages,
                            permissions == null ? Permission.DELETE : permissions.get(doc.getAreaId()));
                })
                .filter(Objects::nonNull)
                .toList();
        return new PageDto<>(items, result.total(), criteria.page(), criteria.size());
    }

    /** Alle Treffer der Suche ohne Seitenaufteilung, in derselben Reihenfolge – für den Export. */
    public List<String> matchingIds(SearchIndex.Criteria requested, boolean retentionExpired) {
        int all = (int) Math.clamp(documents.count(), 1, 1_000_000);
        return searchIndex.search(effective(requested, retentionExpired, 0, all)).hits().stream()
                .map(SearchIndex.Hit::id).toList();
    }

    private SearchIndex.Criteria effective(SearchIndex.Criteria requested, boolean retentionExpired, int page, int size) {
        // die sichtbaren Bereiche setzt immer der Server – nie die Anfrage
        java.util.Set<String> visible = access.visible();
        return new SearchIndex.Criteria(requested.text(), requested.category(),
                requested.correspondent(), requested.tags(), requested.status(), requested.from(), requested.to(),
                requested.sort(), page, size, requested.duplicates(), requested.area(),
                visible, retentionExpired ? documents.idsWithRetentionExpired(java.time.LocalDate.now(), visible) : null);
    }

    /** Das Dokument, wenn es existiert und in einem für den Anfragenden sichtbaren Bereich liegt – sonst 404. */
    public Document require(String id) {
        return documents.findById(id).filter(doc -> access.canSee(doc.getAreaId()))
                .orElseThrow(() -> ApiException.notFound("error.document.notFound"));
    }

    /** Wie {@link #require}, aber nur, wenn der Anfragende das Dokument ändern darf – sonst 403. */
    public Document requireEditable(String id) {
        Document doc = require(id);
        access.require(doc.getAreaId(), Permission.EDIT);
        return doc;
    }

    /** Wie {@link #require}, aber nur, wenn der Anfragende das Dokument löschen darf – sonst 403. */
    public Document requireDeletable(String id) {
        Document doc = require(id);
        access.require(doc.getAreaId(), Permission.DELETE);
        return doc;
    }

    private DocumentDto dto(Document doc) {
        return DocumentDto.of(doc, null, messages, access.permission(doc.getAreaId()));
    }

    public DocumentDto get(String id) {
        return dto(require(id));
    }

    public String text(String id) {
        require(id);
        return texts.findById(id).map(DocumentText::content).orElse("");
    }

    public DocumentDto update(String id, UpdateDocumentRequest request) {
        Document doc = requireEditable(id);
        HistoryEntry.Details before = HistoryService.detailsOf(doc);
        if (request.title() != null && !request.title().isBlank()) {
            doc.setTitle(request.title().strip());
        }
        doc.setSummary(blankToNull(request.summary()));
        doc.setCategory(blankToNull(request.category()));
        doc.setCorrespondent(blankToNull(request.correspondent()));
        doc.setDocumentDate(request.documentDate());
        doc.replaceTags(DocumentProcessor.normalizeTags(request.tags()));
        boolean moved = request.areaId() != null && !request.areaId().equals(doc.getAreaId());
        if (moved) {
            // aus dem Bereich heraus verschwindet es dort wie gelöscht – das verlangt das Recht zum Löschen
            // (wie beim Zusammenführen, siehe DocumentPages.merge)
            access.require(doc.getAreaId(), Permission.DELETE);
            access.requireTarget(request.areaId());
            moveToArea(doc, request.areaId());
        }
        documents.save(doc);
        processor.reindex(doc);
        history.editedDetails(doc, before);
        return dto(doc);
    }

    /**
     * Legt das Dokument in einen anderen Bereich. Duplikat-Vermerke gelten nur innerhalb eines Bereichs – sonst zeigte
     * die Oberfläche auf ein Dokument, das der Leser nicht sehen darf; sie werden daher neu ermittelt. Der Verlauf zieht
     * mit um.
     */
    private void moveToArea(Document doc, String areaId) {
        doc.setAreaId(areaId);
        duplicates.forget(doc.getId());
        doc.setDuplicateOf(null);
        duplicates.check(doc, texts.findById(doc.getId()).map(DocumentText::content).orElse(""));
        history.moveDocument(doc.getId(), areaId);
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
        Document doc = requireDeletable(id);
        if (doc.isRetentionLocked()) {
            // Die Sperre hebt niemand auf – auch kein Administrator.
            throw ApiException.conflict("error.retention.locked", doc.getRetainUntil());
        }
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
            if (doc.isRetentionLocked()) {
                // erst nach Ablauf der Frist; bis dahin bleibt es im Papierkorb (und lässt sich zurückholen)
                continue;
            }
            try {
                storage.deleteAllFiles(doc.getStoragePath(), doc.getId());
                texts.deleteById(doc.getId());
                versions.forget(doc.getId());
                shares.forget(doc.getId());
                reminders.forgetDocument(doc.getId());
                contracts.delete(doc.getId());
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
        Document doc = requireEditable(entry.documentId());
        switch (entry.change()) {
            case DETAILS -> {
                HistoryEntry.Details before = entry.before();
                doc.setTitle(before.title());
                doc.setSummary(before.summary());
                doc.setCategory(before.category());
                doc.setCorrespondent(before.correspondent());
                doc.setDocumentDate(before.documentDate());
                doc.replaceTags(new java.util.LinkedHashSet<>(before.tags() == null ? List.of() : before.tags()));
                if (before.areaId() != null && !before.areaId().equals(doc.getAreaId())
                        && access.allows(before.areaId(), Permission.EDIT)
                        && access.allows(doc.getAreaId(), Permission.DELETE)) {
                    moveToArea(doc, before.areaId());
                }
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
        return dto(doc);
    }

    private DocumentDto restoreDeleted(String id) {
        Document doc = documents.findInTrash(id).orElse(null);
        if (doc == null) {
            throw ApiException.conflict("error.history.notUndoable");
        }
        access.require(doc.getAreaId(), Permission.DELETE);
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
        return dto(doc);
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
        Document doc = requireEditable(id);
        duplicates.dismiss(doc);
        documents.save(doc);
        processor.reindex(doc);
        return dto(doc);
    }

    /**
     * Ersetzt das PDF durch eine geänderte Fassung (etwa mit Markierungen oder Notizen aus dem Betrachter). Beim
     * ersten Mal wird das Original als Kopie aufbewahrt – es geht nie verloren und lässt sich wiederherstellen.
     * Titel, Schlagworte und übrige Angaben bleiben; Text und Suchindex folgen der neuen Fassung, außer der Text
     * stammt aus der Texterkennung (die ein geändertes PDF nicht besser machen würde).
     */
    public DocumentDto replaceFile(String id, InputStream body) throws IOException {
        return replaceFile(id, body, DocumentVersions.Kind.EDITED);
    }

    /** @param kind wie die neue Fassung in der Liste der Fassungen heißt */
    DocumentDto replaceFile(String id, InputStream body, DocumentVersions.Kind kind) throws IOException {
        Document doc = requireEditable(id);
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
            versions.added(doc, kind, null);
            processor.reindex(doc);
            log.info("PDF von Dokument {} geändert, Original aufbewahrt unter {}", id, doc.getOriginalStoragePath());
            history.editedFile(doc, HistoryEntry.Change.FILE, previous);
            return dto(doc);
        } finally {
            storage.discard(received);
            Files.deleteIfExists(thumbnail);
        }
    }

    /** Stellt das aufbewahrte Original wieder her; die geänderte Fassung bleibt für ein Rückgängig aufbewahrt. */
    public DocumentDto restoreOriginal(String id) throws IOException {
        Document doc = requireEditable(id);
        if (doc.getOriginalStoragePath() == null) {
            throw ApiException.notFound("error.document.fileNotFound");
        }
        requireIdle(doc);
        String previous = storage.keepVersion(doc.getStoragePath());
        versions.keepCurrent(doc, previous);
        revertToOriginal(doc);
        versions.added(doc, DocumentVersions.Kind.ORIGINAL_RESTORED, 1);
        history.editedFile(doc, HistoryEntry.Change.ORIGINAL_RESTORED, previous);
        return dto(doc);
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

    /** Seitenzahl, Vorschaubild, E-Rechnung und – bei Text aus der Textebene – den Text der neuen Fassung übernehmen. */
    private void adoptRendering(Document doc, PdfExtractor.PdfContent pdf, Path thumbnail) throws IOException {
        doc.setPageCount(pdf.pageCount());
        doc.setEInvoice(pdf.eInvoice()); // die Angaben folgen der Datei (etwa nach dem Wiederherstellen einer Fassung)
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
        Document doc = requireEditable(id);
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
            return dto(doc);
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
        Document doc = requireEditable(id);
        if (doc.getStatus() == DocumentStatus.PENDING || doc.getStatus() == DocumentStatus.PROCESSING) {
            throw ApiException.conflict("error.document.alreadyProcessing");
        }
        doc.setStatus(DocumentStatus.PENDING);
        doc.setErrorMessage(null);
        documents.save(doc);
        processor.reindex(doc);
        processor.enqueue(id);
        return dto(doc);
    }

    /** Schickt alle Dokumente erneut durch die Pipeline, die (noch) nicht von der KI erschlossen sind. */
    public int reprocessUnanalyzed() {
        List<Document> candidates = documents.findByStatusIn(
                List.of(DocumentStatus.TEXT_ONLY, DocumentStatus.FAILED));
        candidates.forEach(doc -> reprocess(doc.getId()));
        return candidates.size();
    }

    /** Die eingebettete E-Rechnung eines PDFs; {@code null}, wenn keine darin ist oder das PDF nicht lesbar ist. */
    /** Was beim Ablegen schon aus dem PDF gelesen wird; {@code pages} -1 = unlesbar. */
    private record FirstLook(de.aidocuments.einvoice.EInvoice invoice, int pages) {
    }

    /** Seitenzahl und eingebettete E-Rechnung – in einem Durchgang, geladen mit den Prüfungen gegen „Zip-Bomben“. */
    private static FirstLook firstLook(Path pdf) {
        try (org.apache.pdfbox.pdmodel.PDDocument document = PdfExtractor.load(pdf)) {
            de.aidocuments.einvoice.EInvoiceReader.Read read = null;
            try {
                read = de.aidocuments.einvoice.EInvoiceReader.fromPdf(document);
            } catch (RuntimeException e) {
                // ohne lesbare E-Rechnung weiter
            }
            return new FirstLook(read == null ? null : read.invoice(), document.getNumberOfPages());
        } catch (IOException | RuntimeException e) {
            return new FirstLook(null, -1); // die Verarbeitung meldet, was mit dem PDF nicht stimmt
        }
    }

    /** Der Name der XML-Datei im PDF: der empfangene, mit Endung .xml. */
    static String xmlName(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).endsWith(".xml") ? name : name + ".xml";
    }

    /**
     * Sprache der lesbaren Darstellung, wenn niemand Bestimmtes fragt (E-Mail, Eingangsordner): Deutsch für Rechnungen
     * aus dem deutschen Sprachraum, sonst Englisch.
     */
    static java.util.Locale labelLocale(de.aidocuments.einvoice.EInvoice invoice) {
        java.util.Set<String> german = java.util.Set.of("DE", "AT", "CH", "LI", "LU");
        for (de.aidocuments.einvoice.EInvoice.Party party : java.util.Arrays.asList(invoice.seller(), invoice.buyer())) {
            if (party != null && party.country() != null
                    && german.contains(party.country().strip().toUpperCase(java.util.Locale.ROOT))) {
                return java.util.Locale.GERMAN;
            }
        }
        return java.util.Locale.ENGLISH;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** "Quittung.HEIC" → "Quittung.pdf", "xrechnung-4711.xml" → "xrechnung-4711.pdf" */
    static String asPdfName(String name) {
        String base = name.replaceFirst("(?i)\\.(jpe?g|png|hei[cf]|xml)$", "");
        return (base.isBlank() ? "Foto" : base) + ".pdf";
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
