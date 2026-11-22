package de.aidocuments.reminder;

import static de.aidocuments.i18n.ValidationMessages.MAX;
import static de.aidocuments.i18n.ValidationMessages.MIN;
import static de.aidocuments.i18n.ValidationMessages.NOT_NULL;
import static de.aidocuments.i18n.ValidationMessages.SIZE;

import de.aidocuments.area.AreaAccess;
import de.aidocuments.document.Document;
import de.aidocuments.document.DocumentRepository;
import de.aidocuments.document.DocumentService;
import de.aidocuments.i18n.Messages;
import de.aidocuments.user.AppUser;
import de.aidocuments.user.AppUserRepository;
import de.aidocuments.user.Role;
import de.aidocuments.user.UserPrincipal;
import de.aidocuments.web.ApiException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Wiedervorlage: Termine an Dokumenten anlegen, erledigen, verschieben – und Vorschläge aus den Angaben der KI
 * übernehmen oder verwerfen. Wer ein Dokument sieht, sieht auch seine Termine (Bereiche).
 */
@Service
public class ReminderService {

    /** So weit voraus zeigt die Übersicht Vorschläge aus allen Dokumenten. */
    static final int SUGGESTION_HORIZON_DAYS = 180;
    static final int MAX_SUGGESTIONS = 50;

    public record ReminderDto(String id, String documentId, String documentTitle, LocalDate dueDate, String note,
                              String assigneeId, String assigneeName, Reminder.Status status, Reminder.Source source,
                              String createdBy, Instant createdAt, Instant doneAt, String doneBy,
                              Integer repeatMonths) {
    }

    public record SuggestionDto(String documentId, String documentTitle, LocalDate date, String label) {
    }

    public record DocumentReminders(List<ReminderDto> reminders, List<SuggestionDto> suggestions) {
    }

    /** Die Übersicht „Fällig“: was fällig ist, was bald kommt, und Vorschläge aus den Dokumenten. */
    public record Overview(List<ReminderDto> due, List<ReminderDto> upcoming, List<SuggestionDto> suggestions) {
    }

    public record ReminderRequest(@NotNull(message = NOT_NULL) LocalDate dueDate,
                                  @Size(max = Reminder.MAX_NOTE_LENGTH, message = SIZE) String note,
                                  /* null = die anfragende Person */
                                  @Size(max = 64, message = SIZE) String assigneeId,
                                  /* übernimmt einen Vorschlag der KI; fehlt = nein */
                                  Boolean fromSuggestion,
                                  /* wiederholt sich alle so viele Monate; fehlt oder 0 = einmalig */
                                  @Min(value = 0, message = MIN) @Max(value = Reminder.MAX_REPEAT_MONTHS, message = MAX)
                                  Integer repeatMonths) {
    }

    public record DismissRequest(@NotNull(message = NOT_NULL) LocalDate date,
                                 @Size(max = Reminder.MAX_NOTE_LENGTH, message = SIZE) String label) {
    }

    public record Person(String id, String username) {
    }

    private final ReminderRepository reminders;
    private final DocumentService documentService;
    private final DocumentRepository documents;
    private final AreaAccess access;
    private final AppUserRepository users;
    private final Messages messages;

    public ReminderService(ReminderRepository reminders, DocumentService documentService, DocumentRepository documents,
                           AreaAccess access, AppUserRepository users, Messages messages) {
        this.messages = messages;
        this.reminders = reminders;
        this.documentService = documentService;
        this.documents = documents;
        this.access = access;
        this.users = users;
    }

    public DocumentReminders forDocument(String documentId) {
        Document doc = documentService.require(documentId);
        List<Reminder> all = reminders.findByDocument(documentId);
        Set<LocalDate> taken = all.stream().map(Reminder::dueDate).collect(Collectors.toSet());
        List<SuggestionDto> suggestions = DeadlineSuggestions.from(doc.getKeyFacts(), LocalDate.now()).stream()
                .filter(s -> !taken.contains(s.date()))
                .map(s -> new SuggestionDto(doc.getId(), title(doc), s.date(), s.label()))
                .toList();
        List<ReminderDto> visible = all.stream().filter(r -> r.status() != Reminder.Status.DISMISSED)
                .map(r -> toDto(r, doc)).toList();
        return new DocumentReminders(visible, suggestions);
    }

