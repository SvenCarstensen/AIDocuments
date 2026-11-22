package de.aidocuments.notify;

import de.aidocuments.area.AreaRepository;
import de.aidocuments.i18n.Messages;
import de.aidocuments.i18n.Msg;
import de.aidocuments.mailout.MailOutService;
import de.aidocuments.notify.NotificationRepository.Entry;
import de.aidocuments.user.AppUser;
import de.aidocuments.user.AppUserRepository;
import de.aidocuments.user.Role;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Benachrichtigungen per E-Mail (nur, wenn der E-Mail-Versand eingerichtet ist und die Person eine Adresse
 * hinterlegt hat):
 * <ul>
 *   <li>an Admins: Dokumente, deren Aufbewahrungsfrist abgelaufen ist (je Frist einmal) – gelöscht wird nichts;</li>
 *   <li>an Admins: ein Postfach lässt sich wiederholt nicht abrufen (einmal je Fehlerserie);</li>
 *   <li>an Admins: die automatische Sicherung ist fehlgeschlagen (einmal je Fehlerserie);</li>
 *   <li>an alle, die es eingeschaltet haben: einmal am Tag die neuen Dokumente in ihren Bereichen.</li>
 * </ul>
 * Die täglichen E-Mails gehen ab derselben Stunde hinaus wie die Erinnerungen.
 */
@Component
public class Notifications {

    private static final Logger log = LoggerFactory.getLogger(Notifications.class);

    /** So viele Dokumente stehen höchstens in einer E-Mail, der Rest als „… und N weitere“. */
    static final int MAX_LINES = 50;
    /** Weiter zurück reicht eine Übersicht nicht – etwa nach langer Pause oder wenn der Versand ausfiel. */
    static final Duration MAX_DIGEST_SPAN = Duration.ofDays(7);
    /** Die erste Übersicht nach dem Einschalten. */
    static final Duration FIRST_DIGEST_SPAN = Duration.ofDays(1);

    private final NotificationRepository repository;
    private final AppUserRepository users;
    private final AreaRepository areas;
    private final MailOutService mailOut;
    private final Messages messages;

    public Notifications(NotificationRepository repository, AppUserRepository users, AreaRepository areas,
                         MailOutService mailOut, Messages messages) {
        this.repository = repository;
        this.users = users;
        this.areas = areas;
        this.mailOut = mailOut;
        this.messages = messages;
    }

    @Scheduled(initialDelay = 150_000, fixedDelay = 600_000)
    public void tick() {
        if (!mailOut.ready() || LocalTime.now().getHour() < mailOut.current().digestHour()) {
            return;
        }
        try {
            sendRetentionNotices();
            sendDigests(Instant.now());
        } catch (RuntimeException e) {
            log.warn("Benachrichtigungen nicht verschickt: {}", e.toString());
        }
    }

    /** @return an wie viele Admins der Hinweis auf abgelaufene Fristen ging */
    public int sendRetentionNotices() {
        repository.forgetVanished();
        if (!mailOut.ready()) {
            return 0;
        }
        List<AppUser> admins = admins();
        if (admins.isEmpty()) {
            // ohne Empfänger nichts vermerken – wer später eine Adresse hinterlegt, erfährt es dann
            return 0;
        }
        List<Entry> expired = repository.unnotifiedExpired(LocalDate.now());
        if (expired.isEmpty()) {
            return 0;
        }
        int sent = 0;
        for (AppUser admin : admins) {
            Locale locale = locale(admin);
            StringBuilder text = new StringBuilder(render(locale, "mail.notify.retention.intro", admin.getUsername()))
                    .append("\n\n");
            lines(text, expired, locale, entry -> render(locale, "mail.notify.retention.line", entry.name(),
                    entry.retainUntil()));
            link(text, locale, "mail.notify.retention.overview", "/?retention=expired");
            footer(text, locale, "mail.notify.adminFooter");
            if (send(admin, render(locale, "mail.notify.retention.subject", expired.size()), text)) {
                sent++;
            }
        }
        if (sent > 0) {
            repository.markRetentionNotified(expired, Instant.now());
            log.info("Hinweis auf {} abgelaufene Aufbewahrungsfristen an {} Admins verschickt", expired.size(), sent);
        }
        return sent;
    }

