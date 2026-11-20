package de.aidocuments.config;

import java.nio.file.Path;
import java.time.Duration;
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
            @DefaultValue("") String baseUrl,
            @DefaultValue("") String apiKey,
            @DefaultValue("claude-opus-5-5") String model,
            @DefaultValue("medium") String effort,
            @DefaultValue("Deutsch") String outputLanguage,
            @DefaultValue("true") boolean ocr,
            /* null = automatisch: an bei jedem Server außer api.anthropic.com */
            Boolean compatibilityMode,
            @DefaultValue("2") int concurrency) {
    }

    /** Vorgaben für den automatischen Abruf; in der Oberfläche gespeicherte Einstellungen haben Vorrang. */
    public record Import(
            @DefaultValue("5m") Duration interval,
            @DefaultValue("") String directory) {
    }
}
