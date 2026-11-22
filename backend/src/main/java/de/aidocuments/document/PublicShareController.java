package de.aidocuments.document;

import de.aidocuments.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ein geteiltes Dokument, aufgerufen über seinen Link – ohne Anmeldung. Der Link öffnet genau dieses eine Dokument:
 * Titel, Dateiname und PDF, nichts von den übrigen Angaben (Zusammenfassung, Schlagworte, Text, Historie). Darf die
 * Person bearbeiten, kann sie eine geänderte Fassung des PDFs speichern; sie erscheint in Fassungen und Historie als
 * „Link: &lt;Name&gt;“.
 *
 * <p>Abgelaufene, entzogene und unbekannte Links sowie Dokumente im Papierkorb ergeben alle dieselbe Antwort (404).
 */
@RestController
@RequestMapping("/api/share/{token}")
public class PublicShareController {

    private static final Logger log = LoggerFactory.getLogger(PublicShareController.class);
    /**
     * Gegen das Volllaufen der Platte (jede gespeicherte Fassung bleibt erhalten): je Link höchstens so viele
     * Speichervorgänge pro Stunde, so viele Bytes pro Tag und je Fassung nicht mehr als beim Hochladen erlaubt.
     */
    static final int MAX_SAVES_PER_HOUR = 30;
    static final long MAX_BYTES_PER_DAY = 500L * 1024 * 1024;
    static final long MAX_SAVE_BYTES = 50L * 1024 * 1024;
    private static final String NO_INDEX = "noindex, nofollow";

    private final DocumentService documents;
    private final DocumentShares shares;
    private final FileStorage storage;
    private final Map<String, List<Save>> saves = new java.util.HashMap<>();

    public PublicShareController(DocumentService documents, DocumentShares shares, FileStorage storage) {
        this.documents = documents;
        this.shares = shares;
        this.storage = storage;
    }

    public record SharedDocumentDto(String title, String filename, Integer pageCount, long sizeBytes,
                                    Instant modifiedAt, boolean canEdit, Instant expiresAt, String recipient) {
    }

    @GetMapping
    public ResponseEntity<SharedDocumentDto> get(@PathVariable String token) {
        Opened opened = open(token);
        shares.recordAccess(opened.share().id());
        return ResponseEntity.ok().header("X-Robots-Tag", NO_INDEX).cacheControl(CacheControl.noStore())
                .body(dto(opened));
    }

    @GetMapping("/file")
    public ResponseEntity<Resource> file(@PathVariable String token) {
        Opened opened = open(token);
        Path path = storage.resolve(opened.document().getStoragePath());
        if (!Files.exists(path)) {
            throw ApiException.notFound("error.share.notFound");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(opened.document().getOriginalFilename(), StandardCharsets.UTF_8).build().toString())
                .header("X-Robots-Tag", NO_INDEX)
                .cacheControl(CacheControl.noStore())
                .body(new FileSystemResource(path));
    }

    /** Speichert eine geänderte Fassung – nur mit einem Link, der das Bearbeiten erlaubt. */
    @PutMapping(value = "/file", consumes = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<SharedDocumentDto> save(@PathVariable String token, HttpServletRequest request)
            throws IOException {
        Opened opened = open(token);
        DocumentShares.Share share = opened.share();
        if (!share.canEdit()) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "error.share.readOnly");
        }
        Save reserved = reserveSave(share.id());
        if (reserved == null) {
            throw ApiException.of(HttpStatus.TOO_MANY_REQUESTS, "error.share.tooManySaves");
        }
        CountingInputStream body = new CountingInputStream(request.getInputStream(), MAX_SAVE_BYTES);
        // Fassung und Historie sollen die externe Person nennen: für die Dauer des Speicherns als „Link: Name“
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "Link: " + share.recipient(), null, List.of()));
        SecurityContextHolder.setContext(context);
        try {
            documents.replaceFile(share.documentId(), body);
        } finally {
            SecurityContextHolder.setContext(previous);
            reserved.bytes = body.count(); // auch ein abgelehnter Versuch zählt – sonst ließe sich endlos hochladen
        }
        log.info("Dokument {} über den Link für „{}“ geändert", share.documentId(), share.recipient());
        return ResponseEntity.ok().header("X-Robots-Tag", NO_INDEX).cacheControl(CacheControl.noStore())
                .body(dto(open(token)));
    }

    private record Opened(DocumentShares.Share share, Document document) {
    }

    private Opened open(String token) {
        DocumentShares.Share share = shares.findByToken(token)
                .filter(s -> !s.expired(Instant.now()))
                .orElseThrow(() -> ApiException.notFound("error.share.notFound"));
        Document document;
        try {
            document = documents.require(share.documentId()); // liegt es im Papierkorb, gibt es das Dokument nicht
        } catch (ApiException e) {
            throw ApiException.notFound("error.share.notFound");
        }
        return new Opened(share, document);
    }

    /** Ein Speichervorgang über einen Link: wann und wie viele Bytes er gebracht hat. */
    private static final class Save {
        final Instant at;
        volatile long bytes;

        Save(Instant at) {
            this.at = at;
        }
    }

    /** Merkt einen Speichervorgang vor – oder {@code null}, wenn der Link sein Stunden- oder Tageskontingent aufgebraucht hat. */
    private synchronized Save reserveSave(String shareId) {
        Instant now = Instant.now();
        Instant hourAgo = now.minus(Duration.ofHours(1));
        Instant dayAgo = now.minus(Duration.ofDays(1));
        List<Save> recent = new java.util.ArrayList<>(saves.getOrDefault(shareId, List.of()));
        recent.removeIf(save -> !save.at.isAfter(dayAgo));
        long lastHour = recent.stream().filter(save -> save.at.isAfter(hourAgo)).count();
        long bytesToday = recent.stream().mapToLong(save -> save.bytes).sum();
        if (lastHour >= MAX_SAVES_PER_HOUR || bytesToday >= MAX_BYTES_PER_DAY) {
            saves.put(shareId, recent);
            return null;
        }
        Save save = new Save(now);
        recent.add(save);
        saves.put(shareId, recent);
        if (saves.size() > 10_000) {
            saves.values().removeIf(list -> list.stream().noneMatch(s -> s.at.isAfter(dayAgo)));
        }
        return save;
    }

    /** Zählt die gelesenen Bytes und bricht ab, sobald es mehr als erlaubt werden. */
    private static final class CountingInputStream extends java.io.FilterInputStream {
        private final long limit;
        private long count;

        CountingInputStream(java.io.InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        long count() {
            return count;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                add(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            if (n > 0) {
                add(n);
            }
            return n;
        }

        private void add(long n) throws IOException {
            count += n;
            if (count > limit) {
                throw new FileStorage.TooLargeException();
            }
        }
    }

    private static SharedDocumentDto dto(Opened opened) {
        Document doc = opened.document();
        DocumentShares.Share share = opened.share();
        String title = doc.getTitle() != null ? doc.getTitle() : doc.getOriginalFilename();
        return new SharedDocumentDto(title, doc.getOriginalFilename(), doc.getPageCount(), doc.getSizeBytes(),
                doc.getModifiedAt(), share.canEdit(), share.expiresAt(), share.recipient());
    }
}
