import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Freischaltcodes für AIDocuments erzeugen und prüfen. Läuft ohne Bauen, nur mit Java 21:
 *
 * <pre>
 *   java tools/lizenz/Lizenz.java schluessel                      # einmalig: Schlüsselpaar anlegen
 *   java tools/lizenz/Lizenz.java ausstellen --firma "Muster GmbH" [--bis 2027-12-31]
 *   java tools/lizenz/Lizenz.java pruefen AIDOCS-XXXXX-…
 * </pre>
 *
 * Der geheime Schlüssel liegt in {@code ~/.aidocuments-lizenz/privat.pem} (nur für dich lesbar, nie im Repository);
 * wer ihn hat, kann Codes ausstellen. Sichere ihn zusätzlich, etwa im Passwortmanager. Jeder ausgestellte Code wird
 * in {@code ausgestellt.csv} daneben vermerkt.
 *
 * <p>Das Format muss mit {@code backend/src/main/java/de/aidocuments/license/LicenseCode.java} übereinstimmen (ein
 * Test im Backend prüft das).
 */
public class Lizenz {

    private static final byte VERSION = 1;
    private static final LocalDate EPOCH = LocalDate.of(2024, 1, 1);
    private static final byte[] DOMAIN = "AIDOCS-LIZENZ-1\0".getBytes(StandardCharsets.US_ASCII);
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int MAX_LICENSEE_BYTES = 120;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        Path folder = Path.of(option(args, "--ordner", System.getProperty("user.home") + "/.aidocuments-lizenz"));
        switch (args[0]) {
            case "schluessel" -> createKeys(folder);
            case "ausstellen" -> issue(folder, args);
            case "pruefen" -> check(folder, args);
            default -> usage();
        }
    }

    private static void usage() {
        System.out.println("""
                Aufruf:
                  java tools/lizenz/Lizenz.java schluessel [--ordner DIR]
                  java tools/lizenz/Lizenz.java ausstellen --firma "Name" [--bis JJJJ-MM-TT] [--ordner DIR]
                  java tools/lizenz/Lizenz.java pruefen CODE [--ordner DIR]""");
    }

    // ---- Schlüssel ----

    private static void createKeys(Path folder) throws IOException, GeneralSecurityException {
        Path secret = folder.resolve("privat.pem");
        Path open = folder.resolve("oeffentlich.txt");
        if (Files.exists(secret) || Files.exists(open)) {
            fail("In " + folder + " liegt schon ein Schlüssel – er wird nicht überschrieben. (Ein neuer Schlüssel "
                    + "macht alle bisher ausgestellten Codes ungültig.)");
        }
        Files.createDirectories(folder);
        restrict(folder, "rwx------");
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        Files.writeString(secret, pem, StandardOpenOption.CREATE_NEW);
        restrict(secret, "rw-------");
        String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        Files.writeString(open, publicKey + "\n", StandardOpenOption.CREATE_NEW);
        System.out.println("Schlüsselpaar angelegt in " + folder);
        System.out.println("  geheim:     " + secret + "  (sichern, nie weitergeben, nie ins Repository)");
        System.out.println("  öffentlich: " + open);
        System.out.println();
        System.out.println("Diese Zeile gehört nach backend/src/main/resources/license/public-key.txt:");
        System.out.println(publicKey);
    }

    private static void restrict(Path path, String permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        } catch (UnsupportedOperationException e) {
            // Windows: dort schützt das Benutzerprofil die Datei
        }
    }

    private static PrivateKey privateKey(Path folder) throws IOException, GeneralSecurityException {
        Path secret = folder.resolve("privat.pem");
        if (!Files.exists(secret)) {
            fail("Kein geheimer Schlüssel in " + folder + " – erst: java tools/lizenz/Lizenz.java schluessel");
        }
        String base64 = Files.readString(secret).replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
    }

    private static PublicKey publicKey(Path folder) throws IOException, GeneralSecurityException {
        Path open = folder.resolve("oeffentlich.txt");
        if (!Files.exists(open)) {
            fail("Kein öffentlicher Schlüssel in " + folder);
        }
        return KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(Files.readString(open).strip())));
    }

    // ---- Ausstellen ----

    private static void issue(Path folder, String[] args) throws IOException, GeneralSecurityException {
        String licensee = option(args, "--firma", null);
        if (licensee == null || licensee.isBlank()) {
            fail("--firma fehlt, z. B. --firma \"Muster GmbH\"");
        }
        licensee = licensee.strip();
        byte[] name = licensee.getBytes(StandardCharsets.UTF_8);
        if (name.length > MAX_LICENSEE_BYTES) {
            fail("Der Firmenname ist zu lang (höchstens " + MAX_LICENSEE_BYTES + " Bytes).");
        }
        String until = option(args, "--bis", null);
        LocalDate validUntil = until == null ? null : LocalDate.parse(until);
        LocalDate today = LocalDate.now();
        if (validUntil != null && validUntil.isBefore(today)) {
            fail("--bis liegt in der Vergangenheit.");
        }
        byte[] id = new byte[4];
        new SecureRandom().nextBytes(id);

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(VERSION);
        payload.writeBytes(id);
        day(payload, today);
        if (validUntil == null) {
            payload.write(0);
            payload.write(0);
        } else {
            day(payload, validUntil);
        }
        payload.write(name.length);
        payload.writeBytes(name);

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey(folder));
        signer.update(DOMAIN);
        signer.update(payload.toByteArray());
        payload.writeBytes(signer.sign());
        String code = format(payload.toByteArray());

        String number = HexFormat.of().formatHex(id);
        Path register = folder.resolve("ausgestellt.csv");
        if (!Files.exists(register)) {
            Files.writeString(register, "ausgestellt;nummer;firma;gueltig_bis\n", StandardOpenOption.CREATE_NEW);
            restrict(register, "rw-------");
        }
        Files.writeString(register, today + ";" + number + ";" + licensee.replace(";", ",") + ";"
                + (validUntil == null ? "unbefristet" : validUntil) + "\n", StandardOpenOption.APPEND);
        System.out.println("Freischaltcode Nr. " + number + " für „" + licensee + "“, gültig "
                + (validUntil == null ? "unbefristet" : "bis " + validUntil) + ":");
        System.out.println();
        System.out.println(code);
    }

    private static void day(ByteArrayOutputStream out, LocalDate day) {
        long days = ChronoUnit.DAYS.between(EPOCH, day);
        if (days < 1 || days > 0xFFFF) {
            fail("Datum außerhalb 2024–2203: " + day);
        }
        out.write((int) (days >> 8));
        out.write((int) days);
    }

    private static String format(byte[] bytes) {
        StringBuilder encoded = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                encoded.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            encoded.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        }
        StringBuilder text = new StringBuilder("AIDOCS");
        for (int i = 0; i < encoded.length(); i += 5) {
            text.append('-').append(encoded, i, Math.min(encoded.length(), i + 5));
        }
        return text.toString();
    }

    // ---- Prüfen ----

    private static void check(Path folder, String[] args) throws IOException, GeneralSecurityException {
        if (args.length < 2 || args[1].startsWith("--")) {
            fail("Code fehlt.");
        }
        String compact = args[1].replaceAll("[\\s\\-]", "").toUpperCase(Locale.ROOT);
        if (compact.startsWith("AIDOCS")) {
            compact = compact.substring(6);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : compact.toCharArray()) {
            int value = ALPHABET.indexOf(c == 'O' ? '0' : c == 'I' || c == 'L' ? '1' : c);
            if (value < 0) {
                fail("Ungültiges Zeichen im Code: " + c);
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        byte[] bytes = out.toByteArray();
        if (bytes.length < 75) {
            fail("Code zu kurz.");
        }
        int signed = bytes.length - 64;
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey(folder));
        verifier.update(DOMAIN);
        verifier.update(bytes, 0, signed);
        if (!verifier.verify(bytes, signed, 64)) {
            fail("UNGÜLTIG: Die Signatur passt nicht (verändert, unvollständig oder mit anderem Schlüssel).");
        }
        ByteBuffer in = ByteBuffer.wrap(bytes, 0, signed);
        in.get();
        byte[] id = new byte[4];
        in.get(id);
        LocalDate issued = EPOCH.plusDays(Short.toUnsignedInt(in.getShort()));
        int until = Short.toUnsignedInt(in.getShort());
        byte[] name = new byte[Byte.toUnsignedInt(in.get())];
        in.get(name);
        System.out.println("Gültig signiert: Nr. " + HexFormat.of().formatHex(id) + " für „"
                + new String(name, StandardCharsets.UTF_8) + "“, ausgestellt " + issued + ", gültig "
                + (until == 0 ? "unbefristet" : "bis " + EPOCH.plusDays(until)));
    }

    // ---- Hilfen ----

    private static String option(String[] args, String name, String fallback) {
        for (int i = 1; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
    }

    private static void fail(String message) {
        System.err.println(message);
        System.exit(1);
    }
}
