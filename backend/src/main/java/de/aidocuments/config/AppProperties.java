package de.aidocuments.config;

import de.aidocuments.settings.LlmProvider;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.Name;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @DefaultValue("./data") Path dataDir,
        @DefaultValue Ai ai,
        @Name("import") @DefaultValue Import importer) {

    public Path dataRoot() {
        return dataDir.toAbsolutePath().normalize();
    }

    /** Vorgaben für die KI; in der Oberfläche gespeicherte Einstellungen haben Vorrang. */
    public record Ai(
            @DefaultValue("anthropic") LlmProvider provider,
            @DefaultValue("") String baseUrl,
            @DefaultValue("") String apiKey,
            @DefaultValue("claude-opus-5-5") String model,
            @DefaultValue("medium") String effort,
            @DefaultValue("Deutsch") String outputLanguage,
            @DefaultValue("true") boolean ocr,
            /* null = automatisch: an bei jedem Server außer api.anthropic.com */
            Boolean compatibilityMode,
            /*
             * Vorbelegungen für einzelne Anbieter (app.ai.presets.<anbieter>.*). Wählt jemand in der
             * Oberfläche diesen Anbieter, stehen Adresse und Modell schon da, und der Schlüssel gilt als
             * hinterlegt – gedacht für die Entwicklungsumgebung (config/debug.yml).
             */
            Map<LlmProvider, Preset> presets,
            /*
             * Nur für die Entwicklungsumgebung: liefert vorbelegte Schlüssel an die Einstellungsseite aus,
             * damit sie dort sichtbar im Feld stehen. Im Auslieferungszustand aus – dann verlässt ein
             * konfigurierter Schlüssel den Server nie.
             */
            @DefaultValue("false") boolean revealPresetKeys) {

        /** Vorbelegung für den Anbieter: ausdrücklich konfiguriert, sonst die allgemeinen Vorgaben seines Anbieters. */
        public Preset presetFor(LlmProvider candidate) {
            Preset explicit = presets == null ? null : presets.get(candidate);
            if (explicit != null) {
                return explicit;
            }
            return candidate == provider ? new Preset(baseUrl, apiKey, model) : Preset.NONE;
        }
    }

    public record Preset(
            @DefaultValue("") String baseUrl,
            @DefaultValue("") String apiKey,
            @DefaultValue("") String model) {

        static final Preset NONE = new Preset("", "", "");
    }

    /** Vorgaben für den automatischen Abruf; in der Oberfläche gespeicherte Einstellungen haben Vorrang. */
    public record Import(
            @DefaultValue("5m") Duration interval,
            @DefaultValue("") String directory) {
    }
}
