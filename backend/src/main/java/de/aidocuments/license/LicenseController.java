package de.aidocuments.license;

import static de.aidocuments.i18n.ValidationMessages.SIZE;

import de.aidocuments.user.UserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nutzungsart und Freischaltcode ändern – nur für Administratoren (/api/settings/**, siehe SecurityConfig; Änderungen
 * verlangen wie alle Admin-Einstellungen eine frische Bestätigung). Lesen dürfen alle über /api/system.
 */
@RestController
@RequestMapping("/api/settings/license")
public class LicenseController {

    /** @param code {@code null} = den gespeicherten behalten, leer = entfernen */
    public record UpdateRequest(LicenseService.Usage usage, @Size(max = LicenseCode.MAX_TEXT, message = SIZE) String code) {
    }

    private final LicenseService license;

    public LicenseController(LicenseService license) {
        this.license = license;
    }

    @GetMapping
    public LicenseService.Status get() {
        return license.status();
    }

    @PutMapping
    public LicenseService.Status update(@Valid @RequestBody UpdateRequest body,
                                        @AuthenticationPrincipal UserPrincipal principal) {
        return license.update(body.usage(), body.code(), principal == null ? null : principal.username());
    }
}
