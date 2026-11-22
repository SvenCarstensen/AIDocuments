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
        @Name("import") @DefaultValue Import importer,
        @DefaultValue Ocr ocr,
        @DefaultValue Backup backup) {

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

    /**
     * Eingebaute Texterkennung mit Tesseract. Sie liest Scans ohne Textebene, solange keine externe KI eingerichtet
     * ist – damit sind auch sie im Volltext durchsuchbar. Im Docker-Image ist Tesseract enthalten; anderswo genügt
     * es, Tesseract zu installieren.
     *
     * @param enabled     aus = nie; an = sobald Tesseract gefunden wird
     * @param command     Aufruf von Tesseract (Name im Suchpfad oder vollständiger Pfad)
     * @param languages   Sprachen der Dokumente als Tesseract-Kürzel, mit „+“ verbunden, z. B. deu+eng
     * @param maxPages    höchstens so viele Seiten je Dokument; der Rest bleibt ungelesen (vermerkt am Dokument)
     * @param dpi         Auflösung, mit der eine Seite gerendert wird – Tesseract arbeitet am besten mit 300 dpi
     * @param pageTimeout Zeitgrenze je Seite
     */
    public record Ocr(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("tesseract") String command,
            @DefaultValue("deu+eng") String languages,
            @DefaultValue("50") int maxPages,
            @DefaultValue("300") int dpi,
            @DefaultValue("5m") Duration pageTimeout) {
    }

    /**
     * Vorgabe für die automatische Sicherung: der Ordner, in den sie schreibt; leer = {@code backups} im
     * Datenverzeichnis. In der Oberfläche gespeicherte Einstellungen haben Vorrang.
     */
    public record Backup(@DefaultValue("") String directory) {
    }

    /** Vorgaben für den automatischen Abruf; in der Oberfläche gespeicherte Einstellungen haben Vorrang. */
    public record Import(
            @DefaultValue("5m") Duration interval,
            @DefaultValue("") String directory) {
    }
}
