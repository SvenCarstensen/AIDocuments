package de.aidocuments.mail;

import de.aidocuments.document.DocumentService;
import de.aidocuments.document.DocumentService.MailOrigin;
import de.aidocuments.document.DocumentService.Outcome;
import de.aidocuments.document.DocumentSource;
import de.aidocuments.mail.MailDtos.ConnectionTestDto;
import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.FetchProfile;
import jakarta.mail.Folder;
import jakarta.mail.FolderClosedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.StoreClosedException;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeUtility;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Holt PDF-Anhänge aus IMAP-Postfächern. Das Postfach wird nur gelesen: Der Ordner wird schreibgeschützt
 * geöffnet, E-Mails werden weder als gelesen markiert noch verschoben oder gelöscht. Welche E-Mails schon
 * geprüft sind, merkt sich die Anwendung über die IMAP-UID.
 */
@Service
public class MailSyncService {

    private static final Logger log = LoggerFactory.getLogger(MailSyncService.class);
    private static final int FETCH_BATCH = 200;
    private static final int MAX_NESTING = 10;

    private final MailAccountRepository accounts;
    private final MailAccountService accountService;
    private final DocumentService documentService;
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public MailSyncService(MailAccountRepository accounts, MailAccountService accountService,
                           DocumentService documentService) {
        this.accounts = accounts;
        this.accountService = accountService;
        this.documentService = documentService;
    }

    public void syncAllEnabled() {
        accounts.findByEnabledTrue().forEach(account -> sync(account.getId()));
    }

    public boolean isRunning(String accountId) {
        return running.contains(accountId);
    }

    /** Ruft ein Postfach ab. Läuft für dasselbe Postfach bereits ein Abruf, passiert nichts. */
    public void sync(String accountId) {
        if (!running.add(accountId)) {
            return;
        }
        try {
            MailAccount account = accounts.findById(accountId).orElse(null);
            if (account == null) {
                return;
            }
            accountService.markRunning(accountId);
            try {
                Counters counters = fetch(account);
                accountService.markFinished(accountId, MailAccount.SyncStatus.OK, counters.summary());
                if (counters.imported > 0) {
                    log.info("Postfach {}: {}", account.getName(), counters.summary());
                }
            } catch (Exception e) {
                log.warn("Abruf von Postfach {} fehlgeschlagen: {}", account.getName(), e.toString());
                accountService.markFinished(accountId, MailAccount.SyncStatus.ERROR, describe(e));
            }
        } finally {
            running.remove(accountId);
        }
    }

    /** Baut testweise eine Verbindung auf und öffnet den Ordner. */
    public ConnectionTestDto test(MailAccount account, String password) {
        try (Store store = connect(account, password)) {
            Folder folder = store.getFolder(account.getFolder());
            if (!folder.exists()) {
                return new ConnectionTestDto(false, "Die Anmeldung klappt, aber den Ordner „"
                        + account.getFolder() + "“ gibt es nicht.");
            }
            folder.open(Folder.READ_ONLY);
            int count = folder.getMessageCount();
            folder.close(false);
            return new ConnectionTestDto(true, "Verbindung erfolgreich – " + count + " E-Mail"
                    + (count == 1 ? "" : "s") + " im Ordner „" + account.getFolder() + "“.");
        } catch (Exception e) {
            return new ConnectionTestDto(false, describe(e));
        }
    }

