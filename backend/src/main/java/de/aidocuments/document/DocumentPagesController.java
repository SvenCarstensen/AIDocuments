package de.aidocuments.document;

import static de.aidocuments.i18n.ValidationMessages.NOT_NULL;
import static de.aidocuments.i18n.ValidationMessages.SIZE;

import de.aidocuments.document.DocumentDtos.DocumentDto;
import de.aidocuments.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Seiten eines Dokuments: Übersicht als kleine Bilder, neu anordnen, trennen, andere Dokumente anhängen. */
@RestController
@RequestMapping("/api/documents/{id}")
public class DocumentPagesController {

    public record RearrangeRequest(@NotNull(message = NOT_NULL)
                                   @Size(min = 1, max = DocumentPages.MAX_PAGES, message = SIZE) List<DocumentPages.PageSpec> pages) {
    }

    public record SplitRequest(int fromPage) {
    }

    public record SplitResponse(DocumentDto original, String createdId) {
    }

    public record MergeRequest(@NotNull(message = NOT_NULL)
                               @Size(min = 1, max = DocumentPages.MAX_MERGED, message = SIZE) List<String> documentIds) {
    }

    private final DocumentPages pages;

    public DocumentPagesController(DocumentPages pages) {
        this.pages = pages;
    }

    /** Die Seite (ab 1) als JPEG; der Aufrufer hängt den Änderungsstand an die Adresse, daher darf sie zwischengespeichert werden. */
    @GetMapping("/pages/{page}/image")
    public ResponseEntity<byte[]> image(@PathVariable String id, @PathVariable int page,
                                        @RequestParam(defaultValue = "200") int width) throws IOException {
        byte[] jpeg = pages.pageImage(id, page, width);
        if (jpeg == null) {
            throw ApiException.notFound("error.pages.invalid");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(jpeg);
    }

    @PostMapping("/pages")
    public DocumentDto rearrange(@PathVariable String id, @Valid @RequestBody RearrangeRequest request)
            throws IOException {
        return pages.rearrange(id, request.pages());
    }

    @PostMapping("/split")
    public SplitResponse split(@PathVariable String id, @RequestBody SplitRequest request) throws IOException {
        DocumentPages.SplitResult result = pages.split(id, request.fromPage());
        return new SplitResponse(result.original(), result.createdId());
    }

    @PostMapping("/merge")
    public DocumentDto merge(@PathVariable String id, @Valid @RequestBody MergeRequest request) throws IOException {
        return pages.merge(id, request.documentIds());
    }
}
