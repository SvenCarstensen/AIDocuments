package de.aidocuments.document;

import de.aidocuments.document.DocumentDtos.DocumentDto;
import de.aidocuments.document.DocumentDtos.FacetsDto;
import de.aidocuments.document.DocumentDtos.PageDto;
import de.aidocuments.document.DocumentDtos.TextDto;
import de.aidocuments.document.DocumentDtos.UpdateDocumentRequest;
import de.aidocuments.document.DocumentDtos.UploadResultDto;
import de.aidocuments.ai.AiRequestLimiter;
import de.aidocuments.i18n.Messages;
import de.aidocuments.search.SearchIndex;
import de.aidocuments.user.UserPrincipal;
import de.aidocuments.web.ApiException;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService service;
    private final DocumentFacets facets;
    private final FileStorage storage;
    private final Messages messages;

    private final DocumentChatService chat;
    private final AiSearchService aiSearch;

    private final AiRequestLimiter aiLimiter;
    private final de.aidocuments.area.AreaAccess areaAccess;
    private final DocumentExport export;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DocumentController.class);

    public DocumentController(DocumentService service, DocumentFacets facets, FileStorage storage,
                              Messages messages, DocumentChatService chat, AiSearchService aiSearch,
                              AiRequestLimiter aiLimiter, de.aidocuments.area.AreaAccess areaAccess,
                              DocumentExport export) {
        this.export = export;
        this.areaAccess = areaAccess;
        this.aiLimiter = aiLimiter;
        this.chat = chat;
        this.aiSearch = aiSearch;
        this.messages = messages;
        this.service = service;
        this.facets = facets;
        this.storage = storage;
    }

    /**
     * Liste und Suche in einem: ohne {@code q} die neuesten Dokumente, mit {@code q} eine Volltextsuche
     * über Inhalt, Titel, Schlagworte, Absender und Kerndaten. Alle Filter lassen sich kombinieren.
     */
    private static final int MAX_QUERY_LENGTH = 300;

    @GetMapping
    public PageDto<DocumentDto> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String correspondent,
            @RequestParam(required = false, name = "tag") List<String> tags,
            @RequestParam(required = false) DocumentStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean duplicates,
            @RequestParam(required = false) String area,
            /* "expired" = nur Dokumente, deren Aufbewahrungsfrist abgelaufen ist */
            @RequestParam(required = false) String retention,
            @RequestParam(defaultValue = "relevance") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size) {
        return service.search(criteria(q, category, correspondent, tags, status, from, to, duplicates, area, sort,
                page, size), "expired".equals(retention));
    }

    public record ExportTicketDto(String token, int count) {
    }

    /**
     * Export, Schritt 1: merkt sich alle Treffer mit denselben Filtern wie die Liste. Wer die Zwei-Faktor-Anmeldung
     * nutzt, braucht dafür einen frischen Code (siehe {@code AdminTwoFactorFilter}).
     */
    @PostMapping("/export")
    public ExportTicketDto prepareExport(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String correspondent,
            @RequestParam(required = false, name = "tag") List<String> tags,
            @RequestParam(required = false) DocumentStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean duplicates,
            @RequestParam(required = false) String area,
            @RequestParam(required = false) String retention,
            @RequestParam(defaultValue = "relevance") String sort,
            jakarta.servlet.http.HttpServletRequest request) {
        List<String> ids = service.matchingIds(criteria(q, category, correspondent, tags, status, from, to, duplicates,
                area, sort, 0, 1), "expired".equals(retention));
        if (ids.isEmpty()) {
            throw ApiException.badRequest("error.export.empty");
        }
        return new ExportTicketDto(export.prepare(request.getSession(), ids), ids.size());
    }

    /** Export, Schritt 2: der Download selbst – ein gewöhnlicher Seitenaufruf, der Schlüssel gilt einmal. */
    @GetMapping("/export/{token}")
    public void downloadExport(@PathVariable String token, jakarta.servlet.http.HttpServletRequest request,
                               jakarta.servlet.http.HttpServletResponse response) throws IOException {
        List<String> ids = export.take(request.getSession(false), token);
        if (ids == null) {
            throw ApiException.notFound("error.export.notFound");
        }
        // erneut prüfen: Bereichsrechte können sich seit Schritt 1 geändert haben
        List<Document> selected = export.load(ids).stream().filter(d -> areaAccess.canSee(d.getAreaId())).toList();
        String filename = "AIDocuments-Export-" + LocalDate.now() + ".zip";
        log.info("Export von {} Dokumenten durch {}", selected.size(), currentUser());
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(filename).build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        export.write(selected, response.getOutputStream());
    }

    private static String currentUser() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "?" : auth.getName();
    }

    private static SearchIndex.Criteria criteria(String q, String category, String correspondent, List<String> tags,
                                                 DocumentStatus status, LocalDate from, LocalDate to,
                                                 boolean duplicates, String area, String sort, int page, int size) {
        SearchIndex.SortOrder order = switch (sort) {
            case "newest" -> SearchIndex.SortOrder.NEWEST;
            case "date" -> SearchIndex.SortOrder.DOCUMENT_DATE;
            default -> SearchIndex.SortOrder.RELEVANCE;
        };
        // längere Suchtexte ergeben riesige Abfragen (je Wort mehrere Felder) – ohne Nutzen für die Suche
        if (q != null && q.length() > MAX_QUERY_LENGTH) {
            q = q.substring(0, MAX_QUERY_LENGTH);
        }
        return new SearchIndex.Criteria(q, category, correspondent,
                tags == null ? List.of() : tags, status, from, to, order,
                Math.clamp(page, 0, 10_000), Math.clamp(size, 1, 100), duplicates,
                area == null || area.isBlank() ? null : area, null, null);
    }

    @GetMapping("/facets")
    public FacetsDto facets() {
        return facets.all();
    }

    @GetMapping("/{id}")
    public DocumentDto get(@PathVariable String id) {
        return service.get(id);
    }

    @GetMapping("/{id}/text")
    public TextDto text(@PathVariable String id) {
        return new TextDto(service.text(id));
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> file(@PathVariable String id,
                                         @RequestParam(defaultValue = "false") boolean download) {
        Document doc = service.require(id);
        Path path = storage.resolve(doc.getStoragePath());
        if (!Files.exists(path)) {
            throw ApiException.notFound("error.document.fileNotFound");
        }
        ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(doc.getOriginalFilename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(new FileSystemResource(path));
    }

    /** Speichert eine geänderte Fassung des PDFs; das Original bleibt als Kopie erhalten. */
    @PutMapping(value = "/{id}/file", consumes = MediaType.APPLICATION_PDF_VALUE)
    public DocumentDto replaceFile(@PathVariable String id, jakarta.servlet.http.HttpServletRequest request)
            throws java.io.IOException {
        return service.replaceFile(id, request.getInputStream());
    }

    /** Das aufbewahrte Original eines geänderten PDFs – immer als Download. */
    @GetMapping("/{id}/original")
    public ResponseEntity<Resource> original(@PathVariable String id) {
        Document doc = service.require(id);
        Path path = service.originalFile(id);
        String name = doc.getOriginalFilename().replaceFirst("(?i)\\.pdf$", "") + " (Original).pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .cacheControl(CacheControl.noCache().cachePrivate())
                .body(new FileSystemResource(path));
    }

    /**
     * Die XML-Datei der E-Rechnung, so wie sie im PDF liegt (bei XRechnungen: die empfangene Datei, unverändert) –
     * immer als Download, nie im Browser dargestellt.
     */
    @GetMapping("/{id}/e-invoice")
    public ResponseEntity<byte[]> eInvoiceXml(@PathVariable String id) throws java.io.IOException {
        Document doc = service.require(id);
        Path path = storage.resolve(doc.getStoragePath());
        de.aidocuments.einvoice.EInvoiceReader.Read read = Files.exists(path)
                ? de.aidocuments.einvoice.EInvoiceReader.fromPdf(path) : null;
        if (read == null) {
            throw ApiException.notFound("error.einvoice.notFound");
        }
        String name = read.xml().filename().replaceAll("[\\\\/\\p{Cntrl}]", "_");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .cacheControl(CacheControl.noCache().cachePrivate())
                .body(read.xml().content());
    }

    public record ChatMessage(
            @jakarta.validation.constraints.NotNull boolean fromUser,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 8000) String text) {
    }

    public record ChatRequest(
            @jakarta.validation.constraints.NotEmpty @jakarta.validation.constraints.Size(max = 60)
            List<@Valid ChatMessage> messages) {
    }

    public record ChatAnswer(String answer) {
    }

    /** Fragen an ein Dokument – beantwortet von der eingerichteten KI anhand von Angaben und Volltext. */
    @PostMapping("/{id}/chat")
    public ChatAnswer chat(@PathVariable String id, @Valid @RequestBody ChatRequest request,
                           @AuthenticationPrincipal UserPrincipal principal) {
        return aiLimiter.run(principal.id(), () -> {
            try {
                return new ChatAnswer(chat.answer(id, request.messages().stream()
                        .map(m -> new de.aidocuments.ai.AiAnalysisService.ChatTurn(m.fromUser(), m.text())).toList()));
            } catch (de.aidocuments.ai.AiException e) {
                throw aiFailure(e);
            }
        });
    }

    public record AskRequest(@jakarta.validation.constraints.NotBlank
                             @jakarta.validation.constraints.Size(max = 500) String question) {
    }

    /** KI-Suche: übersetzt eine Frage in natürlicher Sprache in Filter; gesucht wird danach wie gewohnt. */
    @PostMapping("/ask")
    public AiSearchService.Interpretation ask(@Valid @RequestBody AskRequest request,
                                              @AuthenticationPrincipal UserPrincipal principal) {
        return aiLimiter.run(principal.id(), () -> {
            try {
                return aiSearch.interpret(request.question());
            } catch (de.aidocuments.ai.AiException e) {
                throw aiFailure(e);
            }
        });
    }

    /** Ohne eingerichtete KI ein Konflikt (die Funktion ist nicht verfügbar), sonst ein Fehler beim KI-Server. */
    private static ApiException aiFailure(de.aidocuments.ai.AiException e) {
        HttpStatus status = "ai.error.notConfigured".equals(e.msg().key()) ? HttpStatus.CONFLICT : HttpStatus.BAD_GATEWAY;
        return new ApiException(status, e.msg());
    }

    /** Der Anwender bestätigt: kein Duplikat. */
    @PostMapping("/{id}/not-duplicate")
    public DocumentDto notDuplicate(@PathVariable String id) {
        return service.notDuplicate(id);
    }

    /** Eine Fassung der PDF-Datei, wie die Oberfläche sie anzeigt. */
    public record VersionDto(int number, DocumentVersions.Kind kind, Integer restoredFrom, java.time.Instant createdAt,
                             String createdBy, Long sizeBytes, Integer pageCount, boolean current) {
    }

    /** Alle Fassungen der PDF-Datei, die neueste zuerst. */
    @GetMapping("/{id}/versions")
    public List<VersionDto> versions(@PathVariable String id) {
        Document doc = service.require(id);
        return service.versions(id).reversed().stream()
                .map(v -> new VersionDto(v.number(), v.kind(), v.restoredFrom(), v.createdAt(), v.createdBy(),
                        v.current() ? Long.valueOf(doc.getSizeBytes()) : v.sizeBytes(),
                        v.current() ? doc.getPageCount() : v.pageCount(), v.current()))
                .toList();
    }

    /** Die Datei einer Fassung – zum Ansehen oder ({@code download=true}) zum Herunterladen. */
    @GetMapping("/{id}/versions/{number}/file")
    public ResponseEntity<Resource> versionFile(@PathVariable String id, @PathVariable int number,
                                                @RequestParam(defaultValue = "false") boolean download) {
        Document doc = service.require(id);
        Path path = service.versionFile(id, number);
        String name = doc.getOriginalFilename().replaceFirst("(?i)\\.pdf$", "") + " (" + messages.get(
                "document.versionFileSuffix", number) + ").pdf";
        ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(name, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .cacheControl(CacheControl.noCache().cachePrivate())
                .body(new FileSystemResource(path));
    }

    /** Macht eine ältere Fassung wieder zur aktuellen (als neue Fassung). */
    @PostMapping("/{id}/versions/{number}/restore")
    public DocumentDto restoreVersion(@PathVariable String id, @PathVariable int number) throws IOException {
        return service.restoreVersion(id, number);
    }

    @PostMapping("/{id}/restore-original")
    public DocumentDto restoreOriginal(@PathVariable String id) throws java.io.IOException {
        return service.restoreOriginal(id);
    }

    @GetMapping("/{id}/thumbnail")
    public ResponseEntity<Resource> thumbnail(@PathVariable String id) {
        service.require(id);
        Path path = storage.thumbnailPath(id);
        if (!Files.exists(path)) {
            throw ApiException.notFound("error.document.thumbnailNotFound");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(new FileSystemResource(path));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<UploadResultDto> upload(@RequestParam("files") List<MultipartFile> files,
                                        @RequestParam(required = false) String area,
                                        @RequestParam(defaultValue = "false") boolean combine) {
        String areaId = area == null || area.isBlank() ? areaAccess.defaultArea() : area;
        areaAccess.requireTarget(areaId);
        if (combine && files.size() > 1) {
            return List.of(uploadCombined(files, areaId));
        }
        List<UploadResultDto> results = new ArrayList<>();
        boolean storageFull = false;
        // ein PDF und eine XRechnung zusammen hochgeladen: meist dieselbe Rechnung – dann ein Dokument
        List<String> names = files.stream().map(MultipartFile::getOriginalFilename).toList();
        int[] pair = DocumentService.invoicePair(names);
        DocumentService.IngestResult paired = null;
        if (pair != null) {
            MultipartFile pdf = files.get(pair[0]);
            MultipartFile xml = files.get(pair[1]);
            try {
                paired = service.ingestPair(pdf::getInputStream, names.get(pair[0]), xml::getInputStream,
                        names.get(pair[1]), DocumentSource.UPLOAD, null, areaId);
            } catch (IOException | FileStorage.StorageFullException e) {
                paired = null; // dann einzeln – bei voller Platte lehnt die Schleife unten alle ab
                storageFull = e instanceof FileStorage.StorageFullException;
            }
        }
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            String name = file.getOriginalFilename() != null ? file.getOriginalFilename() : "Dokument.pdf";
            if (paired != null && (i == pair[0] || i == pair[1])) {
                // die XML-Datei steckt jetzt im PDF: nur einmal zählen
                DocumentService.IngestResult result = i == pair[0] ? paired
                        : new DocumentService.IngestResult(DocumentService.Outcome.IGNORED, paired.document(), null);
                results.add(new UploadResultDto(name, result.outcome().name(),
                        result.document() != null && areaAccess.canSee(result.document().getAreaId())
                                ? result.document().getId() : null,
                        result.message() != null ? messages.render(result.message()) : null));
                continue;
            }
            if (storageFull) {
                results.add(new UploadResultDto(name, DocumentService.Outcome.REJECTED.name(), null,
                        messages.get("upload.storeFailed")));
                continue;
            }
            try (InputStream in = file.getInputStream()) {
                DocumentService.IngestResult result = service.ingest(in, name, DocumentSource.UPLOAD, null, areaId);
                // ein gleiches Dokument in einem fremden Bereich nicht verraten (nur dass es die Datei schon gibt)
                results.add(new UploadResultDto(name, result.outcome().name(),
                        result.document() != null && areaAccess.canSee(result.document().getAreaId())
                                ? result.document().getId() : null,
                        result.message() != null ? messages.render(result.message()) : null));
            } catch (IOException | FileStorage.StorageFullException e) {
                // Platte (fast) voll: diese und alle weiteren Dateien ablehnen, die schon gespeicherten bleiben
                storageFull = e instanceof FileStorage.StorageFullException;
                results.add(new UploadResultDto(name, DocumentService.Outcome.REJECTED.name(), null,
                        messages.get("upload.storeFailed")));
            }
        }
        return results;
    }

    /** Mehrere Fotos als ein Dokument mit je einer Seite – ein Ergebnis für alle. */
    private UploadResultDto uploadCombined(List<MultipartFile> files, String areaId) {
        String name = files.getFirst().getOriginalFilename() != null ? files.getFirst().getOriginalFilename() : "Foto.jpg";
        if (files.size() > ImageToPdf.MAX_COMBINED) {
            return new UploadResultDto(name, DocumentService.Outcome.REJECTED.name(), null,
                    messages.get("upload.tooManyImages"));
        }
        List<InputStream> streams = new ArrayList<>();
        try {
            List<DocumentService.Upload> uploads = new ArrayList<>();
            for (MultipartFile file : files) {
                InputStream in = file.getInputStream();
                streams.add(in);
                uploads.add(new DocumentService.Upload(file.getOriginalFilename(), in));
            }
            DocumentService.IngestResult result = service.ingestCombined(uploads, DocumentSource.UPLOAD, areaId);
            return new UploadResultDto(name, result.outcome().name(),
                    result.document() != null && areaAccess.canSee(result.document().getAreaId())
                            ? result.document().getId() : null,
                    result.message() != null ? messages.render(result.message()) : null);
        } catch (IOException | FileStorage.StorageFullException e) {
            return new UploadResultDto(name, DocumentService.Outcome.REJECTED.name(), null,
                    messages.get("upload.storeFailed"));
        } finally {
            for (InputStream in : streams) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // nur Lesen – nichts zu retten
                }
            }
        }
    }

    @PutMapping("/{id}")
    public DocumentDto update(@PathVariable String id, @Valid @RequestBody UpdateDocumentRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/reprocess")
    public DocumentDto reprocess(@PathVariable String id) {
        return service.reprocess(id);
    }

    @PostMapping("/reprocess-unanalyzed")
    public Map<String, Integer> reprocessUnanalyzed() {
        return Map.of("count", service.reprocessUnanalyzed());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, @RequestParam(defaultValue = "false") boolean duplicate) {
        service.delete(id, duplicate);
    }
}