    /** @return wie viele Übersichten neuer Dokumente hinausgingen */
    public int sendDigests(Instant now) {
        if (!mailOut.ready()) {
            return 0;
        }
        LocalDate today = LocalDate.ofInstant(now, ZoneId.systemDefault());
        int sent = 0;
        for (AppUser user : users.findAll()) {
            if (!user.isEnabled() || !user.isNotifyNewDocuments() || !hasAddress(user)) {
                continue;
            }
            Instant until = repository.digestUntil(user.getId());
            if (until != null && !LocalDate.ofInstant(until, ZoneId.systemDefault()).isBefore(today)) {
                continue; // heute schon
            }
            Instant since = until == null ? now.minus(FIRST_DIGEST_SPAN)
                    : until.isBefore(now.minus(MAX_DIGEST_SPAN)) ? now.minus(MAX_DIGEST_SPAN) : until;
            List<Entry> created = repository.createdBetween(since, now,
                    user.getRole() == Role.ADMIN ? null : areas.visibleFor(user.getId()));
            if (!created.isEmpty()) {
                Locale locale = locale(user);
                StringBuilder text = new StringBuilder(render(locale, "mail.notify.digest.intro", user.getUsername()))
                        .append("\n\n");
                String appUrl = appUrl();
                lines(text, created, locale, entry -> render(locale, "mail.notify.digest.line", entry.name())
                        + (appUrl == null ? "" : "\n   " + appUrl + "/documents/" + entry.id()));
                link(text, locale, "mail.notify.digest.overview", "/");
                footer(text, locale, "mail.notify.digestFooter");
                if (!send(user, render(locale, "mail.notify.digest.subject", created.size()), text)) {
                    continue; // beim nächsten Durchlauf erneut
                }
                sent++;
            }
            repository.setDigestUntil(user.getId(), now);
        }
        if (sent > 0) {
            log.info("Übersichten neuer Dokumente verschickt: {}", sent);
        }
        return sent;
    }

    /**
     * Ein Postfach ließ sich {@code failures}-mal in Folge nicht abrufen.
     *
     * @return ob mindestens ein Admin die E-Mail bekam
     */
    public boolean mailboxFailing(String mailboxName, Msg reason, int failures) {
        if (!mailOut.ready()) {
            return false;
        }
        boolean sent = false;
        for (AppUser admin : admins()) {
            Locale locale = locale(admin);
            StringBuilder text = new StringBuilder(render(locale, "mail.notify.mailbox.text", admin.getUsername(),
                    mailboxName, failures)).append("\n\n")
                    .append(messages.render(reason, locale)).append("\n\n");
            link(text, locale, "mail.notify.mailbox.hint", "/settings/mailboxes");
            footer(text, locale, "mail.notify.adminFooter");
            sent |= send(admin, render(locale, "mail.notify.mailbox.subject", mailboxName), text);
        }
        if (sent) {
            log.info("Admins über das Postfach {} benachrichtigt ({} Fehlversuche in Folge)", mailboxName, failures);
        }
        return sent;
    }

    /**
     * Die automatische Sicherung schlug fehl.
     *
     * @return ob mindestens ein Admin die E-Mail bekam
     */
    public boolean backupFailed(Msg reason) {
        if (!mailOut.ready()) {
            return false;
        }
        boolean sent = false;
        for (AppUser admin : admins()) {
            Locale locale = locale(admin);
            StringBuilder text = new StringBuilder(render(locale, "mail.notify.backup.text", admin.getUsername()))
                    .append("\n\n").append(messages.render(reason, locale)).append("\n\n");
            link(text, locale, "mail.notify.backup.hint", "/settings/backup");
            footer(text, locale, "mail.notify.adminFooter");
            sent |= send(admin, render(locale, "mail.notify.backup.subject"), text);
        }
        if (sent) {
            log.info("Admins über die fehlgeschlagene automatische Sicherung benachrichtigt");
        }
        return sent;
    }

    private List<AppUser> admins() {
        return users.findAll().stream()
                .filter(u -> u.getRole() == Role.ADMIN && u.isEnabled() && u.isNotifyAdmin() && hasAddress(u))
                .toList();
    }

    private static boolean hasAddress(AppUser user) {
        return user.getEmail() != null && !user.getEmail().isBlank();
    }

    private static Locale locale(AppUser user) {
        return user.getLocale() == null ? Locale.ENGLISH : Locale.forLanguageTag(user.getLocale());
    }

    private String render(Locale locale, String key, Object... args) {
        return messages.render(Msg.of(key, args), locale);
    }

    private String appUrl() {
        String url = mailOut.current().appUrl();
        return url == null || url.isBlank() ? null : url;
    }

    private void lines(StringBuilder text, List<Entry> entries, Locale locale,
                       java.util.function.Function<Entry, String> line) {
        entries.stream().limit(MAX_LINES).forEach(entry -> text.append(line.apply(entry)).append('\n'));
        if (entries.size() > MAX_LINES) {
            text.append(render(locale, "mail.notify.more", entries.size() - MAX_LINES)).append('\n');
        }
        text.append('\n');
    }

    private void link(StringBuilder text, Locale locale, String key, String path) {
        String appUrl = appUrl();
        if (appUrl != null) {
            text.append(render(locale, key, appUrl + path)).append("\n\n");
        }
    }

    private void footer(StringBuilder text, Locale locale, String key) {
        text.append("-- \n").append(render(locale, key)).append('\n');
    }

    private boolean send(AppUser user, String subject, StringBuilder text) {
        try {
            mailOut.send(new MailOutService.Mail(user.getEmail(), subject, text.toString()));
            return true;
        } catch (Exception e) {
            log.warn("Benachrichtigung an {} nicht verschickt: {}", user.getUsername(), e.toString());
            return false;
        }
    }
}
