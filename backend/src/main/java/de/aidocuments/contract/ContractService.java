package de.aidocuments.contract;

import static de.aidocuments.i18n.ValidationMessages.MAX;
import static de.aidocuments.i18n.ValidationMessages.MIN;
import static de.aidocuments.i18n.ValidationMessages.SIZE;

import de.aidocuments.area.AreaAccess;
import de.aidocuments.area.Permission;
import de.aidocuments.document.Document;
import de.aidocuments.document.DocumentRepository;
import de.aidocuments.document.DocumentService;
import de.aidocuments.i18n.Msg;
import de.aidocuments.i18n.MsgCodec;
import de.aidocuments.reminder.Reminder;
import de.aidocuments.reminder.ReminderRepository;
import de.aidocuments.reminder.ReminderService;
import de.aidocuments.user.UserPrincipal;
import de.aidocuments.web.ApiException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Verträge: Laufzeit, Kündigungsfrist und Verlängerung eines Dokuments. Rechtzeitig vor dem Ende der Kündigungsfrist
 * (oder dem Laufzeitende, wenn der Vertrag ohnehin endet) steht eine Wiedervorlage an; ein täglicher Lauf verlängert
 * abgelaufene Laufzeiten, die nicht gekündigt wurden, und legt die nächste an.
 */
@Service
public class ContractService {

    private static final Logger log = LoggerFactory.getLogger(ContractService.class);

    public enum Status {
        /** läuft, nichts zu tun */
        ACTIVE,
        /** die Kündigungsfrist (bzw. das Laufzeitende) rückt näher – innerhalb der Vorlaufzeit */
        DUE_SOON,
        /** die Kündigungsfrist ist verstrichen; der Vertrag verlängert sich zum Laufzeitende */
        NOTICE_MISSED,
        /** gekündigt, läuft noch bis zum Laufzeitende */
        CANCELLED,
        /** ausgelaufen (gekündigt oder ohne Verlängerung) */
        ENDED,
        /** ohne festes Laufzeitende */
        OPEN_ENDED
    }

    public record ContractDto(String documentId, String documentTitle, String areaId, String partner,
                              LocalDate startDate, LocalDate endDate, Integer noticeMonths, Integer renewalMonths,
                              String amount, int reminderDays, boolean cancelled, LocalDate noticeDeadline,
                              LocalDate keyDate, Status status, String assigneeId, String reminderId,
                              Permission access) {
    }

    public record ContractRequest(
            @Size(max = 255, message = SIZE) String partner,
            LocalDate startDate,
            LocalDate endDate,
            @Min(value = 0, message = MIN) @Max(value = 120, message = MAX) Integer noticeMonths,
            @Min(value = 0, message = MIN) @Max(value = 120, message = MAX) Integer renewalMonths,
            @Size(max = 100, message = SIZE) String amount,
            /* fehlt = 14 */
            @Min(value = 0, message = MIN) @Max(value = 365, message = MAX) Integer reminderDays,
            boolean cancelled,
            /* wer erinnert wird; fehlt = die anfragende Person */
            @Size(max = 64, message = SIZE) String assigneeId) {
    }

    private final ContractRepository contracts;
    private final ReminderRepository reminders;
    private final ReminderService reminderService;
    private final DocumentService documentService;
    private final DocumentRepository documents;
    private final AreaAccess access;

    public ContractService(ContractRepository contracts, ReminderRepository reminders, ReminderService reminderService,
                           DocumentService documentService, DocumentRepository documents, AreaAccess access) {
        this.contracts = contracts;
        this.reminders = reminders;
        this.reminderService = reminderService;
        this.documentService = documentService;
        this.documents = documents;
        this.access = access;
    }

