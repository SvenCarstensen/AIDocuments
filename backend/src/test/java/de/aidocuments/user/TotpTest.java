package de.aidocuments.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TotpTest {

    /** Schlüssel aus RFC 6238, Anhang B ("12345678901234567890", SHA-1). */
    private static final String RFC_SECRET = Totp.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    void matchesTheTestVectorsOfRfc6238() {
        // RFC 6238 nennt achtstellige Codes; die App zeigt die letzten sechs Ziffern
        assertThat(Totp.code(Totp.decodeBase32(RFC_SECRET), Totp.stepAt(Instant.ofEpochSecond(59)))).isEqualTo("287082");
        assertThat(Totp.code(Totp.decodeBase32(RFC_SECRET), Totp.stepAt(Instant.ofEpochSecond(1111111109))))
                .isEqualTo("081804");
        assertThat(Totp.code(Totp.decodeBase32(RFC_SECRET), Totp.stepAt(Instant.ofEpochSecond(2000000000))))
                .isEqualTo("279037");
    }

    @Test
    void acceptsNeighbouringIntervalsButNoReuse() {
        Instant now = Instant.ofEpochSecond(1111111109);
        long step = Totp.stepAt(now);
        String previous = Totp.code(Totp.decodeBase32(RFC_SECRET), step - 1);
        assertThat(Totp.verify(RFC_SECRET, previous, now, null)).isEqualTo(step - 1);
        assertThat(Totp.verify(RFC_SECRET, "081 804", now, null)).isEqualTo(step);
        // schon verbraucht: derselbe Code ein zweites Mal
        assertThat(Totp.verify(RFC_SECRET, "081804", now, step)).isEqualTo(-1);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(Totp.decodeBase32(RFC_SECRET), step - 2), now, null)).isEqualTo(-1);
        assertThat(Totp.verify(RFC_SECRET, "abc", now, null)).isEqualTo(-1);
    }

    @Test
    void base32RoundTripAndUri() {
        String secret = Totp.newSecret();
        assertThat(secret).hasSize(32).matches("[A-Z2-7]+");
        assertThat(Totp.base32(Totp.decodeBase32(secret))).isEqualTo(secret);
        assertThat(Totp.uri("AIDocuments", "erika", "ABC"))
                .isEqualTo("otpauth://totp/AIDocuments:erika?secret=ABC&issuer=AIDocuments&algorithm=SHA1&digits=6&period=30");
    }
}
