package de.aidocuments.history;

import de.aidocuments.document.DocumentDtos.DocumentDto;
import de.aidocuments.document.DocumentDtos.PageDto;
import de.aidocuments.document.DocumentService;
import de.aidocuments.document.DocumentSource;
import de.aidocuments.history.HistoryEntry.Action;
import de.aidocuments.history.HistoryEntry.Change;
import de.aidocuments.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Die Historie, neueste Einträge zuerst – für alle angemeldeten Benutzer lesbar; Schritte lassen sich zurücknehmen. */
@RestController
@RequestMapping("/api/history")
public class HistoryController {

    /** Was die Oberfläche von einem Eintrag braucht – ohne interne Angaben wie Dateipfade. */
    public record HistoryDto(String id, Instant at, Action action, String documentId, String documentTitle,
                             DocumentSource source, String origin, Change change, String user,
                             Instant undoneAt, String undoneBy, boolean undoable) {
    }

    private final HistoryService history;
    private final DocumentService documents;

    public HistoryController(HistoryService history, DocumentService documents) {
        this.history = history;
        this.documents = documents;
    }

    @GetMapping
    public PageDto<HistoryDto> list(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size) {
        Page<HistoryEntry> result = history.page(Math.clamp(page, 0, 100_000), Math.clamp(size, 1, 200));
        return new PageDto<>(result.getContent().stream().map(this::dto).toList(), result.getTotalElements(),
                result.getNumber(), result.getSize());
    }

    /** Nimmt den Schritt zurück; ein gelöschtes Dokument zurückholen dürfen – wie das Löschen – nur Administratoren. */
    @PostMapping("/{id}/undo")
    public DocumentDto undo(@PathVariable String id, HttpServletRequest request) throws IOException {
        HistoryEntry entry = history.find(id).orElseThrow(() -> ApiException.notFound("error.history.notUndoable"));
        if (entry.action() == Action.DELETED && !request.isUserInRole("ADMIN")) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "error.history.adminOnly");
        }
        if (!history.isUndoable(entry) || !documents.canUndo(entry)) {
            throw ApiException.conflict("error.history.notUndoable");
        }
        DocumentDto result = documents.undo(entry);
        history.markUndone(entry);
        return result;
    }

    private HistoryDto dto(HistoryEntry e) {
        boolean undoable = history.isUndoable(e) && documents.canUndo(e);
        return new HistoryDto(e.id(), e.at(), e.action(), e.documentId(), e.documentTitle(), e.source(), e.origin(),
                e.change(), e.user(), e.undoneAt(), e.undoneBy(), undoable);
    }
}
