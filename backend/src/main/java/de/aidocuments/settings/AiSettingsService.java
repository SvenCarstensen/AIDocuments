package de.aidocuments.settings;

import de.aidocuments.config.AppProperties;
import de.aidocuments.security.SecretCipher;
import de.aidocuments.web.ApiException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.Set;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/**
 * Liefert die wirksame KI-Konfiguration: in der Oberfläche gespeicherte Werte haben Vorrang,
 * sonst gelten die Vorgaben aus application.yml bzw. den Umgebungsvariablen.
 */
@Service
public class AiSettingsService {

    public static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private static final Set<String> EFFORTS = Set.of("low", "medium", "high", "xhigh", "max");

    public enum KeySource {
        SETTINGS, ENVIRONMENT, NONE
    }

    /** Wirksame Konfiguration inklusive Klartext-Schlüssel – nur serverintern verwenden. */
    public record Effective(String baseUrl, String apiKey, KeySource keySource, String model, String effort,
                            String outputLanguage, boolean ocr, boolean compatibilityMode) {

        public boolean enabled() {
            return !apiKey.isBlank();
        }
    }

    public record AiSettingsDto(String baseUrl, String model, String effort, String outputLanguage, boolean ocr,
                                boolean compatibilityMode, boolean apiKeySet, KeySource apiKeySource) {
    }

    /** Die Claude API selbst kann PDFs lesen und strukturiert antworten; andere Server meist nicht. */
    public static boolean isAnthropicApi(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return true;
        }
        try {
            return "api.anthropic.com".equalsIgnoreCase(URI.create(baseUrl.strip()).getHost());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public record UpdateRequest(
            @Size(max = 500) @Pattern(regexp = "^$|^https?://\\S+$", message = "muss mit http:// oder https:// beginnen")
            String baseUrl,
            /* null = unverändert lassen, leer = gespeicherten Schlüssel entfernen */
            @Size(max = 500) String apiKey,
            @NotBlank @Size(max = 100) String model,
            @NotBlank String effort,
            @NotBlank @Size(max = 50) String outputLanguage,
            boolean ocr,
            /* null = automatisch je nach Server */
            Boolean compatibilityMode) {
    }

    private final MongoTemplate mongo;
    private final SecretCipher cipher;
    private final AppProperties.Ai defaults;
    private volatile Effective cached;

    public AiSettingsService(MongoTemplate mongo, SecretCipher cipher, AppProperties properties) {
        this.mongo = mongo;
        this.cipher = cipher;
        this.defaults = properties.ai();
    }

    public Effective current() {
        Effective effective = cached;
        if (effective == null) {
            effective = resolve(mongo.findById(AiSettings.ID, AiSettings.class));
            cached = effective;
        }
        return effective;
    }

    public AiSettingsDto dto() {
        Effective e = current();
        return new AiSettingsDto(e.baseUrl().isBlank() ? DEFAULT_BASE_URL : e.baseUrl(), e.model(), e.effort(),
                e.outputLanguage(), e.ocr(), e.compatibilityMode(), e.enabled(), e.keySource());
    }

    public synchronized AiSettingsDto update(UpdateRequest request) {
        String effort = request.effort().strip().toLowerCase();
        if (!EFFORTS.contains(effort)) {
            throw ApiException.badRequest("Unbekannte Gründlichkeit: " + request.effort());
        }
        AiSettings stored = mongo.findById(AiSettings.ID, AiSettings.class);
        if (stored == null) {
            stored = new AiSettings();
        }
        String baseUrl = request.baseUrl() == null ? "" : request.baseUrl().strip().replaceAll("/+$", "");
        stored.setBaseUrl(baseUrl.equals(DEFAULT_BASE_URL) ? "" : baseUrl);
        stored.setModel(request.model().strip());
        stored.setEffort(effort);
        stored.setOutputLanguage(request.outputLanguage().strip());
        stored.setOcr(request.ocr());
        stored.setCompatibilityMode(request.compatibilityMode());
        if (request.apiKey() != null) {
            String key = request.apiKey().strip();
            stored.setApiKeyEncrypted(key.isEmpty() ? null : cipher.encrypt(key));
        }
        mongo.save(stored);
        cached = null;
        return dto();
    }

    private Effective resolve(AiSettings stored) {
        String envKey = defaults.apiKey() == null ? "" : defaults.apiKey().strip();
        if (stored == null) {
            String baseUrl = defaults.baseUrl().strip();
            return new Effective(baseUrl, envKey,
                    envKey.isBlank() ? KeySource.NONE : KeySource.ENVIRONMENT,
                    defaults.model(), defaults.effort(), defaults.outputLanguage(), defaults.ocr(),
                    defaults.compatibilityMode() != null ? defaults.compatibilityMode() : !isAnthropicApi(baseUrl));
        }
        String key = envKey;
        KeySource source = envKey.isBlank() ? KeySource.NONE : KeySource.ENVIRONMENT;
        if (stored.getApiKeyEncrypted() != null) {
            key = cipher.decrypt(stored.getApiKeyEncrypted());
            source = KeySource.SETTINGS;
        }
        String baseUrl = orDefault(stored.getBaseUrl(), defaults.baseUrl()).strip();
        return new Effective(
                baseUrl,
                key,
                source,
                orDefault(stored.getModel(), defaults.model()),
                orDefault(stored.getEffort(), defaults.effort()),
                orDefault(stored.getOutputLanguage(), defaults.outputLanguage()),
                stored.getOcr() != null ? stored.getOcr() : defaults.ocr(),
                stored.getCompatibilityMode() != null ? stored.getCompatibilityMode() : !isAnthropicApi(baseUrl));
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