    public Overview overview(boolean mineOnly) {
        Set<String> areas = access.visible();
        String me = currentUserId();
        List<Reminder> open = reminders.find(areas, List.of(Reminder.Status.OPEN), null, mineOnly ? me : null);
        Map<String, Document> docs = documentsOf(open.stream().map(Reminder::documentId).toList());
        LocalDate today = LocalDate.now();
        List<ReminderDto> due = new ArrayList<>();
        List<ReminderDto> upcoming = new ArrayList<>();
        for (Reminder r : open) {
            Document doc = docs.get(r.documentId());
            if (doc != null) {
                (r.dueDate().isAfter(today) ? upcoming : due).add(toDto(r, doc));
            }
        }
        return new Overview(due, upcoming, suggestions(areas, today));
    }

    /** Offene, fällige Termine der angemeldeten Person – die Zahl am Menüpunkt. */
    public long dueCount() {
        String me = currentUserId();
        if (me == null) {
            return 0;
        }
        return reminders.find(access.visible(), List.of(Reminder.Status.OPEN), LocalDate.now(), me).size();
    }

    public ReminderDto create(String documentId, ReminderRequest request) {
        Document doc = documentService.require(documentId);
        String assignee = assignee(request.assigneeId(), doc);
        Reminder reminder = new Reminder(UUID.randomUUID().toString(), doc.getId(), request.dueDate(),
                blankToNull(request.note()), assignee, Reminder.Status.OPEN,
                Boolean.TRUE.equals(request.fromSuggestion()) ? Reminder.Source.SUGGESTION : Reminder.Source.USER, currentUserName(),
                Instant.now(), null, null, null, repeat(request.repeatMonths()));
        reminders.save(reminder);
        return toDto(reminder, doc);
    }

    /** Ein Vorschlag passt nicht: vermerken, damit er nicht wieder angeboten wird. */
    public void dismiss(String documentId, DismissRequest request) {
        Document doc = documentService.require(documentId);
        reminders.save(new Reminder(UUID.randomUUID().toString(), doc.getId(), request.date(),
                blankToNull(request.label()), null, Reminder.Status.DISMISSED, Reminder.Source.SUGGESTION,
                currentUserName(), Instant.now(), null, null, null, null));
    }

    public ReminderDto update(String id, ReminderRequest request) {
        Reminder r = require(id);
        Document doc = documentService.require(r.documentId());
        String assignee = assignee(request.assigneeId(), doc);
        // ein neuer Termin oder eine andere Person: die Erinnerung per E-Mail gilt dann neu
        boolean renotify = !r.dueDate().equals(request.dueDate()) || !Objects.equals(r.assigneeId(), assignee);
        Reminder updated = new Reminder(r.id(), r.documentId(), request.dueDate(), blankToNull(request.note()),
                assignee, r.status(), r.source(), r.createdBy(), r.createdAt(), r.doneAt(), r.doneBy(),
                renotify ? null : r.notifiedAt(), repeat(request.repeatMonths()));
        reminders.save(updated);
        return toDto(updated, doc);
    }

    public ReminderDto setDone(String id, boolean done) {
        Reminder r = require(id);
        Document doc = documentService.require(r.documentId());
        Reminder updated = new Reminder(r.id(), r.documentId(), r.dueDate(), r.note(), r.assigneeId(),
                done ? Reminder.Status.DONE : Reminder.Status.OPEN, r.source(), r.createdBy(), r.createdAt(),
                done ? Instant.now() : null, done ? currentUserName() : null, r.notifiedAt(), r.repeatMonths());
        reminders.save(updated);
        if (done && r.status() == Reminder.Status.OPEN && r.repeatMonths() != null) {
            scheduleNext(r);
        }
        return toDto(updated, doc);
    }

    /**
     * Der nächste Termin eines wiederkehrenden – so viele Monate nach dem bisherigen (nicht nach dem Erledigen), damit
     * sich der Rhythmus nicht verschiebt; liegt er schon zurück, der nächste danach. Nur einmal, auch wenn jemand den
     * erledigten wieder öffnet und erneut abhakt.
     */
    private void scheduleNext(Reminder r) {
        LocalDate next = r.dueDate().plusMonths(r.repeatMonths());
        LocalDate today = LocalDate.now();
        while (next.isBefore(today)) {
            next = next.plusMonths(r.repeatMonths());
        }
        LocalDate due = next;
        boolean exists = reminders.findByDocument(r.documentId()).stream()
                .anyMatch(o -> o.status() == Reminder.Status.OPEN && o.dueDate().equals(due));
        if (!exists) {
            reminders.save(new Reminder(UUID.randomUUID().toString(), r.documentId(), due, r.note(), r.assigneeId(),
                    Reminder.Status.OPEN, r.source(), r.createdBy(), Instant.now(), null, null, null,
                    r.repeatMonths()));
        }
    }

