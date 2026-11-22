package de.aidocuments.reminder;

import de.aidocuments.area.AreaAccess;
import de.aidocuments.document.Document;
import de.aidocuments.document.DocumentRepository;
import de.aidocuments.i18n.Messages;
import de.aidocuments.i18n.Msg;
import de.aidocuments.mailout.MailOutService;
import de.aidocuments.mailout.MailOutSettings;
import de.aidocuments.user.AppUser;
import de.aidocuments.user.AppUserRepository;
import de.aidocuments.user.Role;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Erinnert per E-Mail an fällige Termine: einmal je Termin, gesammelt in einer Nachricht je Person, ab der
 * eingestellten Stunde. Nur, wer eine Adresse hinterlegt hat und das Dokument (noch) sehen darf.
 */
@Component
public class ReminderNotifier {

    private static final Logger log = LoggerFactory.getLogger(ReminderNotifier.class);

    private final ReminderRepository reminders;
    private final DocumentRepository documents;
    private final AppUserRepository users;
    private final AreaAccess access;
    private final MailOutService mailOut;
    private final Messages messages;

    public ReminderNotifier(ReminderRepository reminders, DocumentRepository documents, AppUserRepository users,
                            AreaAccess access, MailOutService mailOut, Messages messages) {
        this.reminders = reminders;
        this.documents = documents;
        this.users = users;
        this.access = access;
        this.mailOut = mailOut;
        this.messages = messages;
    }

    @Scheduled(initialDelay = 120_000, fixedDelay = 600_000)
    public void tick() {
        if (mailOut.ready() && LocalTime.now().getHour() >= mailOut.current().digestHour()) {
            sendDue();
        }
    }

    /** @return wie viele E-Mails hinausgingen */
    public int sendDue() {
        MailOutSettings settings = mailOut.current();
        List<Reminder> due = reminders.findDueUnnotified(LocalDate.now());
        Map<String, List<Reminder>> byAssignee = due.stream().collect(Collectors.groupingBy(Reminder::assigneeId));
        int sent = 0;
        for (Map.Entry<String, List<Reminder>> entry : byAssignee.entrySet()) {
            AppUser user = users.findById(entry.getKey()).orElse(null);
            if (user == null || !user.isEnabled() || user.getEmail() == null || user.getEmail().isBlank()) {
                continue;
            }
            Map<String, Document> docs = documents.findAllById(
                            entry.getValue().stream().map(Reminder::documentId).distinct().toList()).stream()
                    .collect(Collectors.toMap(Document::getId, Function.identity()));
            List<Reminder> visible = entry.getValue().stream()
                    .filter(r -> docs.containsKey(r.documentId()))
                    .filter(r -> access.canSee(user.getId(), user.getRole() == Role.ADMIN, docs.get(r.documentId()).getAreaId()))
                    .toList();
            if (visible.isEmpty()) {
                continue;
            }
            Locale locale = user.getLocale() == null ? Locale.ENGLISH : Locale.forLanguageTag(user.getLocale());
            try {
                mailOut.send(new MailOutService.Mail(user.getEmail(),
                        messages.render(Msg.of("mail.reminders.subject", visible.size()), locale),
                        text(user, visible, docs, settings.appUrl(), locale)));
                reminders.markNotified(visible.stream().map(Reminder::id).toList(), Instant.now());
                sent++;
            } catch (Exception e) {
                // beim nächsten Durchlauf erneut versuchen
                log.warn("Erinnerung an {} nicht verschickt: {}", user.getUsername(), e.toString());
            }
        }
        if (sent > 0) {
            log.info("Erinnerungen per E-Mail verschickt: {}", sent);
        }
        return sent;
    }

    private String text(AppUser user, List<Reminder> due, Map<String, Document> docs, String appUrl, Locale locale) {
        StringBuilder text = new StringBuilder(messages.render(Msg.of("mail.reminders.intro", user.getUsername()), locale))
                .append("\n\n");
        for (Reminder r : due) {
            Document doc = docs.get(r.documentId());
            String title = doc.getTitle() != null ? doc.getTitle() : doc.getOriginalFilename();
            text.append(messages.render(Msg.of("mail.reminders.line", r.dueDate(), title), locale)).append('\n');
            if (r.note() != null) {
                text.append("   ").append(messages.localize(r.note(), locale)).append('\n');
            }
            if (appUrl != null && !appUrl.isBlank()) {
                text.append("   ").append(appUrl).append("/documents/").append(doc.getId()).append('\n');
            }
            text.append('\n');
        }
        if (appUrl != null && !appUrl.isBlank()) {
            text.append(messages.render(Msg.of("mail.reminders.overview", appUrl + "/reminders"), locale)).append("\n\n");
        }
        text.append("-- \n").append(messages.render(Msg.of("mail.reminders.footer"), locale)).append('\n');
        return text.toString();
    }
}
