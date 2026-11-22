package de.aidocuments.area;

import de.aidocuments.user.UserPrincipal;
import de.aidocuments.web.ApiException;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Welche Bereiche die aktuelle Anfrage sehen und was sie darin tun darf – die eine Stelle, an der das entschieden wird.
 * Alles, was Dokumente liest, fragt hier: Einzelabruf, Dateien, Suche, Zähler, Filterleiste, Verlauf, KI-Suche; alles,
 * was sie ändert oder löscht, prüft das Recht ({@link Permission}) im Bereich des Dokuments.
 *
 * <p>Ohne angemeldeten Benutzer gibt es keine Einschränkung: Das sind Hintergrundarbeiten (Abruf, Verarbeitung) und
 * Teilen-Links, die über ihr Token genau ein Dokument öffnen. Alle übrigen Pfade unter {@code /api} verlangen eine
 * Anmeldung (siehe SecurityConfig).
 */
@Component
public class AreaAccess {

    private final AreaRepository areas;

    public AreaAccess(AreaRepository areas) {
        this.areas = areas;
    }

    /** Die sichtbaren Bereiche; {@code null} = alle (Administratoren, Hintergrundarbeit). Leer = keiner. */
    public Set<String> visible() {
        Map<String, Permission> permissions = permissions();
        return permissions == null ? null : permissions.keySet();
    }

    /** Das Recht je sichtbarem Bereich; {@code null} = überall alles (Administratoren, Hintergrundarbeit). */
    public Map<String, Permission> permissions() {
        UserPrincipal user = currentUser();
        if (user == null || user.isAdmin()) {
            return null;
        }
        return areas.permissionsFor(user.id());
    }

    /** Was die Anfrage im Bereich darf; {@code null}, wenn sie ihn nicht sieht. */
    public Permission permission(String areaId) {
        Map<String, Permission> permissions = permissions();
        return permissions == null ? Permission.DELETE : permissions.get(areaId);
    }

    public boolean allows(String areaId, Permission needed) {
        Permission granted = permission(areaId);
        return granted != null && granted.allows(needed);
    }

    /** 403 mit einer Erklärung, wenn das Recht im (sichtbaren) Bereich nicht reicht. */
    public void require(String areaId, Permission needed) {
        if (!allows(areaId, needed)) {
            throw ApiException.of(HttpStatus.FORBIDDEN,
                    needed == Permission.DELETE ? "error.permission.delete" : "error.permission.edit");
        }
    }

    public boolean canSee(String areaId) {
        Set<String> visible = visible();
        return visible == null || visible.contains(areaId);
    }

    /** Ob eine bestimmte Person (nicht die anfragende) den Bereich sieht – etwa wer eine Erinnerung bekommt. */
    public boolean canSee(String userId, boolean admin, String areaId) {
        return admin || areas.visibleFor(userId).contains(areaId);
    }

    /**
     * Für Ziele (Hochladen, Verschieben): ein unsichtbarer Bereich gilt als nicht vorhanden; in einem nur lesbaren darf
     * nichts abgelegt werden.
     */
    public void requireTarget(String areaId) {
        if (areaId == null || areas.findById(areaId).isEmpty() || !canSee(areaId)) {
            throw ApiException.badRequest("error.area.notFound");
        }
        require(areaId, Permission.EDIT);
    }

    /**
     * Wohin ein Dokument ohne ausdrückliche Wahl kommt: der allgemeine Bereich, sonst der erste, in dem die Anfrage
     * ablegen darf.
     */
    public String defaultArea() {
        Map<String, Permission> permissions = permissions();
        if (permissions == null) {
            return Area.GENERAL;
        }
        if (permissions.isEmpty()) {
            throw ApiException.badRequest("error.area.none");
        }
        if (permissions.getOrDefault(Area.GENERAL, Permission.READ).allows(Permission.EDIT)) {
            return Area.GENERAL;
        }
        return permissions.entrySet().stream().filter(e -> e.getValue().allows(Permission.EDIT))
                .map(Map.Entry::getKey).findFirst()
                .orElseThrow(() -> ApiException.of(HttpStatus.FORBIDDEN, "error.permission.upload"));
    }

    private static UserPrincipal currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof UserPrincipal user ? user : null;
    }
}
