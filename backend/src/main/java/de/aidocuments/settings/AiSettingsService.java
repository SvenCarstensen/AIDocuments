package de.aidocuments.settings;

import de.aidocuments.config.AppProperties;
import de.aidocuments.security.SecretCipher;
import de.aidocuments.web.ApiException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/**
 * Liefert die wirksame KI-Konfiguration: in der Oberfläche gespeicherte Werte haben Vorrang,
 * sonst gelten die Vorgaben aus application.yml bzw. den Umgebungsvariablen.
 */
@Service
public class AiSettingsService {

    private static final Set<String> EFFORTS = Set.of("low", "medium", "high", "xhigh", "max");

    public enum KeySource {
        SETTINGS, ENVIRONMENT, NONE
    }

    /** Wirksame Konfiguration inklusive Klartext-Schlüssel – nur serverintern verwenden. */
    public record Effective(LlmProvider provider, String baseUrl, String apiKey, KeySource keySource, String model,
                            String effort, String outputLanguage, boolean ocr, boolean compatibilityMode) {

        /** Genug beisammen, um Dokumente analysieren zu lassen? */
        public boolean enabled() {
            if (baseUrl.isBlank() || model.isBlank()) {
                return false;
            }
            return !provider.requiresApiKey() || !apiKey.isBlank();
        }
    }

    public record AiSettingsDto(LlmProvider provider, String baseUrl, String model, String effort,
                                String outputLanguage, boolean ocr, boolean compatibilityMode, boolean enabled,
                                boolean apiKeySet, KeySource apiKeySource, List<LlmProvider.Info> providers) {
    }

    public record UpdateRequest(
            @NotNull LlmProvider provider,
            @Size(max = 500) @Pattern(regexp = "^$|^https?://\\S+$", message = "muss mit http:// oder https:// beginnen")
            String baseUrl,
            /* null = unverändert lassen, leer = gespeicherten Schlüssel entfernen */
            @Size(max = 500) String apiKey,
            @NotBlank @Size(max = 100) String model,
            @NotBlank String effort,
            @NotBlank @Size(max = 50) String outputLanguage,
            boolean ocr,
            /* nur für Anthropic von Bedeutung; null = automatisch je nach Server */
            Boolean compatibilityMode) {
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
        return new AiSettingsDto(e.provider(), e.baseUrl(), e.model(), e.effort(), e.outputLanguage(), e.ocr(),
                e.compatibilityMode(), e.enabled(), !e.apiKey().isBlank(), e.keySource(),
                Arrays.stream(LlmProvider.values()).map(LlmProvider::info).toList());
    }

    public synchronized AiSettingsDto update(UpdateRequest request) {
        String effort = request.effort().strip().toLowerCase();
        if (!EFFORTS.contains(effort)) {
            throw ApiException.badRequest("Unbekannte Gründlichkeit: " + request.effort());
        }
        LlmProvider provider = request.provider();
        String baseUrl = normalizeBaseUrl(provider, request.baseUrl());
        if (baseUrl.isBlank() && provider.defaultBaseUrl().isBlank()) {
            throw ApiException.badRequest("Bitte die Server-Adresse angeben, z. B. http://192.168.1.10:8000/v1.");
        }

        AiSettings stored = mongo.findById(AiSettings.ID, AiSettings.class);
        if (stored == null) {
            stored = new AiSettings();
        }
        boolean providerChanged = resolve(stored).provider() != provider;
        stored.setProvider(provider);
        // Die Standardadresse wird nicht festgeschrieben – ändert sie sich mit einer neuen Version, gilt die neue.
        stored.setBaseUrl(baseUrl.equals(provider.defaultBaseUrl()) ? "" : baseUrl);
        stored.setModel(request.model().strip());
        stored.setEffort(effort);
        stored.setOutputLanguage(request.outputLanguage().strip());
        stored.setOcr(request.ocr());
        stored.setCompatibilityMode(
                provider.protocol() == LlmProvider.Protocol.ANTHROPIC ? request.compatibilityMode() : null);
        if (request.apiKey() != null) {
            String key = request.apiKey().strip();
            stored.setApiKeyEncrypted(key.isEmpty() ? null : cipher.encrypt(key));
        } else if (providerChanged) {
            // Der gespeicherte Schlüssel gehört zum bisherigen Anbieter und darf nicht an den neuen gehen.
            stored.setApiKeyEncrypted(null);
        }
        mongo.save(stored);
        cached = null;
        return dto();
    }

    /**
     * Für die OpenAI-Schnittstelle gehört "/v1" zur Adresse. Wer nur "http://server:8000" einträgt,
     * meint in aller Regel genau das – dann wird es ergänzt.
     */
    static String normalizeBaseUrl(LlmProvider provider, String raw) {
        String url = raw == null ? "" : raw.strip().replaceAll("/+$", "");
        if (url.isEmpty() || provider.protocol() != LlmProvider.Protocol.OPENAI) {
            return url;
        }
        try {
            String path = URI.create(url).getPath();
            return path == null || path.isEmpty() ? url + "/v1" : url;
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Die Server-Adresse ist ungültig.");
        }
    }

    private Effective resolve(AiSettings stored) {
        LlmProvider provider = stored != null && stored.getProvider() != null ? stored.getProvider() : defaults.provider();
        // Vorgaben aus der Konfiguration gelten nur für den dort eingestellten Anbieter.
        boolean configApplies = provider == defaults.provider();

        String baseUrl = stored != null && stored.getBaseUrl() != null && !stored.getBaseUrl().isBlank()
                ? stored.getBaseUrl().strip()
                : configApplies && !defaults.baseUrl().isBlank()
                        ? normalizeBaseUrl(provider, defaults.baseUrl())
                        : provider.defaultBaseUrl();

        String key = "";
        KeySource source = KeySource.NONE;
        if (stored != null && stored.getApiKeyEncrypted() != null) {
            key = cipher.decrypt(stored.getApiKeyEncrypted());
            source = KeySource.SETTINGS;
        } else if (configApplies && defaults.apiKey() != null && !defaults.apiKey().isBlank()) {
            key = defaults.apiKey().strip();
            source = KeySource.ENVIRONMENT;
        }

        String model = stored != null && stored.getModel() != null ? stored.getModel()
                : configApplies ? defaults.model() : "";
        Boolean compatibility = stored != null && stored.getCompatibilityMode() != null
                ? stored.getCompatibilityMode()
                : stored == null ? defaults.compatibilityMode() : null;

        return new Effective(
                provider,
                baseUrl,
                key,
                source,
                model,
                stored != null && stored.getEffort() != null ? stored.getEffort() : defaults.effort(),
                stored != null && stored.getOutputLanguage() != null ? stored.getOutputLanguage()
                        : defaults.outputLanguage(),
                stored != null && stored.getOcr() != null ? stored.getOcr() : defaults.ocr(),
                provider.protocol() == LlmProvider.Protocol.ANTHROPIC
                        && (compatibility != null ? compatibility : !isAnthropicApi(baseUrl)));
    }
}
