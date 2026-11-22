package de.aidocuments.history;

import de.aidocuments.document.Document;
import de.aidocuments.document.DocumentSource;
import de.aidocuments.history.HistoryEntry.Action;
import de.aidocuments.history.HistoryEntry.Change;
import de.aidocuments.history.HistoryEntry.Details;
import de.aidocuments.mail.MailAccountRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Führt die Historie: neue Dokumente (mit Quelle), Bearbeitungen und Löschungen. Ein Fehler beim Eintragen hält die
 * eigentliche Aktion nie auf – die Historie ist ein Protokoll, keine Voraussetzung.
 *
 * <p>Rückgängig machen lässt sich jeweils der neueste noch geltende Schritt eines Dokuments – Bearbeitungen und
 * Löschungen, nacheinander auch mehrere. So überschreibt das Zurücknehmen nie eine spätere Änderung.
 */
@Service
public class HistoryService {

    private static final Logger log = LoggerFactory.getLogger(HistoryService.class);

    private final HistoryRepository history;
    private final MailAccountRepository mailAccounts;

    public HistoryService(HistoryRepository history, MailAccountRepository mailAccounts) {
        this.history = history;
        this.mailAccounts = mailAccounts;
    }

    public void imported(Document doc) {
        record(Action.IMPORTED, doc, doc.getSource(), origin(doc), null, null, null);
    }

    public void editedDetails(Document doc, Details before) {
        record(Action.EDITED, doc, null, null, Change.DETAILS, before, null);
    }

    /** @param previousFile die aufbewahrte Fassung davor; null = davor war es noch das Original */
    public void editedFile(Document doc, Change change, String previousFile) {
        record(Action.EDITED, doc, null, null, change, null, previousFile);
    }

    public void deleted(Document doc, boolean asDuplicate) {
        record(Action.DELETED, doc, null, null, asDuplicate ? Change.DUPLICATE : null, null, null);
    }

    public Page<HistoryEntry> page(int page, int size) {
        return history.findAllByOrderByAtDesc(PageRequest.of(page, size));
    }

    public Optional<HistoryEntry> find(String id) {
        return history.findById(id);
    }

    /** Ob sich der Schritt (noch) zurücknehmen lässt – ob die Daten dafür noch da sind, prüft der Aufrufer. */
    public boolean isUndoable(HistoryEntry entry) {
        return entry.undoneAt() == null && entry.action() != Action.IMPORTED
                && !history.existsByDocumentIdAndAtAfterAndUndoneAtIsNull(entry.documentId(), entry.at());
    }

    public void markUndone(HistoryEntry entry) {
        history.save(entry.undone(currentUser()));
    }

    public static Details detailsOf(Document doc) {
        return new Details(doc.getTitle(), doc.getSummary(), doc.getCategory(), doc.getCorrespondent(),
                doc.getDocumentDate(), List.copyOf(doc.getTags()));
    }

    private void record(Action action, Document doc, DocumentSource source, String origin, Change change,
                        Details before, String previousFile) {
        try {
            String title = doc.getTitle() != null ? doc.getTitle() : doc.getOriginalFilename();
            history.save(new HistoryEntry(UUID.randomUUID().toString(), Instant.now(), action, doc.getId(), title,
                    source, origin, change, currentUser(), before, previousFile, null, null));
        } catch (RuntimeException e) {
            log.warn("Historie: Eintrag für {} nicht gespeichert: {}", doc.getId(), e.toString());
        }
    }

    /** Woher ein Dokument kam, in Worten: Postfach und Betreff bzw. der Dateiname. */
    private String origin(Document doc) {
        List<String> parts = new ArrayList<>();
        if (doc.getSource() == DocumentSource.MAIL) {
            if (doc.getMailAccountId() != null) {
                mailAccounts.findById(doc.getMailAccountId()).ifPresent(account -> parts.add(account.getName()));
            }
            if (doc.getMailSubject() != null && !doc.getMailSubject().isBlank()) {
                parts.add("„" + doc.getMailSubject().strip() + "“");
            }
        }
        parts.add(doc.getOriginalFilename());
        return String.join(" · ", parts);
    }

    /** Der angemeldete Anwender; null bei Aktionen im Hintergrund (Abruf, Eingangsordner). */
    static String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return auth.getName();
    }
}
