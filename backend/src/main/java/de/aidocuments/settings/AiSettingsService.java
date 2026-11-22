package de.aidocuments.settings;

import static de.aidocuments.i18n.ValidationMessages.NOT_BLANK;
import static de.aidocuments.i18n.ValidationMessages.NOT_NULL;
import static de.aidocuments.i18n.ValidationMessages.SIZE;

import de.aidocuments.config.AppProperties;
import de.aidocuments.i18n.Messages;
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
import de.aidocuments.persistence.SettingsStore;
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
                            String effort, String outputLanguage, boolean ocr, boolean compatibilityMode,
                            /* Scans erst mit der eingebauten Texterkennung lesen, dann von der KI verfeinern lassen */
                            boolean quickOcrFirst) {

        /** Genug beisammen, um Dokumente analysieren zu lassen? */
        public boolean enabled() {
            if (baseUrl.isBlank() || model.isBlank()) {
                return false;
            }
            return !provider.requiresApiKey() || !apiKey.isBlank();
        }
    }

    public record AiSettingsDto(LlmProvider provider, String baseUrl, String model, String effort,
                                String outputLanguage, boolean ocr, boolean quickOcrFirst, boolean compatibilityMode,
                                boolean enabled,
                                boolean apiKeySet, KeySource apiKeySource, List<ProviderOption> providers) {
    }

    /**
     * Ein auswählbarer Anbieter samt dem, was die Oberfläche beim Auswählen einträgt. Der vorbelegte
     * Schlüssel selbst bleibt im Normalfall auf dem Server – übermittelt wird nur, dass es einen gibt.
     */
    public record ProviderOption(LlmProvider id, String label, LlmProvider.Protocol protocol, String defaultBaseUrl,
                                 boolean requiresApiKey, boolean cloud, String presetBaseUrl, String presetModel,
                                 boolean presetApiKeySet,
                                 /* nur in der Entwicklungsumgebung gefüllt (app.ai.reveal-preset-keys), sonst leer */
                                 String presetApiKey) {
    }

    public record UpdateRequest(
            @NotNull(message = NOT_NULL) LlmProvider provider,
            @Size(max = 500, message = SIZE)
            @Pattern(regexp = "^$|^https?://\\S+$", message = "{validation.baseUrl}") String baseUrl,
            /* null = unverändert lassen, leer = gespeicherten Schlüssel entfernen */
            @Size(max = 500, message = SIZE) String apiKey,
            @NotBlank(message = NOT_BLANK) @Size(max = 100, message = SIZE) String model,
            @NotBlank(message = NOT_BLANK) String effort,
            @NotBlank(message = NOT_BLANK) @Size(max = 50, message = SIZE) String outputLanguage,
            boolean ocr,
            /* null = unverändert lassen */
            Boolean quickOcrFirst,
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

    private final SettingsStore store;
    private final SecretCipher cipher;
    private final AppProperties.Ai defaults;
    private final Messages messages;
    private volatile Effective cached;

    public AiSettingsService(SettingsStore store, SecretCipher cipher, AppProperties properties, Messages messages) {
        this.messages = messages;
        this.store = store;
        this.cipher = cipher;
        this.defaults = properties.ai();
    }

    public Effective current() {
        Effective effective = cached;
        if (effective == null) {
            effective = resolve(store.find(AiSettings.ID, AiSettings.class));
            cached = effective;
        }
        return effective;
    }

    public AiSettingsDto dto() {
        Effective e = current();
        return new AiSettingsDto(e.provider(), e.baseUrl(), e.model(), e.effort(), e.outputLanguage(), e.ocr(),
                e.quickOcrFirst(), e.compatibilityMode(), e.enabled(), !e.apiKey().isBlank(), e.keySource(),
                Arrays.stream(LlmProvider.values()).map(this::option).toList());
    }

    private ProviderOption option(LlmProvider provider) {
        AppProperties.Preset preset = defaults.presetFor(provider);
        String presetUrl = preset.baseUrl().isBlank() ? "" : normalizeBaseUrl(provider, preset.baseUrl());
        String label = provider.labelKey() != null ? messages.get(provider.labelKey()) : provider.label();
        return new ProviderOption(provider, label, provider.protocol(), provider.defaultBaseUrl(),
                provider.requiresApiKey(), provider.cloud(), presetUrl, preset.model().strip(),
                !preset.apiKey().isBlank(),
                defaults.revealPresetKeys() ? preset.apiKey().strip() : "");
    }

    public synchronized AiSettingsDto update(UpdateRequest request) {
        String effort = request.effort().strip().toLowerCase();
        if (!EFFORTS.contains(effort)) {
            throw ApiException.badRequest("error.ai.unknownEffort", request.effort());
        }
        LlmProvider provider = request.provider();
        String baseUrl = normalizeBaseUrl(provider, request.baseUrl());
        if (baseUrl.isBlank() && provider.defaultBaseUrl().isBlank()) {
            throw ApiException.badRequest("error.ai.baseUrlRequired");
        }

        AiSettings stored = store.find(AiSettings.ID, AiSettings.class);
        if (stored == null) {
            stored = new AiSettings();
        }
        Effective before = resolve(stored);
        boolean providerChanged = before.provider() != provider;
        stored.setProvider(provider);
        // Die Adresse wird so gespeichert, wie sie im Formular stand – auch die Standardadresse. Sonst träte
        // an ihre Stelle stillschweigend wieder eine Vorbelegung aus der Konfiguration.
        stored.setBaseUrl(baseUrl.isBlank() ? provider.defaultBaseUrl() : baseUrl);
        stored.setModel(request.model().strip());
        stored.setEffort(effort);
        stored.setOutputLanguage(request.outputLanguage().strip());
        stored.setOcr(request.ocr());
        if (request.quickOcrFirst() != null) {
            stored.setQuickOcrFirst(request.quickOcrFirst());
        }
        stored.setCompatibilityMode(
                provider.protocol() == LlmProvider.Protocol.ANTHROPIC ? request.compatibilityMode() : null);
        if (request.apiKey() != null) {
            String key = request.apiKey().strip();
            stored.setApiKeyEncrypted(key.isEmpty() ? null : cipher.encrypt(key));
        } else if (providerChanged || !sameServer(before.baseUrl(), stored.getBaseUrl())) {
            // Der gespeicherte Schlüssel gehört zum bisherigen Anbieter bzw. Server und darf nicht an einen anderen
            // gehen – sonst ließe er sich mit einer geänderten Adresse abgreifen. Für den neuen Server neu eingeben.
            stored.setApiKeyEncrypted(null);
        }
        store.save(AiSettings.ID, stored);
        cached = null;
        return dto();
    }

    private static String presetBaseUrl(LlmProvider provider, AppProperties.Preset preset) {
        return preset.baseUrl().isBlank() ? provider.defaultBaseUrl() : normalizeBaseUrl(provider, preset.baseUrl());
    }

    /**
     * Derselbe Server: gleicher Rechnername, und nicht von https auf http herabgestuft. Port und Pfad dürfen sich
     * ändern (etwa /v1 ergänzt).
     */
    static boolean sameServer(String previous, String next) {
        URI a = parse(previous);
        URI b = parse(next);
        if (a == null || b == null || a.getHost() == null || b.getHost() == null) {
            return false;
        }
        boolean downgrade = "https".equalsIgnoreCase(a.getScheme()) && !"https".equalsIgnoreCase(b.getScheme());
        return a.getHost().equalsIgnoreCase(b.getHost()) && !downgrade;
    }

    private static URI parse(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            return URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
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
            throw ApiException.badRequest("error.ai.baseUrlInvalid");
        }
    }

    private Effective resolve(AiSettings stored) {
        boolean saved = stored != null && stored.getProvider() != null;
        LlmProvider provider = saved ? stored.getProvider() : defaults.provider();
        AppProperties.Preset preset = defaults.presetFor(provider);

        // Gespeichertes gilt wörtlich. Nur solange nichts gespeichert ist, wirkt die Vorbelegung.
        String baseUrl;
        if (saved) {
            baseUrl = stored.getBaseUrl() == null || stored.getBaseUrl().isBlank()
                    ? provider.defaultBaseUrl() : stored.getBaseUrl().strip();
        } else {
            baseUrl = preset.baseUrl().isBlank() ? provider.defaultBaseUrl() : normalizeBaseUrl(provider, preset.baseUrl());
        }

        // Ein in der Oberfläche gespeicherter Schlüssel hat Vorrang; sonst der für diesen Anbieter vorbelegte.
        String key = "";
        KeySource source = KeySource.NONE;
        if (stored != null && stored.getApiKeyEncrypted() != null) {
            key = cipher.decrypt(stored.getApiKeyEncrypted());
            source = KeySource.SETTINGS;
        } else if (!preset.apiKey().isBlank() && sameServer(presetBaseUrl(provider, preset), baseUrl)) {
            // der vorbelegte Schlüssel nur für den Server, für den er vorbelegt ist
            key = preset.apiKey().strip();
            source = KeySource.ENVIRONMENT;
        }

        String model = saved && stored.getModel() != null ? stored.getModel() : preset.model().strip();
        Boolean compatibility = saved ? stored.getCompatibilityMode() : defaults.compatibilityMode();

        return new Effective(
                provider,
                baseUrl,
                key,
                source,
                model,
                saved && stored.getEffort() != null ? stored.getEffort() : defaults.effort(),
                saved && stored.getOutputLanguage() != null ? stored.getOutputLanguage() : defaults.outputLanguage(),
                saved && stored.getOcr() != null ? stored.getOcr() : defaults.ocr(),
                provider.protocol() == LlmProvider.Protocol.ANTHROPIC
                        && (compatibility != null ? compatibility : !isAnthropicApi(baseUrl)),
                saved && Boolean.TRUE.equals(stored.getQuickOcrFirst()));
    }
}
