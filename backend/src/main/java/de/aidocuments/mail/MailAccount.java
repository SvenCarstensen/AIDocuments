package de.aidocuments.mail;

import java.time.Instant;
import java.time.LocalDate;
/** Ein IMAP-Postfach, aus dem PDFs abgerufen werden (Tabelle mail_accounts). */
public class MailAccount {

    public enum SyncStatus {
        RUNNING, OK, ERROR
    }

    private String id;
    private String name;
    private String host;
    private int port;
    private MailSecurity security;
    private String username;
    private String passwordEncrypted;
    private String folder;
    /** ISO-Datum; ältere E-Mails werden übersprungen. */
    private String importSince;
    private boolean enabled;
    /** Welche E-Mails ausgewertet werden (Betreff, Absender); {@link MailFilter#NONE} = alle. */
    private MailFilter filter = MailFilter.NONE;
    /** Eigenes Abrufintervall in Minuten; {@code null} = das allgemeine (siehe {@link MailSchedule}). */
    private Integer intervalMinutes;
    /** IMAP-UIDVALIDITY und zuletzt verarbeitete UID – so wird jede E-Mail genau einmal geprüft. */
    private Long uidValidity;
    private Long lastUid;
    /**
     * Beginn des letzten erfolgreichen Abrufs. E-Mails, die davor empfangen wurden, gelten als geprüft – auch wenn
     * der UID-Stand verloren geht (neue UIDVALIDITY, zurückgesetzter Stand). {@code null} = noch nie erfolgreich.
     */
    private Instant checkedUntil;
    private Instant lastSyncAt;
    private SyncStatus lastSyncStatus;
    /** Sprachneutral abgelegt (siehe MsgCodec); Datensätze älterer Versionen enthalten deutschen Klartext. */
    private String lastSyncMessage;
    private Instant createdAt;

    protected MailAccount() {
    }

    public MailAccount(String id) {
        this.id = id;
        this.createdAt = Instant.now();
    }

    /** Für {@link MailAccountRepository}: Abrufstand und Anlagezeitpunkt aus der Tabellenzeile übernehmen. */
    void restoreState(Long uidValidity, Long lastUid, Instant checkedUntil, Instant lastSyncAt,
                      SyncStatus lastSyncStatus, String lastSyncMessage, Instant createdAt) {
        this.uidValidity = uidValidity;
        this.lastUid = lastUid;
        this.checkedUntil = checkedUntil;
        this.lastSyncAt = lastSyncAt;
        this.lastSyncStatus = lastSyncStatus;
        this.lastSyncMessage = lastSyncMessage;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public MailSecurity getSecurity() {
        return security;
    }

    public void setSecurity(MailSecurity security) {
        this.security = security;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordEncrypted() {
        return passwordEncrypted;
    }

    public void setPasswordEncrypted(String passwordEncrypted) {
        this.passwordEncrypted = passwordEncrypted;
    }

    public String getFolder() {
        return folder;
    }

    public void setFolder(String folder) {
        this.folder = folder;
    }

    public LocalDate getImportSince() {
        return importSince == null ? null : LocalDate.parse(importSince);
    }

    public void setImportSince(LocalDate importSince) {
        this.importSince = importSince == null ? null : importSince.toString();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public MailFilter getFilter() {
        return filter;
    }

    public void setFilter(MailFilter filter) {
        this.filter = filter == null ? MailFilter.NONE : filter;
    }

    public Integer getIntervalMinutes() {
        return intervalMinutes;
    }

    public void setIntervalMinutes(Integer intervalMinutes) {
        this.intervalMinutes = intervalMinutes;
    }

    public Long getUidValidity() {
        return uidValidity;
    }

    public Long getLastUid() {
        return lastUid;
    }

    public Instant getCheckedUntil() {
        return checkedUntil;
    }

    /** Von vorn prüfen: UID-Stand und Zeitmarke vergessen (anderes Postfach oder anderer Zeitraum). */
    public void resetCursor() {
        this.uidValidity = null;
        this.lastUid = null;
        this.checkedUntil = null;
    }

    public Instant getLastSyncAt() {
        return lastSyncAt;
    }

    public SyncStatus getLastSyncStatus() {
        return lastSyncStatus;
    }

    public String getLastSyncMessage() {
        return lastSyncMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