    /** Alle sichtbaren Verträge – zuerst die, bei denen bald etwas zu tun ist. */
    public List<ContractDto> list() {
        Map<String, Permission> permissions = access.permissions();
        List<Contract> all = contracts.findVisible(permissions == null ? null : permissions.keySet());
        Map<String, Document> docs = documents.findAllById(all.stream().map(Contract::documentId).toList()).stream()
                .collect(Collectors.toMap(Document::getId, Function.identity()));
        LocalDate today = LocalDate.now();
        return all.stream().filter(c -> docs.containsKey(c.documentId()))
                .map(c -> dto(c, docs.get(c.documentId()), today,
                        permissions == null ? Permission.DELETE : permissions.get(docs.get(c.documentId()).getAreaId())))
                .sorted(Comparator.comparing((ContractDto c) -> order(c.status()))
                        .thenComparing(ContractDto::keyDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /** Die Vertragsangaben eines Dokuments; {@code null}, wenn es keine gibt. */
    public ContractDto forDocument(String documentId) {
        Document doc = documentService.require(documentId);
        return contracts.findByDocument(documentId)
                .map(c -> dto(c, doc, LocalDate.now(), access.permission(doc.getAreaId()))).orElse(null);
    }

    public ContractDto save(String documentId, ContractRequest request) {
        Document doc = documentService.requireEditable(documentId);
        if (request.startDate() != null && request.endDate() != null && request.endDate().isBefore(request.startDate())) {
            throw ApiException.badRequest("error.contract.endBeforeStart");
        }
        Contract existing = contracts.findByDocument(documentId).orElse(null);
        String assignee = reminderService.assignee(request.assigneeId(), doc);
        Instant now = Instant.now();
        Contract contract = new Contract(documentId, blankToNull(request.partner()), request.startDate(),
                request.endDate(), zeroToNull(request.noticeMonths()), zeroToNull(request.renewalMonths()),
                blankToNull(request.amount()),
                request.reminderDays() == null ? Contract.DEFAULT_REMINDER_DAYS : request.reminderDays(),
                request.cancelled(), existing == null ? null : existing.reminderId(), assignee,
                existing == null ? currentUserName() : existing.createdBy(),
                existing == null ? now : existing.createdAt(), now);
        contract = syncReminder(contract, LocalDate.now());
        contracts.save(contract);
        log.info("Vertragsangaben zu Dokument {} gespeichert von {}", documentId, currentUserName());
        return dto(contract, doc, LocalDate.now(), access.permission(doc.getAreaId()));
    }

    /** Entfernt die Vertragsangaben; eine noch offene Wiedervorlage dazu verschwindet mit. */
    public void delete(String documentId) {
        documentService.requireEditable(documentId);
        contracts.findByDocument(documentId).ifPresent(c -> {
            openReminder(c).ifPresent(r -> reminders.delete(r.id()));
            contracts.delete(documentId);
        });
    }

    /**
     * Verlängert abgelaufene, nicht gekündigte Verträge um ihre Verlängerung – so oft, bis die Laufzeit wieder in der
     * Zukunft endet – und legt die Wiedervorlage für die nächste Kündigungsfrist an. Alle sechs Stunden, damit ein
     * Neustart den Tag nicht verpasst.
     */
    @Scheduled(initialDelay = 90_000, fixedDelay = 6 * 3_600_000)
    public void renewExpired() {
        LocalDate today = LocalDate.now();
        for (Contract contract : contracts.findToRenew(today)) {
            LocalDate end = contract.endDate();
            while (end.isBefore(today)) {
                end = end.plusMonths(contract.renewalMonths());
            }
            Contract renewed = syncReminder(contract.withEndDate(end), today);
            contracts.save(renewed);
            log.info("Vertrag zu Dokument {} verlängert bis {}", contract.documentId(), end);
        }
    }

    /**
     * Hält die Wiedervorlage zum Vertrag aktuell: eine offene wird auf den neuen Stichtag gesetzt; ist die bisherige
     * schon erledigt und gilt ein neuer Stichtag, entsteht eine neue. Gekündigt (oder Stichtag vorbei): die offene
     * gilt als erledigt bzw. bleibt, wie sie ist.
     */
    private Contract syncReminder(Contract contract, LocalDate today) {
        LocalDate key = contract.keyDate();
        Reminder open = openReminder(contract).orElse(null);
        if (contract.cancelled() || key == null) {
            if (open != null) {
                reminders.save(new Reminder(open.id(), open.documentId(), open.dueDate(), open.note(), open.assigneeId(),
                        Reminder.Status.DONE, open.source(), open.createdBy(), open.createdAt(), Instant.now(),
                        currentUserName(), open.notifiedAt(), null));
            }
            return contract;
        }
        if (key.isBefore(today)) {
            return contract; // die Frist ist verstrichen – ein offener Termin bleibt sichtbar, bis jemand ihn abhakt
        }
        LocalDate due = key.minusDays(contract.reminderDays());
        if (due.isBefore(today)) {
            due = today;
        }
        String note = MsgCodec.encode(contract.noticeDeadline() != null
                ? Msg.of("contract.reminder.notice", contract.noticeDeadline())
                : Msg.of("contract.reminder.ends", contract.endDate()));
        Reminder previous = contract.reminderId() == null ? null : reminders.findById(contract.reminderId()).orElse(null);
        if (open != null) {
            boolean changed = !open.dueDate().equals(due) || !Objects.equals(open.assigneeId(), contract.assigneeId());
            reminders.save(new Reminder(open.id(), open.documentId(), due, note, contract.assigneeId(),
                    Reminder.Status.OPEN, Reminder.Source.CONTRACT, open.createdBy(), open.createdAt(), null, null,
                    changed ? null : open.notifiedAt(), null));
            return contract;
        }
        if (previous != null && !previous.dueDate().isBefore(due)) {
            return contract; // für diesen Stichtag schon erledigt
        }
        Reminder created = new Reminder(UUID.randomUUID().toString(), contract.documentId(), due, note,
                contract.assigneeId(), Reminder.Status.OPEN, Reminder.Source.CONTRACT, currentUserName(),
                Instant.now(), null, null, null, null);
        reminders.save(created);
        return contract.withReminder(created.id());
    }

    private java.util.Optional<Reminder> openReminder(Contract contract) {
        return contract.reminderId() == null ? java.util.Optional.empty()
                : reminders.findById(contract.reminderId()).filter(r -> r.status() == Reminder.Status.OPEN);
    }

    static Status status(Contract c, LocalDate today) {
        if (c.endDate() == null) {
            return c.cancelled() ? Status.CANCELLED : Status.OPEN_ENDED;
        }
        if (c.endDate().isBefore(today) && (c.cancelled() || c.renewalMonths() == null)) {
            return Status.ENDED;
        }
        if (c.cancelled()) {
            return Status.CANCELLED;
        }
        LocalDate key = c.keyDate();
        if (key.isBefore(today)) {
            return Status.NOTICE_MISSED;
        }
        return key.minusDays(c.reminderDays()).isAfter(today) ? Status.ACTIVE : Status.DUE_SOON;
    }

    private static int order(Status status) {
        return switch (status) {
            case DUE_SOON -> 0;
            case NOTICE_MISSED -> 1;
            case ACTIVE -> 2;
            case CANCELLED -> 3;
            case OPEN_ENDED -> 4;
            case ENDED -> 5;
        };
    }

    private static ContractDto dto(Contract c, Document doc, LocalDate today, Permission permission) {
        return new ContractDto(c.documentId(), doc.getTitle() != null ? doc.getTitle() : doc.getOriginalFilename(),
                doc.getAreaId(), c.partner() != null ? c.partner() : doc.getCorrespondent(), c.startDate(), c.endDate(),
                c.noticeMonths(), c.renewalMonths(), c.amount(), c.reminderDays(), c.cancelled(), c.noticeDeadline(),
                c.keyDate(), status(c, today), c.assigneeId(), c.reminderId(), permission);
    }

    private static Integer zeroToNull(Integer value) {
        return value == null || value <= 0 ? null : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String currentUserName() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof UserPrincipal user
                ? user.username() : null;
    }
}