    private Counters fetch(MailAccount account) throws MessagingException {
        Counters counters = new Counters();
        try (Store store = connect(account, accountService.password(account))) {
            Folder folder = store.getFolder(account.getFolder());
            if (!folder.exists()) {
                throw new MessagingException("Der Ordner „" + account.getFolder() + "“ existiert nicht.");
            }
            folder.open(Folder.READ_ONLY);
            try {
                UIDFolder uidFolder = (UIDFolder) folder;
                long validity = uidFolder.getUIDValidity();
                boolean sameMailbox = account.getUidValidity() != null && account.getUidValidity() == validity;
                long lastUid = sameMailbox && account.getLastUid() != null ? account.getLastUid() : 0;

                Message[] messages = uidFolder.getMessagesByUID(lastUid + 1, UIDFolder.LASTUID);
                FetchProfile uidProfile = new FetchProfile();
                uidProfile.add(UIDFolder.FetchProfileItem.UID);
                folder.fetch(messages, uidProfile);
                // "n:*" liefert immer mindestens die neueste E-Mail – auch wenn sie schon bekannt ist
                List<Message> fresh = new ArrayList<>();
                for (Message message : messages) {
                    if (uidFolder.getUID(message) > lastUid) {
                        fresh.add(message);
                    }
                }
                fresh.sort(Comparator.comparingLong(message -> uid(uidFolder, message)));

                FetchProfile structure = new FetchProfile();
                structure.add(FetchProfile.Item.ENVELOPE);
                structure.add(FetchProfile.Item.CONTENT_INFO);
                Instant importSince = account.getImportSince() == null ? null
                        : account.getImportSince().atStartOfDay(ZoneId.systemDefault()).toInstant();

                for (int start = 0; start < fresh.size(); start += FETCH_BATCH) {
                    List<Message> batch = fresh.subList(start, Math.min(fresh.size(), start + FETCH_BATCH));
                    folder.fetch(batch.toArray(Message[]::new), structure);
                    for (Message message : batch) {
                        long uid = uidFolder.getUID(message);
                        try {
                            importMessage(account, message, importSince, counters);
                        } catch (FolderClosedException | StoreClosedException e) {
                            throw e; // Verbindung weg: abbrechen, beim nächsten Lauf hier weitermachen
                        } catch (MessagingException | IOException | RuntimeException e) {
                            counters.unreadable++;
                            log.warn("E-Mail UID {} in {} konnte nicht gelesen werden: {}", uid,
                                    account.getName(), e.toString());
                        }
                        accountService.saveCursor(account.getId(), validity, uid);
                    }
                }
                if (fresh.isEmpty() && !sameMailbox) {
                    accountService.saveCursor(account.getId(), validity, lastUid);
                }
            } finally {
                if (folder.isOpen()) {
                    folder.close(false);
                }
            }
        }
        return counters;
    }

    private void importMessage(MailAccount account, Message message, Instant importSince, Counters counters)
            throws MessagingException, IOException {
        counters.checked++;
        Date received = message.getReceivedDate() != null ? message.getReceivedDate() : message.getSentDate();
        Instant receivedAt = received != null ? received.toInstant() : null;
        if (importSince != null && receivedAt != null && receivedAt.isBefore(importSince)) {
            return;
        }
        List<Part> pdfParts = new ArrayList<>();
        collectPdfParts(message, pdfParts, 0);
        if (pdfParts.isEmpty()) {
            return;
        }
        MailOrigin origin = new MailOrigin(account.getId(), clip(message.getSubject(), 1000),
                clip(sender(message), 500), receivedAt);
        for (Part part : pdfParts) {
            try (InputStream in = part.getInputStream()) {
                Outcome outcome = documentService.ingest(in, filename(part), DocumentSource.MAIL, origin).outcome();
                switch (outcome) {
                    case CREATED -> counters.imported++;
                    case DUPLICATE -> counters.duplicates++;
                    case REJECTED -> counters.rejected++;
                }
            }
        }
    }

