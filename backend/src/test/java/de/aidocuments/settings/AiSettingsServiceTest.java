package de.aidocuments.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.aidocuments.config.AppProperties;
import de.aidocuments.i18n.TestMessages;
import de.aidocuments.security.SecretCipher;
import de.aidocuments.settings.AiSettingsService.AiSettingsDto;
import de.aidocuments.settings.AiSettingsService.ProviderOption;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import de.aidocuments.persistence.SettingsStore;

/** Vorbelegungen aus der Konfiguration: für wen sie gelten und wann ein Schlüssel den Server verlassen darf. */
class AiSettingsServiceTest {

    private static final String KEY = "schluessel-des-lokalen-servers";

    @Test
    void configuredServerIsThePresetOfItsProviderOnly() {
        AiSettingsDto dto = service(false).dto();

        assertThat(dto.provider()).isEqualTo(LlmProvider.OPENAI_COMPATIBLE);
        assertThat(dto.baseUrl()).isEqualTo("http://server.lan:8000/v1");
        assertThat(dto.model()).isEqualTo("lokales-modell");
        assertThat(dto.apiKeySet()).isTrue();

        ProviderOption local = option(dto, LlmProvider.OPENAI_COMPATIBLE);
        assertThat(local.presetBaseUrl()).isEqualTo("http://server.lan:8000/v1");
        assertThat(local.presetModel()).isEqualTo("lokales-modell");
        assertThat(local.presetApiKeySet()).isTrue();
        // Eigennamen bleiben, wie sie sind; die Bezeichnung dieses Anbieters ist übersetzbar (ohne Anfrage: Englisch)
        assertThat(local.label()).isEqualTo("OpenAI-compatible");
        assertThat(option(dto, LlmProvider.LM_STUDIO).label()).isEqualTo("LM Studio");

        // Für Anthropic (und alle übrigen) gibt es keine Vorbelegung – auch nicht Adresse oder Modell des lokalen Servers
        for (LlmProvider other : new LlmProvider[] {LlmProvider.ANTHROPIC, LlmProvider.OPENAI, LlmProvider.MOONSHOT,
                LlmProvider.LM_STUDIO}) {
            ProviderOption option = option(dto, other);
            assertThat(option.presetBaseUrl()).as(other.name()).isEmpty();
            assertThat(option.presetModel()).as(other.name()).isEmpty();
            assertThat(option.presetApiKeySet()).as(other.name()).isFalse();
            assertThat(option.presetApiKey()).as(other.name()).isEmpty();
        }
    }

    @Test
    void presetKeyLeavesTheServerOnlyWhenExplicitlyAllowed() {
        assertThat(option(service(false).dto(), LlmProvider.OPENAI_COMPATIBLE).presetApiKey()).isEmpty();
        assertThat(option(service(true).dto(), LlmProvider.OPENAI_COMPATIBLE).presetApiKey()).isEqualTo(KEY);
    }

    private static ProviderOption option(AiSettingsDto dto, LlmProvider provider) {
        return dto.providers().stream().filter(option -> option.id() == provider).findFirst().orElseThrow();
    }

    /** Dienst mit einem lokalen Server als Vorgabe und ohne in der Oberfläche gespeicherte Einstellungen. */
    private static AiSettingsService service(boolean revealPresetKeys) {
        AppProperties.Ai ai = new AppProperties.Ai(LlmProvider.OPENAI_COMPATIBLE, "http://server.lan:8000", KEY,
                "lokales-modell", "medium", "Deutsch", true, null, null, revealPresetKeys);
        AppProperties properties = new AppProperties(Path.of("./data"), ai,
                new AppProperties.Import(Duration.ofMinutes(5), ""),
                new AppProperties.Ocr(false, "tesseract", "deu+eng", 50, 300, Duration.ofMinutes(5)),
                new AppProperties.Backup(""));
        return new AiSettingsService(mock(SettingsStore.class), mock(SecretCipher.class), properties,
                TestMessages.create());
    }

    @Test
    void presetKeyIsOnlyUsedForItsOwnServer() {
        assertThat(AiSettingsService.sameServer("http://server.lan:8000/v1", "http://SERVER.lan:9000/v1")).isTrue();
        assertThat(AiSettingsService.sameServer("http://server.lan:8000/v1", "http://angreifer.example/v1")).isFalse();
        assertThat(AiSettingsService.sameServer("https://api.anthropic.com", "http://api.anthropic.com")).isFalse();
        assertThat(AiSettingsService.sameServer("", "http://server.lan")).isFalse();
    }
}
