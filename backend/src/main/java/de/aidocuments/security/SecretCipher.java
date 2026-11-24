package de.aidocuments.security;

import de.aidocuments.config.AppProperties;
import de.aidocuments.i18n.Localizable;
import de.aidocuments.i18n.Msg;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Verschlüsselt Postfach-Passwörter (AES-256-GCM), bevor sie in der Datenbank landen.
 * Der Schlüssel liegt in {@code <data-dir>/secret.key} und wird beim ersten Start erzeugt.
 * Wer die Datenbank sichert, muss diese Datei mitsichern – ohne sie sind die Passwörter nicht lesbar.
 */
@Component
public class SecretCipher {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SecretCipher.class);
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(AppProperties properties) {
        this.key = new SecretKeySpec(loadOrCreateKey(properties.dataRoot().resolve("secret.key")), "AES");
    }

    /** Ver- oder Entschlüsselung gescheitert: fürs Protokoll ein deutscher Satz, für den Anwender ein übersetzbarer. */
    public static final class SecretException extends IllegalStateException implements Localizable {

        private final Msg msg;

        SecretException(String logMessage, Msg msg, Throwable cause) {
            super(logMessage, cause);
            this.msg = msg;
        }

        @Override
        public Msg msg() {
            return msg;
        }
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (GeneralSecurityException e) {
            throw new SecretException("Verschlüsselung fehlgeschlagen", Msg.of("error.secret.encryptFailed"), e);
        }
    }

    public String decrypt(String encoded) {
        try {
            byte[] data = Base64.getDecoder().decode(encoded);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 0, IV_LENGTH));
            byte[] plain = cipher.doFinal(data, IV_LENGTH, data.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new SecretException("Gespeichertes Passwort kann nicht entschlüsselt werden (secret.key geändert?)",
                    Msg.of("error.secret.decryptFailed"), e);
        }
    }

    static byte[] loadOrCreateKey(Path file) {
        try {
            if (Files.exists(file)) {
                restrictToOwner(file); // auch eine ältere, zu offen angelegte Datei
                byte[] existing = Base64.getDecoder().decode(Files.readString(file).trim());
                if (existing.length != 32) {
                    throw new IllegalStateException("Ungültige Schlüsseldatei: " + file);
                }
                return existing;
            }
            Files.createDirectories(file.getParent());
            byte[] fresh = new byte[32];
            new SecureRandom().nextBytes(fresh);
            // erst vollständig in eine Nebendatei, die von Anfang an nur der Eigentümer lesen kann (0600) – kein
            // Augenblick, in dem der Schlüssel für andere lesbar oder nur halb geschrieben dasteht –, dann umbenennen
            Path part = file.resolveSibling(file.getFileName() + ".part");
            Files.deleteIfExists(part);
            boolean posix = Files.getFileStore(file.getParent()).supportsFileAttributeView("posix");
            java.nio.file.attribute.FileAttribute<?>[] attributes = posix
                    ? new java.nio.file.attribute.FileAttribute<?>[]{
                            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
                    : new java.nio.file.attribute.FileAttribute<?>[0];
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(part,
                    java.util.Set.of(java.nio.file.StandardOpenOption.CREATE_NEW,
                            java.nio.file.StandardOpenOption.WRITE), attributes)) {
                ByteBuffer content = ByteBuffer.wrap(
                        Base64.getEncoder().encodeToString(fresh).getBytes(StandardCharsets.US_ASCII));
                while (content.hasRemaining()) {
                    channel.write(content);
                }
                channel.force(true);
            }
            try {
                Files.move(part, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(part, file);
            }
            return fresh;
        } catch (IOException e) {
            throw new UncheckedIOException("Schlüsseldatei kann nicht gelesen/erzeugt werden: " + file, e);
        }
    }

    /** Nur für den Eigentümer lesbar (0600) – wo das Dateisystem POSIX-Rechte kennt. */
    private static void restrictToOwner(Path file) {
        try {
            if (!Files.getFileStore(file).supportsFileAttributeView("posix")) {
                return; // Windows: Dateirechte werden über das Benutzerprofil geregelt
            }
            // Gruppe und andere verlieren jedes Recht; was der Eigentümer hat, bleibt (höchstens 0600)
            java.util.Set<java.nio.file.attribute.PosixFilePermission> current = Files.getPosixFilePermissions(file);
            java.util.Set<java.nio.file.attribute.PosixFilePermission> wanted = java.util.EnumSet.noneOf(
                    java.nio.file.attribute.PosixFilePermission.class);
            wanted.addAll(current);
            wanted.retainAll(PosixFilePermissions.fromString("rw-------"));
            if (!current.equals(wanted)) {
                Files.setPosixFilePermissions(file, wanted);
                log.info("Rechte der Schlüsseldatei {} auf {} eingeschränkt", file, PosixFilePermissions.toString(wanted));
            }
        } catch (IOException | UnsupportedOperationException e) {
            log.warn("Rechte der Schlüsseldatei {} lassen sich nicht einschränken: {}", file, e.toString());
        }
    }
}
