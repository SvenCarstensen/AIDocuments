package de.aidocuments.license;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Freischaltcode für Unternehmen: die Angaben der Lizenz und eine digitale Signatur (Ed25519) darüber.
 *
 * <p>Warum signiert und nicht verschlüsselt: Den Schlüssel zum Entschlüsseln müsste die Anwendung mitbringen – wer ihn
 * herausliest, könnte beliebige Codes bauen. Bei einer Signatur steckt in der Anwendung nur der öffentliche Schlüssel;
 * damit lässt sich ein Code prüfen, aber keiner erzeugen. Den geheimen Schlüssel hat allein, wer die Codes ausstellt
 * ({@code tools/lizenz/Lizenz.java}). Ändert man auch nur ein Zeichen (etwa die Firma oder das Ablaufdatum), passt
 * die Signatur nicht mehr.
 *
 * <p>Aufbau (Version 1), Byte für Byte: Version (1) · Lizenznummer (4, zufällig) · ausgestellt am (2, Tage seit
 * 1.1.2024) · gültig bis (2, ebenso; 0 = unbefristet) · Länge der Firma (1) · Firma (UTF-8, höchstens 120) · Signatur
 * (64) über {@code "AIDOCS-LIZENZ-1\0"} und die Bytes davor. Als Text: {@code AIDOCS-} und die Bytes in Crockford-Base32
 * in Fünfergruppen – unempfindlich gegen Groß-/Kleinschreibung, Leerzeichen, Zeilenumbrüche aus E-Mails und die
 * Verwechslung von O/0 und I/L/1.
 *
 * <p>Muss mit {@code tools/lizenz/Lizenz.java} übereinstimmen (siehe LicenseCodeTest).
 */
public final class LicenseCode {

    static final byte VERSION = 1;
    static final LocalDate EPOCH = LocalDate.of(2024, 1, 1);
    static final String PREFIX = "AIDOCS";
    static final int MAX_LICENSEE_BYTES = 120;
    /** Länger ist kein Code – geprüft, bevor überhaupt etwas dekodiert wird. */
    public static final int MAX_TEXT = 1000;
    private static final byte[] DOMAIN = "AIDOCS-LIZENZ-1\0".getBytes(StandardCharsets.US_ASCII);
    private static final int SIGNATURE_BYTES = 64;
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    /**
     * @param id         Lizenznummer (8 Hexziffern) – zum Nachschlagen beim Aussteller
     * @param validUntil letzter gültiger Tag; {@code null} = unbefristet
     */
    public record License(String id, String licensee, LocalDate issued, LocalDate validUntil) {

        public boolean expiredOn(LocalDate day) {
            return validUntil != null && day.isAfter(validUntil);
        }
    }

    /** Der Code ist kein gültiger Freischaltcode (unvollständig, verändert oder mit fremdem Schlüssel signiert). */
    public static final class InvalidCodeException extends Exception {
        InvalidCodeException(String message) {
            super(message);
        }
    }

    private LicenseCode() {
    }

    /** Prüft Signatur und Aufbau – das Ablaufdatum prüft der Aufrufer ({@link License#expiredOn}). */
    public static License verify(String text, PublicKey key) throws InvalidCodeException {
        if (text == null || text.length() > MAX_TEXT) {
            throw new InvalidCodeException("zu lang oder leer");
        }
        byte[] bytes = decode(text);
        if (bytes.length < 10 + SIGNATURE_BYTES + 1) {
            throw new InvalidCodeException("zu kurz");
        }
        int signed = bytes.length - SIGNATURE_BYTES;
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(DOMAIN);
            verifier.update(bytes, 0, signed);
            if (!verifier.verify(bytes, signed, SIGNATURE_BYTES)) {
                throw new InvalidCodeException("Signatur passt nicht");
            }
        } catch (GeneralSecurityException e) {
            throw new InvalidCodeException("Signatur nicht prüfbar: " + e.getMessage());
        }
        ByteBuffer in = ByteBuffer.wrap(bytes, 0, signed);
        if (in.get() != VERSION) {
            throw new InvalidCodeException("unbekannte Version");
        }
        byte[] id = new byte[4];
        in.get(id);
        LocalDate issued = EPOCH.plusDays(Short.toUnsignedInt(in.getShort()));
        int until = Short.toUnsignedInt(in.getShort());
        int length = Byte.toUnsignedInt(in.get());
        if (length == 0 || length > MAX_LICENSEE_BYTES || in.remaining() != length) {
            throw new InvalidCodeException("Firma fehlt oder Länge passt nicht");
        }
        byte[] licensee = new byte[length];
        in.get(licensee);
        return new License(HexFormat.of().formatHex(id), new String(licensee, StandardCharsets.UTF_8), issued,
                until == 0 ? null : EPOCH.plusDays(until));
    }

    /** Stellt einen Code aus – in der Anwendung nur für Tests; im Alltag macht das {@code tools/lizenz/Lizenz.java}. */
    static String issue(License license, PrivateKey key) throws GeneralSecurityException {
        byte[] payload = payload(license);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(DOMAIN);
        signer.update(payload);
        byte[] signature = signer.sign();
        byte[] all = new byte[payload.length + signature.length];
        System.arraycopy(payload, 0, all, 0, payload.length);
        System.arraycopy(signature, 0, all, payload.length, signature.length);
        return format(all);
    }

    static byte[] payload(License license) {
        byte[] licensee = license.licensee().strip().getBytes(StandardCharsets.UTF_8);
        if (licensee.length == 0 || licensee.length > MAX_LICENSEE_BYTES) {
            throw new IllegalArgumentException("Firma: 1 bis " + MAX_LICENSEE_BYTES + " Bytes");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(VERSION);
        out.writeBytes(HexFormat.of().parseHex(license.id()));
        writeDay(out, license.issued());
        if (license.validUntil() == null) {
            out.write(0);
            out.write(0);
        } else {
            writeDay(out, license.validUntil());
        }
        out.write(licensee.length);
        out.writeBytes(licensee);
        return out.toByteArray();
    }

    private static void writeDay(ByteArrayOutputStream out, LocalDate day) {
        long days = ChronoUnit.DAYS.between(EPOCH, day);
        if (days < 1 || days > 0xFFFF) {
            throw new IllegalArgumentException("Datum außerhalb 2024–2203: " + day);
        }
        out.write((int) (days >> 8));
        out.write((int) days);
    }

    /** {@code AIDOCS-XXXXX-XXXXX-…} */
    static String format(byte[] bytes) {
        String encoded = encode(bytes);
        StringBuilder text = new StringBuilder(PREFIX);
        for (int i = 0; i < encoded.length(); i += 5) {
            text.append('-').append(encoded, i, Math.min(encoded.length(), i + 5));
        }
        return text.toString();
    }

    static String encode(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    static byte[] decode(String text) throws InvalidCodeException {
        String compact = text.replaceAll("[\\s\\-]", "").toUpperCase(Locale.ROOT);
        if (compact.startsWith(PREFIX)) {
            compact = compact.substring(PREFIX.length());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : compact.toCharArray()) {
            char normal = switch (c) {
                case 'O' -> '0';
                case 'I', 'L' -> '1';
                default -> c;
            };
            int value = ALPHABET.indexOf(normal);
            if (value < 0) {
                throw new InvalidCodeException("unerlaubtes Zeichen");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    /** Der öffentliche Schlüssel als Base64 (X.509 SubjectPublicKeyInfo), wie ihn {@code Lizenz.java schluessel} ausgibt. */
    static PublicKey publicKey(String base64) throws GeneralSecurityException {
        return KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64.strip())));
    }
}
