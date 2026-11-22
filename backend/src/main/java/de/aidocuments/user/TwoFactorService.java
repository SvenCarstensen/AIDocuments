package de.aidocuments.user;

import de.aidocuments.security.SecretCipher;
import de.aidocuments.web.ApiException;
import io.nayuki.qrcodegen.QrCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Zwei-Faktor-Anmeldung mit einer Authenticator-App (TOTP). Der Schlüssel liegt verschlüsselt in der Datenbank
 * (secret.key), jeder Code gilt nur einmal, und zehn Wiederherstellungscodes (gespeichert nur als SHA-256) helfen,
 * wenn das Gerät mit der App verloren ging. Ein- und Abschalten verlangt das Passwort – eine fremde, offen gelassene
 * Sitzung kann den Inhaber damit weder aussperren noch den Schutz entfernen.
 */
@Service
public class TwoFactorService {

    static final String ISSUER = "AIDocuments";
    static final int RECOVERY_CODES = 10;
    private static final String RECOVERY_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";

    private final AppUserRepository users;
    private final UserService userService;
    private final SecretCipher cipher;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    public TwoFactorService(AppUserRepository users, UserService userService, SecretCipher cipher,
                            PasswordEncoder passwordEncoder) {
        this.users = users;
        this.userService = userService;
        this.cipher = cipher;
        this.passwordEncoder = passwordEncoder;
    }

    public record Status(boolean enabled, int recoveryCodesLeft) {
    }

    /** Vorschlag für die Einrichtung: Schlüssel, Adresse für die App und dieselbe als QR-Code (SVG, data-URL). */
    public record Setup(String secret, String uri, String qrCode) {
    }

    public record Enabled(List<String> recoveryCodes) {
    }

    public Status status(String userId) {
        AppUser user = require(userId);
        return new Status(user.isTwoFactorEnabled(), user.isTwoFactorEnabled() ? user.getRecoveryCodeHashes().size() : 0);
    }

    /** Erzeugt einen neuen Schlüssel; gespeichert wird er erst mit {@link #enable}. */
    public Setup newSetup(String userId) {
        AppUser user = require(userId);
        if (user.isTwoFactorEnabled()) {
            throw ApiException.conflict("error.twoFactor.alreadyEnabled");
        }
        String secret = Totp.newSecret();
        String uri = Totp.uri(ISSUER, user.getUsername(), secret);
        return new Setup(secret, uri, qrDataUrl(uri));
    }

