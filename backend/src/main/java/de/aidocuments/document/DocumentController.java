package de.aidocuments.document;

import de.aidocuments.document.DocumentDtos.DocumentDto;
import de.aidocuments.document.DocumentDtos.FacetsDto;
import de.aidocuments.document.DocumentDtos.PageDto;
import de.aidocuments.document.DocumentDtos.TextDto;
import de.aidocuments.document.DocumentDtos.UpdateDocumentRequest;
import de.aidocuments.document.DocumentDtos.UploadResultDto;
import de.aidocuments.search.SearchIndex;
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

    public DocumentController(DocumentService service, DocumentFacets facets, FileStorage storage) {
        this.service = service;
        this.facets = facets;
        this.storage = storage;
    }

    /**
     * Liste und Suche in einem: ohne {@code q} die neuesten Dokumente, mit {@code q} eine Volltextsuche
     * über Inhalt, Titel, Schlagworte, Absender und Kerndaten. Alle Filter lassen sich kombinieren.
     */
    @GetMapping
    public PageDto<DocumentDto> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String correspondent,
            @RequestParam(required = false, name = "tag") List<String> tags,
            @RequestParam(required = false) DocumentStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "relevance") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size) {
        SearchIndex.SortOrder order = switch (sort) {
            case "newest" -> SearchIndex.SortOrder.NEWEST;
            case "date" -> SearchIndex.SortOrder.DOCUMENT_DATE;
            default -> SearchIndex.SortOrder.RELEVANCE;
        };
        return service.search(new SearchIndex.Criteria(q, category, correspondent,
                tags == null ? List.of() : tags, status, from, to, order,
                Math.max(0, page), Math.clamp(size, 1, 100)));
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
            throw ApiException.notFound("Die PDF-Datei");
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

    @GetMapping("/{id}/thumbnail")
    public ResponseEntity<Resource> thumbnail(@PathVariable String id) {
        service.require(id);
        Path path = storage.thumbnailPath(id);
        if (!Files.exists(path)) {
            throw ApiException.notFound("Das Vorschaubild");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(new FileSystemResource(path));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<UploadResultDto> upload(@RequestParam("files") List<MultipartFile> files) {
        List<UploadResultDto> results = new ArrayList<>();
        for (MultipartFile file : files) {
            String name = file.getOriginalFilename() != null ? file.getOriginalFilename() : "Dokument.pdf";
            try (InputStream in = file.getInputStream()) {
                DocumentService.IngestResult result = service.ingest(in, name, DocumentSource.UPLOAD, null);
                results.add(new UploadResultDto(name, result.outcome().name(),
                        result.document() != null ? result.document().getId() : null, result.message()));
            } catch (IOException e) {
                results.add(new UploadResultDto(name, DocumentService.Outcome.REJECTED.name(), null,
                        "Die Datei konnte nicht gespeichert werden."));
            }
        }
        return results;
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
    public void delete(@PathVariable String id) {
        service.delete(id);
    }
}
