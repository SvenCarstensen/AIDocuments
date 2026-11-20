package de.aidocuments.mail;

import java.time.Instant;
import java.time.LocalDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "mail_accounts")
public class MailAccount {

    public enum SyncStatus {
        RUNNING, OK, ERROR
    }

    @Id
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
    /** IMAP-UIDVALIDITY und zuletzt verarbeitete UID – so wird jede E-Mail genau einmal geprüft. */
    private Long uidValidity;
    private Long lastUid;
    private Instant lastSyncAt;
    private SyncStatus lastSyncStatus;
    private String lastSyncMessage;
    private Instant createdAt;

    protected MailAccount() {
    }

    public MailAccount(String id) {
        this.id = id;
        this.createdAt = Instant.now();
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

    public Long getUidValidity() {
        return uidValidity;
    }

    public Long getLastUid() {
        return lastUid;
    }

    public void resetCursor() {
        this.uidValidity = null;
        this.lastUid = null;
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