    /**
     * Schaltet die Zwei-Faktor-Anmeldung ein, sobald ein Code aus der App passt.
     *
     * @return die Wiederherstellungscodes – nur dieses eine Mal im Klartext
     */
    public synchronized Enabled enable(String userId, String password, String secret, String code) {
        AppUser user = require(userId);
        requirePassword(user, password);
        if (user.isTwoFactorEnabled()) {
            throw ApiException.conflict("error.twoFactor.alreadyEnabled");
        }
        long step = Totp.verify(secret, code, Instant.now(), null);
        if (step < 0) {
            throw ApiException.badRequest("error.twoFactor.invalidCode");
        }
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            codes.add(newRecoveryCode());
        }
        user.setTwoFactor(cipher.encrypt(secret), step, codes.stream().map(TwoFactorService::hash).toList());
        users.save(user);
        userService.invalidateSessions(userId);
        return new Enabled(codes);
    }

    /** Schaltet die Zwei-Faktor-Anmeldung ab – mit Passwort und einem gültigen Code (oder Wiederherstellungscode). */
    public synchronized void disable(String userId, String password, String code) {
        AppUser user = require(userId);
        requirePassword(user, password);
        if (!user.isTwoFactorEnabled()) {
            throw ApiException.conflict("error.twoFactor.notEnabled");
        }
        if (user.getRole() == Role.ADMIN) {
            throw ApiException.conflict("error.twoFactor.requiredForAdmin");
        }
        if (!consume(user, code)) {
            throw ApiException.badRequest("error.twoFactor.invalidCode");
        }
        user.setTwoFactor(null, null, null);
        users.save(user);
        userService.invalidateSessions(userId);
    }

    /** Für Administratoren: Gerät verloren – das Konto meldet sich danach wieder nur mit dem Passwort an. */
    public synchronized void reset(String userId) {
        AppUser user = require(userId);
        user.setTwoFactor(null, null, null);
        users.save(user);
        userService.invalidateSessions(userId);
    }

    public boolean isEnabled(String userId) {
        return users.findById(userId).map(AppUser::isTwoFactorEnabled).orElse(false);
    }

    /** Zweiter Schritt der Anmeldung: Code aus der App oder ein (dann verbrauchter) Wiederherstellungscode. */
    public synchronized boolean verifyLogin(String userId, String code) {
        AppUser user = users.findById(userId).orElse(null);
        if (user == null || !user.isTwoFactorEnabled()) {
            return false;
        }
        if (!consume(user, code)) {
            return false;
        }
        users.save(user);
        return true;
    }

    /** Prüft einen Code und vermerkt ihn als verbraucht (am Objekt; speichern muss der Aufrufer). */
    private boolean consume(AppUser user, String code) {
        String given = code == null ? "" : code.strip();
        if (given.replaceAll("\\s", "").matches("\\d{" + Totp.DIGITS + "}")) {
            String secret = cipher.decrypt(user.getTotpSecretEncrypted());
            long step = Totp.verify(secret, given, Instant.now(), user.getTotpLastStep());
            if (step < 0) {
                return false;
            }
            user.setTotpLastStep(step);
            return true;
        }
        String hashed = hash(normalizeRecoveryCode(given));
        List<String> remaining = new ArrayList<>(user.getRecoveryCodeHashes());
        boolean found = false;
        for (String candidate : user.getRecoveryCodeHashes()) {
            if (MessageDigest.isEqual(candidate.getBytes(StandardCharsets.US_ASCII),
                    hashed.getBytes(StandardCharsets.US_ASCII))) {
                found = true;
                remaining.remove(candidate);
            }
        }
        if (found) {
            user.setTwoFactor(user.getTotpSecretEncrypted(), user.getTotpLastStep(), remaining);
        }
        return found;
    }

    private void requirePassword(AppUser user, String password) {
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw ApiException.badRequest("error.user.currentPasswordWrong");
        }
    }

    private AppUser require(String id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("error.user.notFound"));
    }

    /** Zehn Zeichen aus einem Alphabet ohne verwechselbare Zeichen, als xxxxx-xxxxx (rund 50 Bit). */
    private String newRecoveryCode() {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i == 5) {
                code.append('-');
            }
            code.append(RECOVERY_ALPHABET.charAt(random.nextInt(RECOVERY_ALPHABET.length())));
        }
        return code.toString();
    }

    private static String normalizeRecoveryCode(String code) {
        String clean = code.toLowerCase().replaceAll("[^a-z0-9]", "");
        return clean.length() == 10 ? clean.substring(0, 5) + "-" + clean.substring(5) : clean;
    }

    static String hash(String recoveryCode) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalizeRecoveryCode(recoveryCode).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** QR-Code als SVG in einer data-URL – die Content-Security-Policy erlaubt Bilder aus data:. */
    static String qrDataUrl(String text) {
        QrCode qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
        int border = 2;
        int size = qr.size + border * 2;
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < qr.size; y++) {
            for (int x = 0; x < qr.size; x++) {
                if (qr.getModule(x, y)) {
                    path.append('M').append(x + border).append(',').append(y + border).append("h1v1h-1z");
                }
            }
        }
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + size + " " + size
                + "\" shape-rendering=\"crispEdges\"><rect width=\"100%\" height=\"100%\" fill=\"#fff\"/>"
                + "<path d=\"" + path + "\" fill=\"#000\"/></svg>";
        return "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
    }
}
