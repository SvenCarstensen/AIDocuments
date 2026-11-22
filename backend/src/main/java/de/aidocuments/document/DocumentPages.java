package de.aidocuments.document;

import de.aidocuments.document.DocumentDtos.DocumentDto;
import de.aidocuments.web.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Seiten bearbeiten: umsortieren, drehen und entfernen, ein Dokument ab einer Seite in zwei trennen, andere Dokumente
 * anhängen. Jede Änderung ergibt eine neue Fassung (die bisherige bleibt erhalten und lässt sich wiederherstellen);
 * Titel, Schlagworte und übrige Angaben bleiben.
 *
 * <p>Stammt der Text aus der Texterkennung, passt er nach dem Umsortieren nicht mehr zu den Seiten – er wird dann im
 * Hintergrund neu erkannt. Beim Anhängen ergibt er sich aus den Texten der Teile.
 */
@Component
public class DocumentPages {

    private static final Logger log = LoggerFactory.getLogger(DocumentPages.class);

    /** Mehr Seiten nimmt keine Anfrage entgegen. */
    static final int MAX_PAGES = 5000;
    /** Höchstens so viele Dokumente lassen sich auf einmal anhängen. */
    static final int MAX_MERGED = 50;

    /** Eine Seite der neuen Fassung: welche bisherige (ab 1) und um wie viel Grad im Uhrzeigersinn gedreht. */
    public record PageSpec(int page, int rotate) {
    }

    private final DocumentService service;
    private final DocumentTextRepository texts;
    private final DocumentRepository documents;
    private final FileStorage storage;
    private final DocumentProcessor processor;
    private final PdfExtractor extractor;

    public DocumentPages(DocumentService service, DocumentTextRepository texts, DocumentRepository documents,
                         FileStorage storage, DocumentProcessor processor, PdfExtractor extractor) {
        this.service = service;
        this.texts = texts;
        this.documents = documents;
        this.storage = storage;
        this.processor = processor;
        this.extractor = extractor;
    }

    /** Die neue Seitenfolge; nicht genannte Seiten fallen weg. Jede bisherige Seite höchstens einmal. */
    public DocumentDto rearrange(String id, List<PageSpec> pages) throws IOException {
        Document doc = editable(id);
        if (pages == null || pages.isEmpty() || pages.size() > MAX_PAGES) {
            throw ApiException.badRequest("error.pages.noneLeft");
        }
        Set<Integer> seen = new HashSet<>();
        for (PageSpec spec : pages) {
            if (spec.rotate() % 90 != 0 || !seen.add(spec.page())) {
                throw ApiException.badRequest("error.pages.invalid");
            }
        }
        Path result = storage.temporaryFile(".pdf");
        try {
            try (PDDocument pdf = open(doc)) {
                int count = pdf.getNumberOfPages();
                if (pages.stream().anyMatch(p -> p.page() < 1 || p.page() > count)) {
                    throw ApiException.badRequest("error.pages.invalid");
                }
                if (unchanged(pages, count)) {
                    return service.get(id);
                }
                List<PDPage> all = new ArrayList<>();
                pdf.getPages().forEach(all::add);
                PDPageTree tree = pdf.getPages();
                all.forEach(tree::remove);
                for (PageSpec spec : pages) {
                    PDPage page = all.get(spec.page() - 1);
                    page.setRotation(Math.floorMod(page.getRotation() + spec.rotate(), 360));
                    tree.add(page);
                }
                save(pdf, result);
            }
            DocumentDto updated = replace(id, result, DocumentVersions.Kind.PAGES);
            refreshText(doc);
            log.info("Seiten von Dokument {} neu angeordnet ({} Seiten)", id, pages.size());
            return updated;
        } finally {
            Files.deleteIfExists(result);
        }
    }

    public record SplitResult(DocumentDto original, String createdId) {
    }

    /**
     * Trennt das Dokument vor der Seite {@code fromPage}: Diese und alle folgenden Seiten werden ein neues Dokument im
     * selben Bereich (mit Herkunft wie das Original), das wie ein neu eingegangenes verarbeitet wird.
     */
    public SplitResult split(String id, int fromPage) throws IOException {
        Document doc = editable(id);
        Path first = storage.temporaryFile(".pdf");
        Path second = storage.temporaryFile(".pdf");
        try {
            try (PDDocument pdf = open(doc)) {
                int count = pdf.getNumberOfPages();
                if (fromPage < 2 || fromPage > count) {
                    throw ApiException.badRequest("error.pages.splitInvalid");
                }
                List<PDPage> all = new ArrayList<>();
                pdf.getPages().forEach(all::add);
                // erst der hintere Teil (vorne entfernen), dann der vordere (hinten entfernen)
                all.subList(0, fromPage - 1).forEach(pdf.getPages()::remove);
                save(pdf, second);
            }
            try (PDDocument pdf = open(doc)) {
                List<PDPage> all = new ArrayList<>();
                pdf.getPages().forEach(all::add);
                all.subList(fromPage - 1, all.size()).forEach(pdf.getPages()::remove);
                save(pdf, first);
            }
            String name = doc.getOriginalFilename().replaceFirst("(?i)\\.pdf$", "") + " (2).pdf";
            DocumentService.MailOrigin origin = doc.getMailAccountId() == null && doc.getMailSubject() == null ? null
                    : new DocumentService.MailOrigin(doc.getMailAccountId(), doc.getMailSubject(), doc.getMailFrom(),
                    doc.getMailReceivedAt());
            DocumentService.IngestResult created;
            try (InputStream in = Files.newInputStream(second)) {
                created = service.ingest(in, name, doc.getSource(), origin, doc.getAreaId());
            }
            if (created.outcome() != DocumentService.Outcome.CREATED) {
                // genau diese Seiten gibt es schon als Dokument – dann bleibt das Original unverändert
                throw ApiException.conflict("error.pages.splitExists");
            }
            DocumentDto original = replace(id, first, DocumentVersions.Kind.PAGES);
            refreshText(doc);
            log.info("Dokument {} ab Seite {} getrennt, neuer Teil {}", id, fromPage, created.document().getId());
            return new SplitResult(original, created.document().getId());
        } finally {
            Files.deleteIfExists(first);
            Files.deleteIfExists(second);
        }
    }

