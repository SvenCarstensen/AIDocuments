package de.aidocuments.document;

import static de.aidocuments.i18n.ValidationMessages.MAX;
import static de.aidocuments.i18n.ValidationMessages.MIN;
import static de.aidocuments.i18n.ValidationMessages.NOT_BLANK;
import static de.aidocuments.i18n.ValidationMessages.SIZE;

import de.aidocuments.user.UserPrincipal;
import de.aidocuments.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Links verwalten, mit denen nicht angemeldete Personen ein Dokument öffnen – für alle angemeldeten Anwender (die
 * Ablage ist ohnehin gemeinsam). Den Link selbst ruft {@link PublicShareController} ab.
 */
@RestController
@RequestMapping("/api/documents/{id}/shares")
public class DocumentShareController {

    private static final Logger log = LoggerFactory.getLogger(DocumentShareController.class);

    private final DocumentService documents;
    private final DocumentShares shares;

    public DocumentShareController(DocumentService documents, DocumentShares shares) {
        this.documents = documents;
        this.shares = shares;
    }

    /** Ein Link, wie die Oberfläche ihn zeigt – samt Token, damit er sich erneut kopieren lässt. */
    public record ShareDto(String id, String recipient, boolean canEdit, Instant createdAt, String createdBy,
                           Instant expiresAt, boolean expired, Instant lastAccessAt, int accessCount, String token) {
    }

    /**
     * @param expiresInDays Gültigkeit in Tagen; {@code null} = unbegrenzt
     */
    public record CreateShareRequest(
            @NotBlank(message = NOT_BLANK) @Size(max = 100, message = SIZE) String recipient,
            boolean canEdit,
            @Min(value = 1, message = MIN) @Max(value = 3650, message = MAX) Integer expiresInDays) {
    }

    @GetMapping
    public List<ShareDto> list(@PathVariable String id) {
        documents.require(id);
        Instant now = Instant.now();
        return shares.list(id).stream().map(share -> dto(share, shares.token(share), now)).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShareDto create(@PathVariable String id, @Valid @RequestBody CreateShareRequest body,
                           @AuthenticationPrincipal UserPrincipal principal) {
        documents.require(id);
        Instant expiresAt = body.expiresInDays() == null ? null
                : Instant.now().plus(Duration.ofDays(body.expiresInDays()));
        String recipient = body.recipient().strip().replaceAll("\\p{Cntrl}", " ");
        DocumentShares.Created created = shares.create(id, recipient, body.canEdit(), expiresAt,
                principal.getUsername());
        log.info("Dokument {} von {} geteilt mit „{}“ ({}, gültig bis {})", id, principal.getUsername(), recipient,
                body.canEdit() ? "darf bearbeiten" : "nur ansehen", expiresAt == null ? "unbegrenzt" : expiresAt);
        return dto(created.share(), created.token(), Instant.now());
    }

    @DeleteMapping("/{shareId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable String id, @PathVariable String shareId,
                       @AuthenticationPrincipal UserPrincipal principal) {
        if (!shares.revoke(id, shareId)) {
            throw ApiException.notFound("error.share.notFound");
        }
        log.info("Link {} für Dokument {} von {} entzogen", shareId, id, principal.getUsername());
    }

    private static ShareDto dto(DocumentShares.Share share, String token, Instant now) {
        return new ShareDto(share.id(), share.recipient(), share.canEdit(), share.createdAt(), share.createdBy(),
                share.expiresAt(), share.expired(now), share.lastAccessAt(), share.accessCount(), token);
    }
}