    private static void collectPdfParts(Part part, List<Part> result, int depth)
            throws MessagingException, IOException {
        if (depth > MAX_NESTING) {
            return;
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                collectPdfParts(multipart.getBodyPart(i), result, depth + 1);
            }
        } else if (part.isMimeType("message/rfc822")) {
            if (part.getContent() instanceof Part nested) {
                collectPdfParts(nested, result, depth + 1);
            }
        } else if (isPdf(part)) {
            result.add(part);
        }
    }

    private static boolean isPdf(Part part) throws MessagingException {
        if (part.isMimeType("application/pdf") || part.isMimeType("application/x-pdf")) {
            return true;
        }
        // manche Programme verschicken PDFs als application/octet-stream
        String name = part.getFileName();
        return name != null && decode(name).toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private static String filename(Part part) throws MessagingException {
        String name = part.getFileName();
        if (name == null || name.isBlank()) {
            return "Dokument.pdf";
        }
        String decoded = decode(name);
        return decoded.toLowerCase(Locale.ROOT).endsWith(".pdf") ? decoded : decoded + ".pdf";
    }

    private static String decode(String headerValue) {
        try {
            return MimeUtility.decodeText(headerValue);
        } catch (UnsupportedEncodingException e) {
            return headerValue;
        }
    }

    private static String sender(Message message) throws MessagingException {
        Address[] from = message.getFrom();
        if (from == null || from.length == 0) {
            return null;
        }
        return from[0] instanceof InternetAddress address ? address.toUnicodeString() : from[0].toString();
    }

    private static long uid(UIDFolder folder, Message message) {
        try {
            return folder.getUID(message);
        } catch (MessagingException e) {
            return Long.MAX_VALUE;
        }
    }

    private static Store connect(MailAccount account, String password) throws MessagingException {
        String protocol = account.getSecurity() == MailSecurity.SSL ? "imaps" : "imap";
        String prefix = "mail." + protocol + ".";
        Properties props = new Properties();
        props.put("mail.store.protocol", protocol);
        props.put(prefix + "connectiontimeout", "15000");
        props.put(prefix + "timeout", "60000");
        props.put(prefix + "writetimeout", "30000");
        // Anhänge laden, ohne die E-Mail als gelesen zu markieren
        props.put(prefix + "peek", "true");
        props.put(prefix + "ssl.checkserveridentity", "true");
        if (account.getSecurity() == MailSecurity.STARTTLS) {
            props.put(prefix + "starttls.enable", "true");
            props.put(prefix + "starttls.required", "true");
        }
        Store store = Session.getInstance(props).getStore(protocol);
        store.connect(account.getHost(), account.getPort(), account.getUsername(), password);
        return store;
    }

    /** Übersetzt technische Fehler in eine Meldung, mit der der Anwender etwas anfangen kann. */
    static String describe(Exception e) {
        if (e instanceof AuthenticationFailedException) {
            return "Anmeldung abgelehnt – bitte Benutzername und Passwort prüfen.";
        }
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof UnknownHostException) {
                return "Der Server „" + cause.getMessage() + "“ wurde nicht gefunden.";
            }
            if (cause instanceof ConnectException || cause instanceof SocketTimeoutException) {
                return "Der Server antwortet nicht – bitte Adresse, Port und Verschlüsselung prüfen.";
            }
            if (cause instanceof SSLException) {
                return "Die verschlüsselte Verbindung kam nicht zustande (" + cause.getMessage()
                        + ") – passen Port und Verschlüsselung zusammen?";
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static String clip(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static final class Counters {
        int checked;
        int imported;
        int duplicates;
        int rejected;
        int unreadable;

        String summary() {
            if (checked == 0) {
                return "Keine neuen E-Mails.";
            }
            List<String> parts = new ArrayList<>();
            parts.add(checked + (checked == 1 ? " neue E-Mail geprüft" : " neue E-Mails geprüft"));
            parts.add(imported + (imported == 1 ? " PDF übernommen" : " PDFs übernommen"));
            if (duplicates > 0) {
                parts.add(duplicates + " bereits vorhanden");
            }
            if (rejected > 0) {
                parts.add(rejected + " kein gültiges PDF");
            }
            if (unreadable > 0) {
                parts.add(unreadable + " nicht lesbar");
            }
            return String.join(", ", parts) + ".";
        }
    }
}
