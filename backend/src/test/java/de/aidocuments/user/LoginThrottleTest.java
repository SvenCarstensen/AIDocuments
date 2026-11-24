package de.aidocuments.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LoginThrottleTest {

    /** Ein Fehlversuch: gezählt und nicht als Erfolg abgezogen. */
    private static boolean fail(LoginThrottle throttle, String user, String address) {
        return throttle.begin(user, address) != null;
    }

    @Test
    void pausesAnAccountFromOneAddressAfterFiveFailures() {
        LoginThrottle throttle = new LoginThrottle();
        for (int i = 0; i < 5; i++) {
            assertThat(fail(throttle, "Erika", "192.0.2.1")).isTrue();
        }
        assertThat(throttle.begin("erika ", "192.0.2.1")).isNull();
        // von einer anderen Adresse aus kann sich die echte Erika weiter anmelden – kein Aussperren durch Fremde
        assertThat(throttle.begin("ERIKA", "192.0.2.99")).isNotNull();
    }

    @Test
    void pausesAnAddressThatTriesManyAccounts() {
        LoginThrottle throttle = new LoginThrottle();
        for (int i = 0; i < 20; i++) {
            assertThat(fail(throttle, "konto" + i, "198.51.100.7")).isTrue();
        }
        assertThat(throttle.begin("ganz-neu", "198.51.100.7")).isNull();
        assertThat(throttle.begin(null, "198.51.100.7")).isNull(); // auch kein Raten des Einrichtungscodes
        assertThat(throttle.begin("konto0", "198.51.100.8")).isNotNull();
    }

    @Test
    void pausesAnAccountAttackedFromManyAddresses() {
        LoginThrottle throttle = new LoginThrottle();
        for (int i = 0; i < 100; i++) {
            assertThat(fail(throttle, "admin", "203.0.113." + (i % 250))).isTrue();
        }
        assertThat(throttle.begin("admin", "192.0.2.200")).isNull();
    }

    @Test
    void allowsOnlyTenWrongAppCodesPerDayFromUnknownAddresses() {
        LoginThrottle throttle = new LoginThrottle();
        // die echte Erika hat sich von zu Hause schon angemeldet
        throttle.begin("erika", "192.0.2.10").succeeded();
        for (int i = 0; i < 10; i++) {
            assertThat(throttle.beginSecondFactor("erika", "203.0.113." + i)).isNotNull();
        }
        assertThat(throttle.secondFactorLocked("erika")).isTrue();
        assertThat(throttle.beginSecondFactor("erika", "198.51.100.1")).isNull();
        // auch von zu Hause aus nicht: wer das Passwort kennt, käme sonst über eine bekannte Adresse unbegrenzt an
        // weitere Versuche für den Code. Die Anmeldung mit Passwort ist nicht betroffen.
        assertThat(throttle.beginSecondFactor("erika", "192.0.2.10")).isNull();
        assertThat(throttle.begin("erika", "198.51.100.2")).isNotNull();
        assertThat(throttle.begin("erika", "192.0.2.10")).isNotNull();
        // ein richtiger Code zählt nicht mit
        LoginThrottle other = new LoginThrottle();
        for (int i = 0; i < 20; i++) {
            other.beginSecondFactor("max", "203.0.113." + i).succeeded();
        }
        assertThat(other.secondFactorLocked("max")).isFalse();
    }

    @Test
    void limitsPasskeyChallengesPerAddress() {
        LoginThrottle throttle = new LoginThrottle();
        for (int i = 0; i < 30; i++) {
            assertThat(throttle.allowPasskeyChallenge("198.51.100.20")).isTrue();
        }
        assertThat(throttle.allowPasskeyChallenge("198.51.100.20")).isFalse();
        assertThat(throttle.allowPasskeyChallenge("198.51.100.21")).isTrue();
        // IPv6: je /64 gezählt
        for (int i = 0; i < 30; i++) {
            assertThat(throttle.allowPasskeyChallenge("2001:db8:7:7::" + Integer.toHexString(i + 1))).isTrue();
        }
        assertThat(throttle.allowPasskeyChallenge("2001:db8:7:7::beef")).isFalse();
        assertThat(throttle.allowPasskeyChallenge("2001:db8:7:8::1")).isTrue();
    }

    @Test
    void parallelAttemptsCannotOvertakeTheLimit() throws Exception {
        LoginThrottle throttle = new LoginThrottle();
        AtomicInteger admitted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(32);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                if (throttle.begin("erika", "192.0.2.1") != null) {
                    admitted.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (var future : futures) {
            future.get();
        }
        pool.shutdown();
        assertThat(admitted.get()).isEqualTo(5);
    }

    @Test
    void successDoesNotCountAndEndsThePause() {
        LoginThrottle throttle = new LoginThrottle();
        for (int i = 0; i < 4; i++) {
            fail(throttle, "erika", "192.0.2.1");
        }
        throttle.begin("erika", "192.0.2.1").succeeded();
        for (int i = 0; i < 4; i++) {
            assertThat(fail(throttle, "erika", "192.0.2.1")).isTrue();
        }
        // viele erfolgreiche Anmeldungen hinter einem Proxy sperren die Adresse nicht
        for (int i = 0; i < 50; i++) {
            throttle.begin("max", "192.0.2.1").succeeded();
        }
        assertThat(throttle.begin("max", "192.0.2.1")).isNotNull();
    }

    @Test
    void manyInventedNamesDoNotPushOutALock() {
        LoginThrottle.Limiter limiter = new LoginThrottle.Limiter(5, Duration.ofMinutes(5), Duration.ofMinutes(5));
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("admin");
        }
        for (int i = 0; i < 101_000; i++) {
            limiter.tryAcquire("erfunden-" + i);
        }
        assertThat(limiter.isLocked("admin")).isTrue();
    }

    @Test
    void aShortLockDoesNotRestartTheLongerWindow() {
        java.time.Instant start = java.time.Instant.parse("2026-01-01T10:00:00Z");
        java.time.Instant[] now = {start};
        // wie die Grenze je Konto: 100 in der Stunde, danach 15 Minuten Pause (hier mit 3 Versuchen)
        LoginThrottle.Limiter limiter = new LoginThrottle.Limiter(3, Duration.ofHours(1), Duration.ofMinutes(15),
                () -> now[0]);
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("admin")).isTrue();
        }
        now[0] = start.plus(Duration.ofMinutes(20)); // Pause um, die Stunde aber noch nicht
        assertThat(limiter.tryAcquire("admin")).isFalse();
        now[0] = start.plus(Duration.ofMinutes(61));
        assertThat(limiter.tryAcquire("admin")).isTrue();
    }

    @Test
    void ipv6CountsPerSlash64() {
        assertThat(LoginThrottle.addressKey("2001:db8:1:2:aaaa::1")).isEqualTo(LoginThrottle.addressKey("2001:db8:1:2::ffff"));
        assertThat(LoginThrottle.addressKey("2001:db8:1:2::1")).isNotEqualTo(LoginThrottle.addressKey("2001:db8:1:3::1"));
        assertThat(LoginThrottle.addressKey("192.0.2.1")).isEqualTo("192.0.2.1");
        LoginThrottle throttle = new LoginThrottle();
        for (int i = 0; i < 5; i++) {
            assertThat(fail(throttle, "erika", "2001:db8:1:2::" + Integer.toHexString(i + 1))).isTrue();
        }
        assertThat(throttle.begin("erika", "2001:db8:1:2::abcd")).isNull();
    }

    @Test
    void knownAddressesAreNotLockedOutByAnAttackOnTheAccount() {
        LoginThrottle throttle = new LoginThrottle();
        throttle.begin("admin", "192.0.2.10").succeeded(); // der Inhaber hat sich von hier schon angemeldet
        for (int i = 0; i < 100; i++) {
            fail(throttle, "admin", "203.0.113." + (i % 250));
        }
        assertThat(throttle.begin("admin", "198.51.100.1")).isNull();
        assertThat(throttle.begin("admin", "192.0.2.10")).isNotNull();
    }
}
