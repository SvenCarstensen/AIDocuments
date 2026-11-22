package de.aidocuments.mail;

import de.aidocuments.document.DocumentService;
import de.aidocuments.document.DocumentService.MailOrigin;
import de.aidocuments.document.DocumentService.Outcome;
import de.aidocuments.document.DocumentSource;
import de.aidocuments.i18n.Localizable;
import de.aidocuments.i18n.Msg;
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
    /** Ab so vielen fehlgeschlagenen Abrufen in Folge werden die Admins benachrichtigt. */
    public static final int FAILURES_BEFORE_NOTICE = 3;
    /** Höchstens so viele PDF-Anhänge je Mail. */
    static final int MAX_ATTACHMENTS = 25;
    /**
     * Spielraum für unterschiedlich gehende Uhren von Mailserver und Anwendung: E-Mails, die kurz vor dem letzten
     * Abruf empfangen wurden, werden noch einmal angesehen (schon übernommene erkennt der UID-Stand bzw. die
     * Duplikaterkennung).
     */
    public static final java.time.Duration CLOCK_TOLERANCE = java.time.Duration.ofMinutes(5);

    /** Ergebnis des Verbindungstests; die Meldung übersetzt der Controller in die Sprache der Anfrage. */
    public record TestResult(boolean ok, Msg message) {
    }

    private final MailAccountRepository accounts;
    private final MailAccountService accountService;
    private final DocumentService documentService;
    private final MailSchedule schedule;
    private final de.aidocuments.notify.Notifications notifications;
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public MailSyncService(MailAccountRepository accounts, MailAccountService accountService,
                           DocumentService documentService, MailSchedule schedule,
                           de.aidocuments.notify.Notifications notifications) {
        this.accounts = accounts;
        this.notifications = notifications;
        this.accountService = accountService;
        this.documentService = documentService;
        this.schedule = schedule;
    }

    /** Ruft nacheinander alle aktiven Postfächer ab, deren Intervall seit dem letzten Abruf verstrichen ist. */
    public void syncDue() {
        Instant now = Instant.now();
        accounts.findByEnabledTrue().stream()
                .filter(account -> schedule.isDue(account, now))
                .forEach(account -> sync(account.getId()));
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
            Instant startedAt = Instant.now();
            // auch ein von Hand gestarteter Abruf beginnt das Intervall neu
            schedule.started(accountId, startedAt);
            try {
                Counters counters = fetch(account);
                // erst nach vollständigem Erfolg weiterrücken – sonst gingen E-Mails eines abgebrochenen Abrufs verloren
                accountService.saveCheckedUntil(accountId, startedAt);
                accountService.markFinished(accountId, MailAccount.SyncStatus.OK, counters.summary());
                accounts.resetFailures(accountId);
                if (counters.imported > 0) {
                    log.info("Postfach {}: {} neue E-Mails geprüft, {} PDFs übernommen, {} bereits vorhanden, "
                                    + "{} kein gültiges PDF, {} nicht lesbar", account.getName(), counters.checked,
                            counters.imported, counters.duplicates, counters.rejected, counters.unreadable);
                }
            } catch (Exception e) {
                log.warn("Abruf von Postfach {} fehlgeschlagen: {}", account.getName(), e.toString());
                Msg reason = describe(e);
                accountService.markFinished(accountId, MailAccount.SyncStatus.ERROR, List.of(reason));
                failed(account, reason);
            }
        } finally {
            running.remove(accountId);
        }
    }

    /**
     * Ein einzelner Fehlschlag (Netz kurz weg, Server neu gestartet) ist kein Grund zur Sorge; erst wenn ein Postfach
     * sich wiederholt nicht abrufen lässt, erfahren es die Admins – einmal je Fehlerserie.
     */
    private void failed(MailAccount account, Msg reason) {
        try {
            int failures = accounts.recordFailure(account.getId());
            if (failures >= FAILURES_BEFORE_NOTICE && !accounts.failureNotified(account.getId())
                    && notifications.mailboxFailing(account.getName(), reason, failures)) {
                accounts.markFailureNotified(account.getId(), Instant.now());
            }
        } catch (RuntimeException e) {
            log.warn("Fehlschlag von Postfach {} nicht gemeldet: {}", account.getName(), e.toString());
        }
    }

    /** Baut testweise eine Verbindung auf und öffnet den Ordner. */
    public TestResult test(MailAccount account, String password) {
        try (Store store = connect(account, password)) {
            Folder folder = store.getFolder(account.getFolder());
            if (!folder.exists()) {
                return new TestResult(false, Msg.of("mailbox.test.folderMissing", account.getFolder()));
            }
            folder.open(Folder.READ_ONLY);
            int count = folder.getMessageCount();
            folder.close(false);
            return new TestResult(true, Msg.of("mailbox.test.success", account.getFolder(), count));
        } catch (Exception e) {
            return new TestResult(false, describe(e));
        }
    }

    private Counters fetch(MailAccount account) throws MessagingException {
        Counters counters = new Counters();
        try (Store store = connect(account, accountService.password(account))) {
            Folder folder = store.getFolder(account.getFolder());
            if (!folder.exists()) {
                throw new MissingFolderException(account.getFolder());
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
                Instant receivedAfter = account.getCheckedUntil() == null ? null
                        : account.getCheckedUntil().minus(CLOCK_TOLERANCE);

                for (int start = 0; start < fresh.size(); start += FETCH_BATCH) {
                    List<Message> batch = fresh.subList(start, Math.min(fresh.size(), start + FETCH_BATCH));
                    folder.fetch(batch.toArray(Message[]::new), structure);
                    for (Message message : batch) {
                        long uid = uidFolder.getUID(message);
                        try {
                            importMessage(account, message, importSince, receivedAfter, counters);
                        } catch (FolderClosedException | StoreClosedException
                                 | de.aidocuments.document.FileStorage.StorageFullException e) {
                            // Verbindung weg oder Platte voll: abbrechen, beim nächsten Lauf hier weitermachen
                            throw e;
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

    /**
     * @param receivedAfter E-Mails, die davor empfangen wurden, sind schon bei einem früheren Abruf geprüft worden
     *                      und werden übergangen, ohne mitgezählt zu werden; {@code null} = noch nie abgerufen
     */
    private void importMessage(MailAccount account, Message message, Instant importSince, Instant receivedAfter,
                               Counters counters) throws MessagingException, IOException {
        Date received = message.getReceivedDate() != null ? message.getReceivedDate() : message.getSentDate();
        Instant receivedAt = received != null ? received.toInstant() : null;
        if (receivedAfter != null && receivedAt != null && receivedAt.isBefore(receivedAfter)) {
            return;
        }
        counters.checked++;
        if (importSince != null && receivedAt != null && receivedAt.isBefore(importSince)) {
            return;
        }
        // erst Betreff und Absender prüfen (stehen schon im abgerufenen Umschlag), dann die Anhänge laden
        if (!account.getFilter().matches(message.getSubject(), senders(message))) {
            counters.filtered++;
            return;
        }
        List<Part> pdfParts = new ArrayList<>();
        collectDocumentParts(message, pdfParts, 0, false);
        if (pdfParts.isEmpty()) {
            return;
        }
        MailOrigin origin = new MailOrigin(account.getId(), clip(message.getSubject(), 1000),
                clip(sender(message), 500), receivedAt);
        if (pdfParts.size() > MAX_ATTACHMENTS) {
            // Schutz vor Mails mit Hunderten Anhängen (jeder kostet Speicherplatz und eine KI-Analyse)
            log.warn("Mail mit {} Anhängen – nur die ersten {} werden übernommen", pdfParts.size(), MAX_ATTACHMENTS);
            counters.rejected += pdfParts.size() - MAX_ATTACHMENTS;
            pdfParts = pdfParts.subList(0, MAX_ATTACHMENTS);
        }
        for (Part part : pdfParts) {
            try (InputStream in = part.getInputStream()) {
                Outcome outcome = documentService.ingest(in, filename(part), DocumentSource.MAIL, origin,
                        account.getAreaId()).outcome();
                switch (outcome) {
                    case CREATED -> counters.imported++;
                    case DUPLICATE -> counters.duplicates++;
                    case REJECTED -> counters.rejected++;
                    case IGNORED -> { }
                }
            }
        }
    }

    /**
     * Sammelt PDF-Anhänge und Fotos. Bilder, die nur im HTML-Text eingebettet sind ({@code multipart/related}: Logos,
     * Signaturen, Banner), bleiben außen vor, sofern sie nicht ausdrücklich als Anhang verschickt wurden; was danach
     * noch zu klein ist, übergeht die Aufnahme ({@link DocumentService#ingest}).
     */
    private static void collectDocumentParts(Part part, List<Part> result, int depth, boolean inRelated)
            throws MessagingException, IOException {
        if (depth > MAX_NESTING) {
            return;
        }
        if (part.isMimeType("multipart/*")) {
            boolean related = inRelated || part.isMimeType("multipart/related");
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                collectDocumentParts(multipart.getBodyPart(i), result, depth + 1, related);
            }
        } else if (part.isMimeType("message/rfc822")) {
            if (part.getContent() instanceof Part nested) {
                collectDocumentParts(nested, result, depth + 1, false);
            }
        } else if (isPdf(part)) {
            result.add(part);
        } else if (isImage(part) && (!inRelated || Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()))) {
            result.add(part);
        }
    }

    private static final java.util.regex.Pattern IMAGE_NAME =
            java.util.regex.Pattern.compile("(?i).*\\.(jpe?g|png|hei[cf])$");

    private static boolean isImage(Part part) throws MessagingException {
        if (part.isMimeType("image/jpeg") || part.isMimeType("image/pjpeg") || part.isMimeType("image/png")
                || part.isMimeType("image/heic") || part.isMimeType("image/heif")) {
            return true;
        }
        String name = part.getFileName();
        return name != null && IMAGE_NAME.matcher(decode(name)).matches();
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
            return isPdf(part) ? "Dokument.pdf" : "Foto.jpg";
        }
        String decoded = decode(name);
        // Bilder behalten ihre Endung – die Aufnahme macht daraus "<Name>.pdf"
        return decoded.toLowerCase(Locale.ROOT).endsWith(".pdf") || IMAGE_NAME.matcher(decoded).matches()
                ? decoded : decoded + ".pdf";
    }

    private static String decode(String headerValue) {
        try {
            return MimeUtility.decodeText(headerValue);
        } catch (UnsupportedEncodingException e) {
            return headerValue;
        }
    }

    /** Alle Absender als „Name &lt;Adresse&gt;“ – für den Absenderfilter. */
    private static List<String> senders(Message message) throws MessagingException {
        Address[] from = message.getFrom();
        if (from == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Address address : from) {
            result.add(address instanceof InternetAddress internet ? internet.toUnicodeString() : address.toString());
        }
        return result;
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

    /**
     * Übersetzt technische Fehler in eine Meldung, mit der der Anwender etwas anfangen kann – sprachneutral,
     * denn beim Abruf im Hintergrund steht noch nicht fest, wer sie liest. Unbekannte Fehler werden mit der
     * Meldung ihrer Quelle (Mailserver, Bibliothek) wörtlich durchgereicht.
     */
    static Msg describe(Exception e) {
        if (e instanceof Localizable localizable) {
            return localizable.msg();
        }
        if (e instanceof AuthenticationFailedException) {
            return Msg.of("mailbox.error.authFailed");
        }
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof UnknownHostException) {
                return Msg.of("mailbox.error.unknownHost", cause.getMessage());
            }
            if (cause instanceof ConnectException || cause instanceof SocketTimeoutException) {
                return Msg.of("mailbox.error.noResponse");
            }
            if (cause instanceof SSLException) {
                return Msg.of("mailbox.error.ssl", cause.getMessage());
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return Msg.literal(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
    }

    private static final class MissingFolderException extends MessagingException implements Localizable {

        private final Msg msg;

        MissingFolderException(String folder) {
            super("Ordner " + folder + " existiert nicht");
            this.msg = Msg.of("mailbox.error.folderMissing", folder);
        }

        @Override
        public Msg msg() {
            return msg;
        }
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
        int filtered;

        /**
         * Das Ergebnis als Folge von Sätzen der Form „Bezeichnung: Anzahl.“ – so stimmt es in jeder Sprache
         * für jede Anzahl, ohne Einzahl- und Mehrzahlformen.
         */
        List<Msg> summary() {
            if (checked == 0) {
                return List.of(Msg.of("mailbox.sync.none"));
            }
            List<Msg> sentences = new ArrayList<>();
            sentences.add(Msg.of("mailbox.sync.checked", checked));
            if (filtered > 0) {
                sentences.add(Msg.of("mailbox.sync.filtered", filtered));
            }
            sentences.add(Msg.of("mailbox.sync.imported", imported));
            if (duplicates > 0) {
                sentences.add(Msg.of("mailbox.sync.duplicates", duplicates));
            }
            if (rejected > 0) {
                sentences.add(Msg.of("mailbox.sync.rejected", rejected));
            }
            if (unreadable > 0) {
                sentences.add(Msg.of("mailbox.sync.unreadable", unreadable));
            }
            return sentences;
        }
    }
}
