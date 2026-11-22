package de.aidocuments.document;

import static de.aidocuments.persistence.Db.instant;
import static de.aidocuments.persistence.Db.millis;

import de.aidocuments.persistence.Db;
import de.aidocuments.security.SecretCipher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Links, mit denen nicht angemeldete Personen genau ein Dokument öffnen (Tabelle {@code document_shares}).
 *
 * <p>Der Link enthält 32 Zufallsbytes (256 Bit) – erraten lässt er sich nicht. Gesucht wird nur über seinen SHA-256-Wert;
 * im Klartext steht er nirgends in der Datenbank, nur verschlüsselt (secret.key), damit angemeldete Anwender ihn erneut
 * kopieren können.
 */
@Component
public class DocumentShares {

    /** Format eines Links: 32 Bytes in Base64url ohne Auffüllung. */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    public record Share(String id, String documentId, String recipient, boolean canEdit, Instant createdAt,
                        String createdBy, Instant expiresAt, Instant lastAccessAt, int accessCount,
                        String tokenEncrypted) {

        public boolean expired(Instant now) {
            return expiresAt != null && !expiresAt.isAfter(now);
        }
    }

    /** Ein neu angelegter Link samt Token (nur hier und über {@link #token} im Klartext). */
    public record Created(Share share, String token) {
    }

    private final Db db;
    private final JdbcClient jdbc;
    private final SecretCipher cipher;
    private final SecureRandom random = new SecureRandom();

    public DocumentShares(Db db, SecretCipher cipher) {
        this.db = db;
        this.jdbc = db.jdbc();
        this.cipher = cipher;
    }

    public Created create(String documentId, String recipient, boolean canEdit, Instant expiresAt, String createdBy) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Share share = new Share(UUID.randomUUID().toString(), documentId, recipient, canEdit, Instant.now(), createdBy,
                expiresAt, null, 0, cipher.encrypt(token));
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", share.id());
        c.put("document_id", documentId);
        c.put("token_hash", hash(token));
        c.put("token_encrypted", share.tokenEncrypted());
        c.put("recipient", recipient);
        c.put("can_edit", canEdit);
        c.put("created_at", millis(share.createdAt()));
        c.put("created_by", createdBy);
        c.put("expires_at", millis(expiresAt));
        c.put("access_count", 0);
        db.insert("document_shares", c);
        return new Created(share, token);
    }

    /** Alle Links eines Dokuments, die neuesten zuerst – auch abgelaufene. */
    public List<Share> list(String documentId) {
        return jdbc.sql("SELECT * FROM document_shares WHERE document_id = ? ORDER BY created_at DESC")
                .param(documentId).query(DocumentShares::map).list();
    }

    /** Der Link im Klartext – für angemeldete Anwender zum erneuten Kopieren. */
    public String token(Share share) {
        return cipher.decrypt(share.tokenEncrypted());
    }

    /** Sucht den Link zu einem Token; ein falsch geformtes Token führt gar nicht erst zu einer Abfrage. */
    public Optional<Share> findByToken(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT * FROM document_shares WHERE token_hash = ?").param(hash(token))
                .query(DocumentShares::map).optional();
    }

    public boolean revoke(String documentId, String shareId) {
        return jdbc.sql("DELETE FROM document_shares WHERE id = ? AND document_id = ?")
                .param(shareId).param(documentId).update() > 0;
    }

    public void recordAccess(String shareId) {
        jdbc.sql("UPDATE document_shares SET last_access_at = ?, access_count = access_count + 1 WHERE id = ?")
                .param(millis(Instant.now())).param(shareId).update();
    }

    /** Beim endgültigen Löschen eines Dokuments. */
    public void forget(String documentId) {
        jdbc.sql("DELETE FROM document_shares WHERE document_id = ?").param(documentId).update();
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Share map(ResultSet rs, int row) throws SQLException {
        return new Share(rs.getString("id"), rs.getString("document_id"), rs.getString("recipient"),
                rs.getBoolean("can_edit"), instant(rs, "created_at"), rs.getString("created_by"),
                instant(rs, "expires_at"), instant(rs, "last_access_at"), rs.getInt("access_count"),
                rs.getString("token_encrypted"));
    }
}
