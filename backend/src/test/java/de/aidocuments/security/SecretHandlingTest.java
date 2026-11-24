package de.aidocuments.security;

import static org.assertj.core.api.Assertions.assertThat;

import de.aidocuments.config.AppProperties;
import de.aidocuments.settings.AiSettingsService;
import de.aidocuments.settings.LlmProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Geheimnisse landen weder im Protokoll noch in einer für andere lesbaren Datei. */
class SecretHandlingTest {

    @TempDir
    Path dir;

    @Test
    void apiKeysAreMaskedInToString() {
        AppProperties.Preset preset = new AppProperties.Preset("https://example.test/v1", "sk-preset-geheim", "m");
        AppProperties.Ai ai = new AppProperties.Ai(LlmProvider.ANTHROPIC, "", "sk-ant-geheim", "modell", "medium",
                "Deutsch", true, null, Map.of(LlmProvider.OPENAI, preset), false);
        AiSettingsService.Effective effective = new AiSettingsService.Effective(LlmProvider.OPENAI,
                "https://example.test/v1", "sk-effektiv-geheim", AiSettingsService.KeySource.SETTINGS, "m", "medium",
                "Deutsch", true, false, false);
        AppProperties.Backup backup = new AppProperties.Backup("", "sicherungs-passwort");
        for (Object value : new Object[] {preset, ai, effective, backup}) {
            assertThat(value.toString()).doesNotContain("geheim").doesNotContain("passwort").contains("***");
        }
        assertThat(new AppProperties.Ai(LlmProvider.ANTHROPIC, "", "", "m", "medium", "Deutsch", true, null, null,
                false).toString()).contains("apiKey=,");
    }

    @Test
    void theKeyFileIsOnlyReadableByItsOwner() throws Exception {
        if (!Files.getFileStore(dir).supportsFileAttributeView("posix")) {
            return; // unter Windows regelt das Benutzerprofil die Rechte
        }
        Path file = dir.resolve("secret.key");
        byte[] created = SecretCipher.loadOrCreateKey(file);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        assertThat(dir.resolve("secret.key.part")).doesNotExist();
        // eine ältere, zu offen angelegte Datei wird beim Start eingeschränkt – der Schlüssel bleibt derselbe
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        assertThat(SecretCipher.loadOrCreateKey(file)).isEqualTo(created);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
    }
}
