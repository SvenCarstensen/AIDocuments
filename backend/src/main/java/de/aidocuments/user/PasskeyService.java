package de.aidocuments.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.converter.exception.DataConversionException;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.AuthenticatorTransport;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import com.webauthn4j.util.Base64UrlUtil;
import com.webauthn4j.verifier.exception.VerificationException;
import de.aidocuments.persistence.Db;
import de.aidocuments.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Passkeys (WebAuthn): Anmeldung ohne Passwort mit einem Schlüssel, der auf dem Gerät oder im Passwortmanager liegt
 * und dort per Fingerabdruck, Gesicht oder Geräte-PIN freigegeben wird. Ein Passkey gilt nur für die Adresse, unter
 * der er angelegt wurde – eine nachgemachte Seite kann ihn nicht verwenden.
 *
 * <p>Die Herausforderung (Challenge) liegt in der Sitzung und gilt einmal, höchstens {@link #CHALLENGE_TIME}. Die
 * Adresse (Relying Party) ergibt sich aus der Anfrage; hinter einem Proxy aus dessen {@code X-Forwarded-*}-Angaben,
 * sofern er vertrauenswürdig ist.
 */
@Service
public class PasskeyService {

    private static final Logger log = LoggerFactory.getLogger(PasskeyService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();
    static final Duration CHALLENGE_TIME = Duration.ofMinutes(5);
    static final int MAX_PER_USER = 20;
    private static final String LOGIN_CHALLENGE = "aidocuments.passkeyLoginChallenge";
    private static final String REGISTRATION_CHALLENGE = "aidocuments.passkeyRegistrationChallenge";
    /** ES256 (die meisten Geräte), EdDSA, RS256 (Windows Hello) */
    private static final List<PublicKeyCredentialParameters> ALGORITHMS = List.of(
            new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256),
            new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.EdDSA),
            new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.RS256));

    private record Challenge(byte[] value, String userId, Instant expiresAt) implements Serializable {
    }

    /** Ein gespeicherter Passkey. */
    record Passkey(String id, String userId, String credentialId, byte[] credentialData, long signCount,
                   boolean uvInitialized, boolean backupEligible, boolean backupState, Set<String> transports,
                   String name, Instant createdAt, Instant lastUsedAt) {
    }

    public record PasskeyDto(String id, String name, Instant createdAt, Instant lastUsedAt, boolean synced) {
    }

    private final JdbcClient jdbc;
    private final Db db;
    private final ObjectConverter converter = new ObjectConverter();
    private final AttestedCredentialDataConverter credentialConverter = new AttestedCredentialDataConverter(converter);
    // ohne Prüfung der Herstellerbescheinigung (Attestation): Passkeys aus Passwortmanagern haben keine
    private final WebAuthnManager webAuthn = WebAuthnManager.createNonStrictWebAuthnManager(converter);

    public PasskeyService(Db db) {
        this.db = db;
        this.jdbc = db.jdbc();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Anlegen
    // ---------------------------------------------------------------------------------------------------------

    /** Die Angaben für {@code navigator.credentials.create()} – im Format von {@code PublicKeyCredentialCreationOptionsJSON}. */
    public Map<String, Object> registrationOptions(AppUser user, HttpServletRequest request) {
        List<Passkey> existing = findByUser(user.getId());
        if (existing.size() >= MAX_PER_USER) {
            throw ApiException.conflict("error.passkey.tooMany", MAX_PER_USER);
        }
        byte[] challenge = challenge(request.getSession(), REGISTRATION_CHALLENGE, user.getId());
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("challenge", Base64UrlUtil.encodeToString(challenge));
        options.put("rp", Map.of("id", rpId(request), "name", "AIDocuments"));
        options.put("user", Map.of("id", Base64UrlUtil.encodeToString(user.getId().getBytes(StandardCharsets.UTF_8)),
                "name", user.getUsername(), "displayName", user.getUsername()));
        options.put("pubKeyCredParams", ALGORITHMS.stream()
                .map(p -> Map.of("type", "public-key", "alg", p.getAlg().getValue())).toList());
        options.put("excludeCredentials", existing.stream().map(PasskeyService::descriptor).toList());
        options.put("authenticatorSelection", Map.of("residentKey", "required", "requireResidentKey", true,
                "userVerification", "required"));
        options.put("attestation", "none");
        options.put("timeout", CHALLENGE_TIME.toMillis());
        return options;
    }

    /** Prüft die Antwort des Browsers ({@code PublicKeyCredential} als JSON) und speichert den Passkey. */
    public PasskeyDto register(AppUser user, String name, Object credential, HttpServletRequest request) {
        byte[] challenge = take(request.getSession(false), REGISTRATION_CHALLENGE, user.getId());
        RegistrationData data;
        try {
            data = webAuthn.verifyRegistrationResponseJSON(JSON.writeValueAsString(credential),
                    new RegistrationParameters(serverProperty(request, challenge), ALGORITHMS, true, true));
        } catch (DataConversionException | VerificationException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("Passkey für „{}“ nicht angelegt: {}", user.getUsername(), e.toString());
            throw ApiException.badRequest("error.passkey.invalid");
        }
        AuthenticatorData<?> authenticatorData = data.getAttestationObject().getAuthenticatorData();
        AttestedCredentialData attested = authenticatorData.getAttestedCredentialData();
        Set<String> transports = data.getTransports() == null ? Set.of()
                : data.getTransports().stream().map(AuthenticatorTransport::getValue).collect(Collectors.toSet());
        Passkey passkey = new Passkey(UUID.randomUUID().toString(), user.getId(),
                Base64UrlUtil.encodeToString(attested.getCredentialId()), credentialConverter.convert(attested),
                authenticatorData.getSignCount(), authenticatorData.isFlagUV(), authenticatorData.isFlagBE(),
                authenticatorData.isFlagBS(), transports, cleanName(name), Instant.now(), null);
        try {
            insert(passkey);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("error.passkey.exists");
        }
        log.info("Passkey „{}“ für „{}“ angelegt", passkey.name(), user.getUsername());
        return dto(passkey);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Anmelden und bestätigen
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Die Angaben für {@code navigator.credentials.get()}: ohne Liste der Schlüssel – der Browser bietet die
     * Passkeys an, die er für diese Adresse kennt, und nennt dabei auch das Konto.
     */
    public Map<String, Object> loginOptions(HttpServletRequest request) {
        // vor der Anmeldung: nur eine kurzlebige Sitzung für die Challenge
        byte[] challenge = challenge(de.aidocuments.security.SessionTimeouts.anonymous(request.getSession()),
                LOGIN_CHALLENGE, null);
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("challenge", Base64UrlUtil.encodeToString(challenge));
        options.put("rpId", rpId(request));
        options.put("userVerification", "required");
        options.put("timeout", CHALLENGE_TIME.toMillis());
        return options;
    }

    /** Welchem Konto der Passkey gehört, den der Browser nennt – noch ohne Prüfung; {@code null} = unbekannt. */
    public String ownerOf(String credentialJson) {
        try {
            AuthenticationData parsed = webAuthn.parseAuthenticationResponseJSON(credentialJson);
            return find(Base64UrlUtil.encodeToString(parsed.getCredentialId())).map(Passkey::userId).orElse(null);
        } catch (DataConversionException | IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Prüft die Anmeldung (Unterschrift, Adresse, Challenge, Freigabe per Fingerabdruck/PIN, Zähler).
     *
     * @return die ID des Kontos
     */
    public String verify(String credentialJson, HttpServletRequest request) {
        byte[] challenge = take(request.getSession(false), LOGIN_CHALLENGE, null);
        try {
            AuthenticationData parsed = webAuthn.parseAuthenticationResponseJSON(credentialJson);
            Passkey passkey = find(Base64UrlUtil.encodeToString(parsed.getCredentialId()))
                    .orElseThrow(() -> ApiException.of(HttpStatus.UNAUTHORIZED, "error.passkey.unknown"));
            if (parsed.getUserHandle() != null && !Arrays.equals(parsed.getUserHandle(),
                    passkey.userId().getBytes(StandardCharsets.UTF_8))) {
                throw ApiException.of(HttpStatus.UNAUTHORIZED, "error.passkey.invalid");
            }
            AttestedCredentialData attested = credentialConverter.convert(passkey.credentialData());
            CredentialRecordImpl record = new CredentialRecordImpl(new NoneAttestationStatement(),
                    passkey.uvInitialized(), passkey.backupEligible(), passkey.backupState(), passkey.signCount(),
                    attested, null, null, null,
                    passkey.transports().stream().map(AuthenticatorTransport::create).collect(Collectors.toSet()));
            AuthenticationData verified = webAuthn.verify(parsed, new AuthenticationParameters(
                    serverProperty(request, challenge), record, null, true, true));
            AuthenticatorData<?> authenticatorData = verified.getAuthenticatorData();
            jdbc.sql("UPDATE passkeys SET sign_count = ?, backup_state = ?, uv_initialized = ?, last_used_at = ? "
                            + "WHERE id = ?")
                    .param(authenticatorData.getSignCount()).param(authenticatorData.isFlagBS())
                    .param(passkey.uvInitialized() || authenticatorData.isFlagUV())
                    .param(Instant.now().toEpochMilli()).param(passkey.id()).update();
            return passkey.userId();
        } catch (DataConversionException | VerificationException | IllegalArgumentException e) {
            log.warn("Anmeldung per Passkey von {} abgelehnt: {}", request.getRemoteAddr(), e.toString());
            throw ApiException.of(HttpStatus.UNAUTHORIZED, "error.passkey.invalid");
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Verwalten
    // ---------------------------------------------------------------------------------------------------------

    public List<PasskeyDto> list(String userId) {
        return findByUser(userId).stream().map(PasskeyService::dto).toList();
    }

    public boolean hasAny(String userId) {
        return jdbc.sql("SELECT COUNT(*) FROM passkeys WHERE user_id = ?").param(userId).query(Long.class).single() > 0;
    }

    public void delete(String userId, String id) {
        int removed = jdbc.sql("DELETE FROM passkeys WHERE id = ? AND user_id = ?").param(id).param(userId).update();
        if (removed == 0) {
            throw ApiException.notFound("error.passkey.notFound");
        }
        log.info("Passkey {} von Konto {} entfernt", id, userId);
    }

    /** Beim Löschen eines Kontos. */
    public void deleteAll(String userId) {
        jdbc.sql("DELETE FROM passkeys WHERE user_id = ?").param(userId).update();
    }

    // ---------------------------------------------------------------------------------------------------------

    /** Die Adresse ohne Schema und Port – darauf ist ein Passkey festgelegt. */
    static String rpId(HttpServletRequest request) {
        return request.getServerName();
    }

    private static ServerProperty serverProperty(HttpServletRequest request, byte[] challenge) {
        return new ServerProperty(Origin.create(origin(request)), rpId(request), new DefaultChallenge(challenge));
    }

    /** {@code https://docs.example.org} bzw. mit Port, wenn er vom üblichen abweicht. */
    static String origin(HttpServletRequest request) {
        String scheme = request.getScheme();
        int port = request.getServerPort();
        boolean defaultPort = port <= 0 || ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
        return scheme + "://" + request.getServerName() + (defaultPort ? "" : ":" + port);
    }

    private static byte[] challenge(HttpSession session, String attribute, String userId) {
        byte[] value = new byte[32];
        RANDOM.nextBytes(value);
        session.setAttribute(attribute, new Challenge(value, userId, Instant.now().plus(CHALLENGE_TIME)));
        return value;
    }

    /** Die gemerkte Challenge – einmal; abgelaufen oder für ein anderes Konto zählt sie nicht. */
    private static byte[] take(HttpSession session, String attribute, String userId) {
        Object stored = session == null ? null : session.getAttribute(attribute);
        if (session != null) {
            session.removeAttribute(attribute);
        }
        if (!(stored instanceof Challenge challenge) || challenge.expiresAt().isBefore(Instant.now())
                || (userId != null && !userId.equals(challenge.userId()))) {
            throw ApiException.badRequest("error.passkey.expired");
        }
        return challenge.value();
    }

    private static String cleanName(String name) {
        String cleaned = name == null ? "" : name.replaceAll("\\p{Cntrl}", " ").strip();
        if (cleaned.length() > 100) {
            cleaned = cleaned.substring(0, 100).strip();
        }
        return cleaned.isEmpty() ? "Passkey" : cleaned;
    }

    private static Map<String, Object> descriptor(Passkey passkey) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("type", "public-key");
        descriptor.put("id", passkey.credentialId());
        if (!passkey.transports().isEmpty()) {
            descriptor.put("transports", List.copyOf(passkey.transports()));
        }
        return descriptor;
    }

    private static PasskeyDto dto(Passkey passkey) {
        return new PasskeyDto(passkey.id(), passkey.name(), passkey.createdAt(), passkey.lastUsedAt(),
                passkey.backupState());
    }

    private Optional<Passkey> find(String credentialId) {
        return jdbc.sql("SELECT * FROM passkeys WHERE credential_id = ?").param(credentialId)
                .query(PasskeyService::map).optional();
    }

    private List<Passkey> findByUser(String userId) {
        return jdbc.sql("SELECT * FROM passkeys WHERE user_id = ? ORDER BY created_at").param(userId)
                .query(PasskeyService::map).list();
    }

    private void insert(Passkey passkey) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", passkey.id());
        c.put("user_id", passkey.userId());
        c.put("credential_id", passkey.credentialId());
        c.put("credential_data", Base64.getEncoder().encodeToString(passkey.credentialData()));
        c.put("sign_count", passkey.signCount());
        c.put("uv_initialized", passkey.uvInitialized());
        c.put("backup_eligible", passkey.backupEligible());
        c.put("backup_state", passkey.backupState());
        c.put("transports", passkey.transports().isEmpty() ? null : String.join(",", passkey.transports()));
        c.put("name", passkey.name());
        c.put("created_at", passkey.createdAt().toEpochMilli());
        db.insert("passkeys", c);
    }

    private static Passkey map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        String transports = rs.getString("transports");
        return new Passkey(rs.getString("id"), rs.getString("user_id"), rs.getString("credential_id"),
                Base64.getDecoder().decode(rs.getString("credential_data")), rs.getLong("sign_count"),
                rs.getBoolean("uv_initialized"), rs.getBoolean("backup_eligible"), rs.getBoolean("backup_state"),
                transports == null || transports.isBlank() ? Set.of() : Set.of(transports.split(",")),
                rs.getString("name"), Db.instant(rs, "created_at"), Db.instant(rs, "last_used_at"));
    }
}
