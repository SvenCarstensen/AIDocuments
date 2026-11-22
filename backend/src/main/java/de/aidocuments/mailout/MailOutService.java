package de.aidocuments.mailout;

import static de.aidocuments.i18n.ValidationMessages.MAX;
import static de.aidocuments.i18n.ValidationMessages.MIN;
import static de.aidocuments.i18n.ValidationMessages.NOT_NULL;
import static de.aidocuments.i18n.ValidationMessages.SIZE;

import de.aidocuments.i18n.Msg;
import de.aidocuments.mail.MailSecurity;
import de.aidocuments.persistence.SettingsStore;
import de.aidocuments.security.SecretCipher;
import de.aidocuments.web.ApiException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Properties;
import org.springframework.stereotype.Service;

/** SMTP-Einstellungen verwalten und E-Mails verschicken. */
@Service
public class MailOutService {

    public record MailOutDto(boolean enabled, String host, int port, MailSecurity security, String username,
                             boolean passwordSet, String from, String appUrl, int digestHour) {
    }

    public record MailOutRequest(boolean enabled,
                                 @Size(max = 255, message = SIZE) String host,
                                 @Min(value = 1, message = MIN) @Max(value = 65535, message = MAX) int port,
                                 @NotNull(message = NOT_NULL) MailSecurity security,
                                 @Size(max = 255, message = SIZE) String username,
                                 /* null oder leer = gespeichertes Passwort behalten */
                                 @Size(max = 500, message = SIZE) String password,
                                 @Size(max = 254, message = SIZE) @Email(message = "{validation.email}") String from,
                                 @Size(max = 500, message = SIZE) String appUrl,
                                 @Min(value = 0, message = MIN) @Max(value = 23, message = MAX) int digestHour) {
    }

    /** Eine Nachricht in einer Sprache: Betreff und Text. */
    public record Mail(String to, String subject, String text) {
    }

    private final SettingsStore store;
    private final SecretCipher cipher;

    public MailOutService(SettingsStore store, SecretCipher cipher) {
        this.store = store;
        this.cipher = cipher;
    }

    public MailOutSettings current() {
        MailOutSettings stored = store.find(MailOutSettings.ID, MailOutSettings.class);
        return stored == null ? MailOutSettings.defaults() : stored;
    }

    public MailOutDto dto() {
        MailOutSettings s = current();
        return new MailOutDto(s.enabled(), s.host(), s.port(), s.security(), s.username(), s.passwordEncrypted() != null,
                s.from(), s.appUrl(), s.digestHour());
    }

    public synchronized MailOutDto update(MailOutRequest request) {
        MailOutSettings old = current();
        String host = strip(request.host());
        String password = old.passwordEncrypted();
        boolean auth = !strip(request.username()).isEmpty();
        if (request.password() != null && !request.password().isBlank()) {
            password = cipher.encrypt(request.password());
        } else if (auth && password != null && !mayReusePassword(old, host, request.security())) {
            // sonst ließe sich das gespeicherte Passwort mit einer geänderten Adresse an einen fremden Server schicken
            throw ApiException.badRequest("error.mailOut.passwordForNewServer");
        }
        String appUrl = strip(request.appUrl());
        if (!appUrl.isEmpty() && !appUrl.matches("(?i)https?://\\S+")) {
            throw ApiException.badRequest("error.mailOut.appUrlInvalid");
        }
        MailOutSettings updated = new MailOutSettings(request.enabled(), host, request.port(), request.security(),
                strip(request.username()), auth ? password : null, strip(request.from()),
                appUrl.replaceAll("/+$", ""), request.digestHour());
        if (updated.enabled() && !updated.complete()) {
            throw ApiException.badRequest("error.mailOut.incomplete");
        }
        store.save(MailOutSettings.ID, updated);
        return dto();
    }

    /** Ob E-Mails verschickt werden können. */
    public boolean ready() {
        MailOutSettings s = current();
        return s.enabled() && s.complete();
    }

    /** Verschickt eine Nachricht (Klartext, UTF-8). */
    public void send(Mail mail) throws MessagingException {
        send(current(), mail);
    }

    /** Testnachricht mit den gespeicherten Einstellungen; liefert die Meldung für die Oberfläche. */
    public Msg test(String to, Mail mail) {
        MailOutSettings s = current();
        if (!s.complete()) {
            return Msg.of("error.mailOut.incomplete");
        }
        try {
            send(s, mail);
            return Msg.of("mailOut.test.ok", to);
        } catch (MessagingException | RuntimeException e) {
            return Msg.of("mailOut.test.failed", Msg.detailOf(rootCause(e)));
        }
    }

    private void send(MailOutSettings s, Mail mail) throws MessagingException {
        Properties props = new Properties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.host", s.host());
        props.put("mail.smtp.port", String.valueOf(s.port()));
        props.put("mail.smtp.connectiontimeout", "15000");
        props.put("mail.smtp.timeout", "30000");
        props.put("mail.smtp.writetimeout", "30000");
        props.put("mail.smtp.ssl.checkserveridentity", "true");
        if (s.security() == MailSecurity.SSL) {
            props.put("mail.smtp.ssl.enable", "true");
        } else if (s.security() == MailSecurity.STARTTLS) {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        }
        boolean auth = s.username() != null && !s.username().isBlank();
        props.put("mail.smtp.auth", String.valueOf(auth));
        Session session = Session.getInstance(props);
        MimeMessage message = new MimeMessage(session);
        try {
            message.setFrom(new InternetAddress(s.from(), "AIDocuments", StandardCharsets.UTF_8.name()));
        } catch (java.io.UnsupportedEncodingException e) {
            message.setFrom(new InternetAddress(s.from()));
        }
        message.setRecipient(Message.RecipientType.TO, address(mail.to()));
        message.setSubject(mail.subject(), StandardCharsets.UTF_8.name());
        message.setText(mail.text(), StandardCharsets.UTF_8.name());
        message.setSentDate(new Date());
        message.setHeader("Auto-Submitted", "auto-generated");
        if (auth) {
            Transport.send(message, s.username(), s.passwordEncrypted() == null ? "" : cipher.decrypt(s.passwordEncrypted()));
        } else {
            Transport.send(message);
        }
    }

    private static InternetAddress address(String to) throws AddressException {
        InternetAddress address = new InternetAddress(to, true);
        address.validate();
        return address;
    }

    private static boolean mayReusePassword(MailOutSettings old, String host, MailSecurity security) {
        boolean sameHost = old.host() != null && old.host().strip().equalsIgnoreCase(host);
        boolean downgrade = security == MailSecurity.NONE && old.security() != MailSecurity.NONE;
        return sameHost && !downgrade;
    }

    private static Throwable rootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String strip(String text) {
        return text == null ? "" : text.strip();
    }
}
