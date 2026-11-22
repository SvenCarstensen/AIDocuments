package de.aidocuments.user;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Einmalcodes nach RFC 6238 (TOTP) – das Verfahren der Authenticator-Apps (Google, Microsoft, 1Password, Aegis …):
 * HMAC-SHA1, 30 Sekunden je Code, 6 Ziffern. Angenommen wird der aktuelle Code und je einer davor und danach, damit
 * eine leicht falsch gehende Uhr nicht stört.
 */
final class Totp {

    static final int PERIOD_SECONDS = 30;
    static final int DIGITS = 6;
    private static final int WINDOW = 1;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    /** Neuer Schlüssel: 160 Bit, als Base32 (so tippt man ihn auch von Hand in die App). */
    static String newSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return base32(bytes);
    }

    static long stepAt(Instant time) {
        return Math.floorDiv(time.getEpochSecond(), PERIOD_SECONDS);
    }

    /**
     * Prüft einen Code.
     *
     * @param notBefore Intervalle bis einschließlich diesem gelten als verbraucht ({@code null} = keins)
     * @return das Intervall, zu dem der Code passt, oder -1
     */
    static long verify(String secret, String code, Instant now, Long notBefore) {
        String digits = code == null ? "" : code.replaceAll("\\s", "");
        if (!digits.matches("\\d{" + DIGITS + "}")) {
            return -1;
        }
        byte[] key = decodeBase32(secret);
        long current = stepAt(now);
        long found = -1;
        for (long step = current - WINDOW; step <= current + WINDOW; step++) {
            // alle Kandidaten prüfen (gleiche Laufzeit), den passenden merken
            if (constantTimeEquals(code(key, step), digits) && (notBefore == null || step > notBefore)) {
                found = step;
            }
        }
        return found;
    }

    /** Adresse für den QR-Code, die jede Authenticator-App versteht. */
    static String uri(String issuer, String account, String secret) {
        String label = encode(issuer) + ":" + encode(account);
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
    }

    static String code(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format("%0" + DIGITS + "d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 nicht verfügbar", e);
        }
    }

    static String base32(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    static byte[] decodeBase32(String text) {
        String clean = text.replaceAll("[\\s=-]", "").toUpperCase();
        ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (char c : clean.toCharArray()) {
            int value = BASE32.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("Kein Base32");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) (buffer >> (bits - 8)));
                bits -= 8;
            }
        }
        return out.array();
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII),
                b.getBytes(StandardCharsets.US_ASCII));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