    private static Integer repeat(Integer months) {
        return months == null || months <= 0 ? null : months;
    }

    public void delete(String id) {
        Reminder r = require(id);
        documentService.require(r.documentId());
        reminders.delete(id);
    }

    /** Wer einen Termin an diesem Dokument übernehmen kann: alle aktiven Konten, die das Dokument sehen. */
    public List<Person> assignees(String documentId) {
        Document doc = documentService.require(documentId);
        return users.findAll().stream()
                .filter(AppUser::isEnabled)
                .filter(u -> access.canSee(u.getId(), u.getRole() == Role.ADMIN, doc.getAreaId()))
                .map(u -> new Person(u.getId(), u.getUsername()))
                .sorted(Comparator.comparing(Person::username, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    // ------------------------------------------------------------------------------------------------

    private Reminder require(String id) {
        Reminder r = reminders.findById(id).orElseThrow(() -> ApiException.notFound("error.reminder.notFound"));
        if (r.status() == Reminder.Status.DISMISSED) {
            throw ApiException.notFound("error.reminder.notFound");
        }
        return r;
    }

    /** Die zuständige Person – sie muss das Dokument sehen, sonst bekäme sie eine Erinnerung, die sie nicht öffnen kann. */
    public String assignee(String requested, Document doc) {
        String id = requested == null || requested.isBlank() ? currentUserId() : requested;
        if (id == null) {
            return null;
        }
        AppUser user = users.findById(id).orElseThrow(() -> ApiException.badRequest("error.reminder.unknownAssignee"));
        if (!access.canSee(user.getId(), user.getRole() == Role.ADMIN, doc.getAreaId())) {
            throw ApiException.badRequest("error.reminder.assigneeNoAccess", user.getUsername());
        }
        return user.getId();
    }

    private List<SuggestionDto> suggestions(Set<String> areas, LocalDate today) {
        LocalDate horizon = today.plusDays(SUGGESTION_HORIZON_DAYS);
        Set<String> taken = new HashSet<>();
        reminders.find(areas, List.of(Reminder.Status.values()), null, null)
                .forEach(r -> taken.add(r.documentId() + "|" + r.dueDate()));
        List<SuggestionDto> result = new ArrayList<>();
        for (Document doc : documents.findWithKeyFacts(areas)) {
            for (DeadlineSuggestions.Suggestion s : DeadlineSuggestions.from(doc.getKeyFacts(), today)) {
                if (!s.date().isAfter(horizon) && !taken.contains(doc.getId() + "|" + s.date())) {
                    result.add(new SuggestionDto(doc.getId(), title(doc), s.date(), s.label()));
                }
            }
        }
        result.sort(Comparator.comparing(SuggestionDto::date));
        return result.size() > MAX_SUGGESTIONS ? result.subList(0, MAX_SUGGESTIONS) : result;
    }

    private Map<String, Document> documentsOf(List<String> ids) {
        return documents.findAllById(new HashSet<>(ids)).stream()
                .collect(Collectors.toMap(Document::getId, Function.identity()));
    }

    private ReminderDto toDto(Reminder r, Document doc) {
        String assigneeName = r.assigneeId() == null ? null
                : users.findById(r.assigneeId()).map(AppUser::getUsername).orElse(null);
        return new ReminderDto(r.id(), r.documentId(), title(doc), r.dueDate(), messages.localize(r.note()),
                r.assigneeId(), assigneeName, r.status(), r.source(), r.createdBy(), r.createdAt(), r.doneAt(),
                r.doneBy(), r.repeatMonths());
    }

    private static String title(Document doc) {
        return doc.getTitle() != null ? doc.getTitle() : doc.getOriginalFilename();
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static UserPrincipal principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof UserPrincipal user ? user : null;
    }

    private static String currentUserId() {
        UserPrincipal user = principal();
        return user == null ? null : user.id();
    }

    private static String currentUserName() {
        UserPrincipal user = principal();
        return user == null ? null : user.username();
    }
}
