package de.aidocuments.license;

import de.aidocuments.persistence.SettingsStore;
import de.aidocuments.web.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.time.Instant;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Nutzungsart und Freischaltcode. AIDocuments ist für Privatpersonen, Vereine und Behörden kostenlos; Unternehmen
 * brauchen einen Freischaltcode ({@link LicenseCode}). Ohne Code bleibt alles nutzbar – die Oberfläche weist nur
 * darauf hin. Der Code gilt nicht nur für eine Installation; die Anwendung zeigt aber überall, für wen er ausgestellt
 * ist.
 */
@Service
public class LicenseService {

    private static final Logger log = LoggerFactory.getLogger(LicenseService.class);

    /** Wo der öffentliche Schlüssel liegt, mit dem die Codes geprüft werden (eine Zeile Base64; # = Kommentar). */
    static final String PUBLIC_KEY_RESOURCE = "/license/public-key.txt";
    static final String ID = "license";

    public enum Usage { PRIVATE, CLUB, AUTHORITY, BUSINESS }

    /**
     * {@code UNSET}: noch nicht angegeben · {@code FREE}: privat, Verein oder Behörde · {@code LICENSED}: Unternehmen
     * mit gültigem Code · {@code EXPIRED}: Code abgelaufen · {@code UNLICENSED}: Unternehmen ohne (gültigen) Code
     */
    public enum State { UNSET, FREE, LICENSED, EXPIRED, UNLICENSED }

    /** Was gespeichert wird – der Code im Wortlaut, damit er bei jedem Abruf neu geprüft werden kann. */
    public record Stored(Usage usage, String code, Instant changedAt) {
    }

    public record Status(Usage usage, State state, String licensee, LocalDate validUntil, String licenseId,
                         boolean codeStored) {
    }

    private final SettingsStore store;
    private final PublicKey key;

    @Autowired
    public LicenseService(SettingsStore store) {
        this(store, loadKey());
    }

    LicenseService(SettingsStore store, PublicKey key) {
        this.store = store;
        this.key = key;
    }

    public Status status() {
        Stored stored = store.find(ID, Stored.class);
        if (stored == null || stored.usage() == null) {
            return new Status(null, State.UNSET, null, null, null, false);
        }
        if (stored.usage() != Usage.BUSINESS) {
            return new Status(stored.usage(), State.FREE, null, null, null, false);
        }
        if (stored.code() == null || key == null) {
            return new Status(Usage.BUSINESS, State.UNLICENSED, null, null, null, stored.code() != null);
        }
        try {
            LicenseCode.License license = LicenseCode.verify(stored.code(), key);
            State state = license.expiredOn(LocalDate.now()) ? State.EXPIRED : State.LICENSED;
            return new Status(Usage.BUSINESS, state, license.licensee(), license.validUntil(), license.id(), true);
        } catch (LicenseCode.InvalidCodeException e) {
            return new Status(Usage.BUSINESS, State.UNLICENSED, null, null, null, true);
        }
    }

    /**
     * Prüft einen Code, ohne etwas zu speichern (etwa vor der Ersteinrichtung).
     *
     * @return die Lizenz; {@code null}, wenn kein Code angegeben ist
     */
    public LicenseCode.License check(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        if (key == null) {
            throw ApiException.badRequest("error.license.unavailable");
        }
        LicenseCode.License license;
        try {
            license = LicenseCode.verify(code, key);
        } catch (LicenseCode.InvalidCodeException e) {
            log.info("Ungültiger Freischaltcode abgelehnt: {}", e.getMessage());
            throw ApiException.badRequest("error.license.invalid");
        }
        if (license.expiredOn(LocalDate.now())) {
            throw ApiException.badRequest("error.license.expired", license.validUntil());
        }
        return license;
    }

    /**
     * @param code {@code null} = den gespeicherten behalten, leer = entfernen. Gilt nur für Unternehmen – bei den
     *             übrigen Nutzungsarten wird kein Code gespeichert.
     */
    public Status update(Usage usage, String code, String changedBy) {
        if (usage == null) {
            throw ApiException.badRequest("error.license.usageMissing");
        }
        Stored previous = store.find(ID, Stored.class);
        String keep = code == null && previous != null ? previous.code() : null;
        String newCode = usage != Usage.BUSINESS ? null
                : code == null ? keep : code.isBlank() ? null : normalize(code);
        LicenseCode.License license = code == null || code.isBlank() || usage != Usage.BUSINESS ? null : check(code);
        store.save(ID, new Stored(usage, newCode, Instant.now()));
        if (license != null) {
            log.info("Freischaltcode {} für „{}“ eingetragen von {} (gültig bis {})", license.id(), license.licensee(),
                    changedBy, license.validUntil() == null ? "unbefristet" : license.validUntil());
        } else {
            log.info("Nutzungsart {} eingetragen von {}", usage, changedBy);
        }
        return status();
    }

    /** Wie eingegeben, aber ohne Zeilenumbrüche und Leerraum am Rand – geprüft wird ohnehin unabhängig davon. */
    private static String normalize(String code) {
        return code.replaceAll("\\s+", "").toUpperCase(java.util.Locale.ROOT);
    }

    private static PublicKey loadKey() {
        try (InputStream in = LicenseService.class.getResourceAsStream(PUBLIC_KEY_RESOURCE)) {
            if (in == null) {
                return null;
            }
            for (String line : new String(in.readAllBytes(), StandardCharsets.US_ASCII).split("\\R")) {
                String value = line.strip();
                if (!value.isEmpty() && !value.startsWith("#")) {
                    return LicenseCode.publicKey(value);
                }
            }
            log.warn("Kein öffentlicher Schlüssel für Freischaltcodes hinterlegt ({}) – Codes lassen sich nicht prüfen",
                    PUBLIC_KEY_RESOURCE);
            return null;
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            log.error("Öffentlicher Schlüssel für Freischaltcodes unlesbar: {}", e.toString());
            return null;
        }
    }
}
