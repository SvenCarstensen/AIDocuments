package de.aidocuments.license;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Freischaltcodes: nur mit dem geheimen Schlüssel zu erzeugen, nicht zu verändern, robust gegen Abtippen. */
public class LicenseCodeTest {

    @TempDir
    Path dir;

    private static final LicenseCode.License MUSTER = new LicenseCode.License("0a1b2c3d", "Müller & Söhne GmbH",
            LocalDate.of(2026, 11, 24), LocalDate.of(2027, 12, 31));

    @Test
    void aCodeCarriesItsDetailsAndIsVerifiedWithThePublicKey() throws Exception {
        KeyPair keys = keys();
        String code = LicenseCode.issue(MUSTER, keys.getPrivate());

        assertThat(code).startsWith("AIDOCS-").matches("AIDOCS(-[0-9A-HJKMNP-TV-Z]{1,5})+");
        assertThat(LicenseCode.verify(code, keys.getPublic())).isEqualTo(MUSTER);
        // klein geschrieben, ohne Bindestriche, mit Zeilenumbruch aus einer E-Mail, O statt 0: gilt trotzdem
        String sloppy = code.toLowerCase().replace("-", "").replaceFirst("(.{40})", "$1\n  ").replace('0', 'O');
        assertThat(LicenseCode.verify(sloppy, keys.getPublic())).isEqualTo(MUSTER);
        assertThat(LicenseCode.verify(code.substring("AIDOCS-".length()), keys.getPublic())).isEqualTo(MUSTER);

        LicenseCode.License unlimited = new LicenseCode.License("ffffffff", "Verein? Nein: Firma", MUSTER.issued(), null);
        assertThat(LicenseCode.verify(LicenseCode.issue(unlimited, keys.getPrivate()), keys.getPublic()).validUntil())
                .isNull();
        assertThat(MUSTER.expiredOn(LocalDate.of(2027, 12, 31))).isFalse();
        assertThat(MUSTER.expiredOn(LocalDate.of(2028, 1, 1))).isTrue();
    }

    @Test
    void changedOrForeignCodesAreRefused() throws Exception {
        KeyPair keys = keys();
        String code = LicenseCode.issue(MUSTER, keys.getPrivate());

        // jedes einzelne Zeichen verändert: ungültig (die Signatur deckt alles ab – Firma, Datum, Nummer)
        String body = code.substring("AIDOCS-".length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '-') {
                continue;
            }
            char other = c == 'Z' ? 'Y' : 'Z';
            String changed = "AIDOCS-" + body.substring(0, i) + other + body.substring(i + 1);
            assertThatThrownBy(() -> LicenseCode.verify(changed, keys.getPublic()))
                    .as("Zeichen %d verändert", i).isInstanceOf(LicenseCode.InvalidCodeException.class);
        }
        // mit einem anderen Schlüssel signiert, abgeschnitten, Unsinn, zu lang
        String foreign = LicenseCode.issue(MUSTER, keys().getPrivate());
        for (String bad : List.of(foreign, code.substring(0, code.length() - 6), "AIDOCS-HALLO-WELT", "",
                "AIDOCS-" + "A".repeat(2000), "AIDOCS-UUUUU")) {
            assertThatThrownBy(() -> LicenseCode.verify(bad, keys.getPublic()))
                    .isInstanceOf(LicenseCode.InvalidCodeException.class);
        }
    }

    @Test
    void theToolIssuesCodesTheApplicationAcceptsAndChecksTheApplicationsCodes() throws Exception {
        Path folder = dir.resolve("schluessel");
        List<String> keygen = tool("schluessel", "--ordner", folder.toString());
        assertThat(String.join("\n", keygen)).contains("public-key.txt");
        assertThat(Files.getPosixFilePermissions(folder.resolve("privat.pem")))
                .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        // ein zweites Mal überschreibt nichts
        assertThat(run("schluessel", "--ordner", folder.toString()).exitCode).isEqualTo(1);

        var publicKey = LicenseCode.publicKey(Files.readString(folder.resolve("oeffentlich.txt")));
        List<String> issued = tool("ausstellen", "--firma", "Müller & Söhne GmbH", "--bis", "2030-06-30",
                "--ordner", folder.toString());
        LicenseCode.License license = LicenseCode.verify(issued.getLast(), publicKey);
        assertThat(license.licensee()).isEqualTo("Müller & Söhne GmbH");
        assertThat(license.validUntil()).isEqualTo(LocalDate.of(2030, 6, 30));
        assertThat(license.issued()).isEqualTo(LocalDate.now());
        assertThat(Files.readString(folder.resolve("ausgestellt.csv"))).contains(license.id() + ";Müller & Söhne GmbH;2030-06-30");

        // umgekehrt: was die Anwendung (für Tests) ausstellt, erkennt das Werkzeug
        PrivateKey secret = KeyFactory.getInstance("Ed25519").generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(
                Base64.getDecoder().decode(Files.readString(folder.resolve("privat.pem"))
                        .replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", ""))));
        String fromApp = LicenseCode.issue(MUSTER, secret);
        assertThat(String.join("\n", tool("pruefen", fromApp, "--ordner", folder.toString())))
                .contains("Gültig signiert", "0a1b2c3d", "Müller & Söhne GmbH", "2027-12-31");
        assertThat(run("pruefen", fromApp.replace('A', 'B'), "--ordner", folder.toString()).exitCode).isEqualTo(1);
    }

    /** Der Testschlüssel aus src/test/resources passt zu dem öffentlichen, den die Anwendung im Test lädt. */
    public static String issueWithTestKey(LicenseCode.License license) throws Exception {
        try (InputStream in = LicenseCodeTest.class.getResourceAsStream("/license/test-private-key.pem")) {
            String pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII).lines()
                    .filter(line -> !line.startsWith("#") && !line.startsWith("-----")).reduce("", String::concat);
            PrivateKey key = KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
            return LicenseCode.issue(license, key);
        }
    }

    /** Für Tests außerhalb dieses Pakets. */
    public static String issue(LicenseCode.License license, PrivateKey key) throws Exception {
        return LicenseCode.issue(license, key);
    }

    private static KeyPair keys() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private record Result(int exitCode, List<String> lines) {
    }

    private static List<String> tool(String... args) throws Exception {
        Result result = run(args);
        assertThat(result.exitCode).as(String.join("\n", result.lines)).isZero();
        return result.lines.stream().filter(line -> !line.isBlank()).toList();
    }

    private static Result run(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "../tools/lizenz/Lizenz.java"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Result(process.exitValue(), output.lines().toList());
    }
}
