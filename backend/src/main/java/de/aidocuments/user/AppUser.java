package de.aidocuments.user;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
/** Ein Benutzerkonto (Tabelle users; der Benutzername ist eindeutig). */
public class AppUser {

    private String id;
    private String username;
    private String passwordHash;
    private Role role;
    private boolean enabled;
    private Instant createdAt;
    private Instant lastLoginAt;
    /** Schlüssel der Zwei-Faktor-Anmeldung, verschlüsselt; {@code null} = nicht eingerichtet. */
    private String totpSecretEncrypted;
    /** Zuletzt verwendetes 30-Sekunden-Intervall – ein Code gilt nur einmal. */
    private Long totpLastStep;
    /** SHA-256 der noch unbenutzten Wiederherstellungscodes. */
    private List<String> recoveryCodeHashes = new ArrayList<>();

    protected AppUser() {
    }

    public AppUser(String id, String username, String passwordHash, Role role) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = true;
        this.createdAt = Instant.now();
    }

    /** Für {@link AppUserRepository}: Anlagezeitpunkt aus der Tabellenzeile übernehmen. */
    void restoreCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public boolean isTwoFactorEnabled() {
        return totpSecretEncrypted != null;
    }

    public String getTotpSecretEncrypted() {
        return totpSecretEncrypted;
    }

    public Long getTotpLastStep() {
        return totpLastStep;
    }

    public void setTotpLastStep(Long totpLastStep) {
        this.totpLastStep = totpLastStep;
    }

    public List<String> getRecoveryCodeHashes() {
        return recoveryCodeHashes;
    }

    /** Schaltet die Zwei-Faktor-Anmeldung ein ({@code secret} verschlüsselt) oder mit {@code null} ab. */
    public void setTwoFactor(String encryptedSecret, Long lastStep, List<String> recoveryHashes) {
        this.totpSecretEncrypted = encryptedSecret;
        this.totpLastStep = lastStep;
        this.recoveryCodeHashes = recoveryHashes == null ? new ArrayList<>() : new ArrayList<>(recoveryHashes);
    }
}