    /**
     * Hängt die Seiten der anderen Dokumente in dieser Reihenfolge an. Die angehängten Dokumente kommen danach in den
     * Papierkorb – das verlangt das Recht zum Löschen, und keines darf unter einer Aufbewahrungsfrist stehen.
     */
    public DocumentDto merge(String id, List<String> otherIds) throws IOException {
        Document doc = editable(id);
        if (otherIds == null || otherIds.isEmpty() || otherIds.size() > MAX_MERGED
                || new HashSet<>(otherIds).size() != otherIds.size() || otherIds.contains(id)) {
            throw ApiException.badRequest("error.pages.mergeInvalid");
        }
        List<Document> others = new ArrayList<>();
        for (String otherId : otherIds) {
            Document other = service.requireDeletable(otherId);
            requireIdle(other);
            if (other.isRetentionLocked()) {
                throw ApiException.conflict("error.pages.mergeLocked", other.getTitle(), other.getRetainUntil());
            }
            others.add(other);
        }
        Path result = storage.temporaryFile(".pdf");
        try {
            try (PDDocument target = open(doc)) {
                PDFMergerUtility merger = new PDFMergerUtility();
                List<PDDocument> opened = new ArrayList<>();
                try {
                    for (Document other : others) {
                        PDDocument source = open(other);
                        opened.add(source);
                        merger.appendDocument(target, source);
                    }
                    if (target.getNumberOfPages() > MAX_PAGES) {
                        throw ApiException.badRequest("error.pages.tooMany", MAX_PAGES);
                    }
                    save(target, result);
                } finally {
                    for (PDDocument source : opened) {
                        source.close();
                    }
                }
            }
            boolean ocr = doc.isOcrUsed() || others.stream().anyMatch(Document::isOcrUsed);
            StringBuilder text = new StringBuilder(texts.findById(id).map(DocumentText::content).orElse(""));
            for (Document other : others) {
                String part = texts.findById(other.getId()).map(DocumentText::content).orElse("");
                if (!part.isBlank()) {
                    text.append(text.isEmpty() ? "" : "\n\n").append(part);
                }
            }
            DocumentDto updated = replace(id, result, DocumentVersions.Kind.MERGED);
            if (ocr) {
                // Erkannte Texte stehen nicht im PDF – die der Teile ergeben zusammen den des Ganzen.
                Document current = documents.findById(id).orElseThrow();
                current.setOcrUsed(true);
                documents.save(current);
                texts.save(new DocumentText(id, text.toString()));
                processor.reindex(current);
                updated = service.get(id);
            }
            for (Document other : others) {
                service.delete(other.getId(), false);
            }
            log.info("{} Dokumente an Dokument {} angehängt", others.size(), id);
            return updated;
        } finally {
            Files.deleteIfExists(result);
        }
    }

    /** Eine Seite als kleines Bild für die Seitenübersicht; {@code null}, wenn es die Seite nicht gibt. */
    public byte[] pageImage(String id, int page, int width) throws IOException {
        Document doc = service.require(id);
        return extractor.renderPageAsJpeg(storage.resolve(doc.getStoragePath()), page - 1,
                Math.clamp(width, 60, 800));
    }

    private Document editable(String id) {
        Document doc = service.requireEditable(id);
        requireIdle(doc);
        return doc;
    }

    private static void requireIdle(Document doc) {
        if (doc.getStatus() == DocumentStatus.PENDING || doc.getStatus() == DocumentStatus.PROCESSING) {
            throw ApiException.conflict("error.document.alreadyProcessing");
        }
    }

    private PDDocument open(Document doc) {
        try {
            return PdfExtractor.load(storage.resolve(doc.getStoragePath()));
        } catch (IOException e) {
            log.warn("PDF von Dokument {} nicht lesbar: {}", doc.getId(), e.toString());
            throw ApiException.badRequest("error.pages.unreadable");
        }
    }

    private static void save(PDDocument pdf, Path target) throws IOException {
        // ein Kennwort zum Ändern (ohne Kennwort zum Öffnen) würde das Speichern der neuen Fassung verhindern
        pdf.setAllSecurityToBeRemoved(true);
        pdf.save(target.toFile());
    }

    private DocumentDto replace(String id, Path file, DocumentVersions.Kind kind) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return service.replaceFile(id, in, kind);
        }
    }

    /** Erkannter Text gehört zu den bisherigen Seiten – neu erkennen lassen. */
    private void refreshText(Document doc) {
        if (doc.isOcrUsed()) {
            processor.retranscribe(doc.getId());
        }
    }

    private static boolean unchanged(List<PageSpec> pages, int count) {
        if (pages.size() != count) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            if (pages.get(i).page() != i + 1 || Math.floorMod(pages.get(i).rotate(), 360) != 0) {
                return false;
            }
        }
        return true;
    }
}
