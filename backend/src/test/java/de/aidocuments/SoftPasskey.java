package de.aidocuments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webauthn4j.converter.AttestationObjectConverter;
import com.webauthn4j.converter.AuthenticatorDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.attestation.AttestationObject;
import com.webauthn4j.data.attestation.authenticator.AAGUID;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData;
import com.webauthn4j.data.attestation.authenticator.EC2COSEKey;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import com.webauthn4j.data.extension.authenticator.RegistrationExtensionAuthenticatorOutput;
import com.webauthn4j.util.Base64UrlUtil;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ein Passkey für die Tests: tut, was Browser und Authenticator bei {@code navigator.credentials.create()} und
 * {@code get()} tun – mit einem P-256-Schlüssel, Bestätigung „none“ und gesetzten Flags für Anwesenheit und Freigabe.
 */
final class SoftPasskey {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectConverter CONVERTER = new ObjectConverter();

    private final KeyPair keys;
    private final byte[] credentialId = new byte[16];
    private byte[] userHandle;
    private long counter;

    SoftPasskey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        keys = generator.generateKeyPair();
        new SecureRandom().nextBytes(credentialId);
    }

    /** Antwort auf die Optionen von {@code /api/auth/passkeys/options}, als JSON wie {@code PublicKeyCredential.toJSON()}. */
    Map<String, Object> create(String optionsJson, String origin) throws Exception {
        JsonNode options = JSON.readTree(optionsJson);
        String rpId = options.path("rp").path("id").asText();
        userHandle = Base64UrlUtil.decode(options.path("user").path("id").asText());
        byte[] clientData = clientData("webauthn.create", options.path("challenge").asText(), origin);
        AttestedCredentialData attested = new AttestedCredentialData(AAGUID.ZERO, credentialId,
                EC2COSEKey.create((ECPublicKey) keys.getPublic(), COSEAlgorithmIdentifier.ES256));
        AuthenticatorData<RegistrationExtensionAuthenticatorOutput> authenticatorData = new AuthenticatorData<>(
                sha256(rpId.getBytes(StandardCharsets.UTF_8)),
                (byte) (AuthenticatorData.BIT_UP | AuthenticatorData.BIT_UV | AuthenticatorData.BIT_AT), counter,
                attested);
        byte[] attestationObject = new AttestationObjectConverter(CONVERTER).convertToBytes(
                new AttestationObject(authenticatorData, new NoneAttestationStatement()));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("clientDataJSON", Base64UrlUtil.encodeToString(clientData));
        response.put("attestationObject", Base64UrlUtil.encodeToString(attestationObject));
        response.put("transports", List.of("internal"));
        return credential(response);
    }

    /** Antwort auf die Optionen von {@code /api/auth/passkey/options} (JSON-Text für den Anfragekörper). */
    String get(String optionsJson, String origin) throws Exception {
        JsonNode options = JSON.readTree(optionsJson);
        return get(options.path("challenge").asText(), options.path("rpId").asText(), origin);
    }

    String get(String challenge, String rpId, String origin) throws Exception {
        counter++;
        byte[] clientData = clientData("webauthn.get", challenge, origin);
        byte[] authenticatorData = new AuthenticatorDataConverter(CONVERTER).convert(new AuthenticatorData<>(
                sha256(rpId.getBytes(StandardCharsets.UTF_8)),
                (byte) (AuthenticatorData.BIT_UP | AuthenticatorData.BIT_UV), counter));
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keys.getPrivate());
        signer.update(ByteBuffer.allocate(authenticatorData.length + 32).put(authenticatorData)
                .put(sha256(clientData)).array());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("clientDataJSON", Base64UrlUtil.encodeToString(clientData));
        response.put("authenticatorData", Base64UrlUtil.encodeToString(authenticatorData));
        response.put("signature", Base64UrlUtil.encodeToString(signer.sign()));
        response.put("userHandle", Base64UrlUtil.encodeToString(userHandle));
        return JSON.writeValueAsString(credential(response));
    }

    private Map<String, Object> credential(Map<String, Object> response) {
        Map<String, Object> credential = new LinkedHashMap<>();
        credential.put("id", Base64UrlUtil.encodeToString(credentialId));
        credential.put("rawId", Base64UrlUtil.encodeToString(credentialId));
        credential.put("type", "public-key");
        credential.put("response", response);
        credential.put("clientExtensionResults", Map.of());
        credential.put("authenticatorAttachment", "platform");
        return credential;
    }

    private static byte[] clientData(String type, String challenge, String origin) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", type);
        data.put("challenge", challenge);
        data.put("origin", origin);
        data.put("crossOrigin", false);
        return JSON.writeValueAsBytes(data);
    }

    private static byte[] sha256(byte[] value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value);
    }
}
