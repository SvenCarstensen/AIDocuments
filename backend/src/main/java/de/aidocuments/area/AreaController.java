package de.aidocuments.area;

import static de.aidocuments.i18n.ValidationMessages.SIZE;

import de.aidocuments.importer.ImportSettingsService;
import de.aidocuments.user.UserPrincipal;
import de.aidocuments.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bereiche: Lesen dürfen alle Angemeldeten (nur die eigenen sichtbaren, ohne Mitgliederliste), Anlegen, Ändern und
 * Löschen nur Administratoren (SecurityConfig) – mit frischem App-Code, denn es ändert, wer welche Dokumente sieht.
 */
@RestController
@RequestMapping("/api/areas")
public class AreaController {

    private static final Logger log = LoggerFactory.getLogger(AreaController.class);

    public record MemberDto(String userId, Permission access) {
    }

    /**
     * @param everyoneAccess was alle dürfen, wenn der Bereich für alle freigegeben ist
     * @param memberIds      und {@code members}: nur für Administratoren, sonst leer
     * @param documentCount  Dokumente im Bereich
     * @param myAccess       was der Anfragende im Bereich darf
     */
    public record AreaDto(String id, String name, boolean everyone, Permission everyoneAccess, List<String> memberIds,
                          List<MemberDto> members, long documentCount, Permission myAccess) {
    }

    public record AreaRequest(
            /* beim allgemeinen Bereich: leer = der übersetzte Standardname */
            @Size(max = 120, message = SIZE) String name,
            boolean everyone,
            /* fehlt = bearbeiten */
            Permission everyoneAccess,
            /* Mitglieder mit ihrem Recht; ältere Aufrufer schicken nur memberIds (dann: bearbeiten) */
            @Size(max = 1000, message = SIZE) List<MemberDto> members,
            @Size(max = 1000, message = SIZE) List<String> memberIds) {
    }

    private final AreaRepository areas;
    private final AreaAccess access;
    private final ImportSettingsService importSettings;

    public AreaController(AreaRepository areas, AreaAccess access, ImportSettingsService importSettings) {
        this.areas = areas;
        this.access = access;
        this.importSettings = importSettings;
    }

    @GetMapping
    public List<AreaDto> list(@AuthenticationPrincipal UserPrincipal principal) {
        Set<String> visible = access.visible();
        boolean admin = principal.isAdmin();
        Map<String, Map<String, Permission>> members = admin ? areas.members() : Map.of();
        Map<String, Long> counts = areas.documentCounts();
        Map<String, Permission> mine = access.permissions();
        return areas.findAll().stream()
                .filter(area -> visible == null || visible.contains(area.id()))
                .map(area -> dto(area, members.getOrDefault(area.id(), Map.of()), counts.getOrDefault(area.id(), 0L),
                        mine == null ? Permission.DELETE : mine.get(area.id())))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AreaDto create(@Valid @RequestBody AreaRequest request, @AuthenticationPrincipal UserPrincipal principal) {
        String name = requireName(request.name(), null);
        Area area = new Area(UUID.randomUUID().toString(), name, request.everyone(), everyoneAccess(request),
                Instant.now());
        areas.save(area);
        areas.replaceMembers(area.id(), validMembers(request));
        log.info("Bereich „{}“ angelegt von {}", name, principal.username());
        return dto(area);
    }

    @PutMapping("/{id}")
    public AreaDto update(@PathVariable String id, @Valid @RequestBody AreaRequest request,
                          @AuthenticationPrincipal UserPrincipal principal) {
        Area existing = require(id);
        String name = existing.isGeneral() && (request.name() == null || request.name().isBlank())
                ? null : requireName(request.name(), id);
        Area area = new Area(id, name, request.everyone(), everyoneAccess(request), existing.createdAt());
        areas.save(area);
        Map<String, Permission> members = validMembers(request);
        areas.replaceMembers(id, members);
        log.info("Bereich „{}“ geändert von {} (für alle: {}, Mitglieder: {})", name == null ? id : name,
                principal.username(), area.everyone() ? area.everyoneAccess() : "nein", members);
        return dto(area);
    }

    /** Nur leere Bereiche: ohne Dokumente (auch nicht im Papierkorb), Postfächer oder Eingangsordner. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, @AuthenticationPrincipal UserPrincipal principal) {
        Area area = require(id);
        if (area.isGeneral()) {
            throw ApiException.conflict("error.area.generalUndeletable");
        }
        if (areas.usage(id) > 0 || id.equals(importSettings.current().folderAreaId())) {
            throw ApiException.conflict("error.area.inUse");
        }
        areas.delete(id);
        log.info("Bereich „{}“ gelöscht von {}", area.name(), principal.username());
    }

    private Area require(String id) {
        return areas.findById(id).orElseThrow(() -> ApiException.notFound("error.area.notFound"));
    }

    private String requireName(String requested, String ownId) {
        String name = requested == null ? "" : requested.strip();
        if (name.isEmpty()) {
            throw ApiException.badRequest("error.area.nameRequired");
        }
        boolean taken = areas.findAll().stream()
                .anyMatch(area -> !area.id().equals(ownId) && area.name() != null && area.name().equalsIgnoreCase(name));
        if (taken) {
            throw ApiException.conflict("error.area.nameTaken");
        }
        return name;
    }

    private static Permission everyoneAccess(AreaRequest request) {
        return request.everyoneAccess() == null ? Permission.EDIT : request.everyoneAccess();
    }

    private Map<String, Permission> validMembers(AreaRequest request) {
        Map<String, Permission> requested = new LinkedHashMap<>();
        if (request.members() != null) {
            for (MemberDto member : request.members()) {
                if (member == null || member.userId() == null) {
                    throw ApiException.badRequest("error.area.unknownUser");
                }
                requested.merge(member.userId(), member.access() == null ? Permission.EDIT : member.access(),
                        Permission::max);
            }
        } else if (request.memberIds() != null) {
            request.memberIds().forEach(userId -> requested.put(userId, Permission.EDIT));
        }
        if (!areas.userIds().containsAll(requested.keySet())) {
            throw ApiException.badRequest("error.area.unknownUser");
        }
        return requested;
    }

    private AreaDto dto(Area area) {
        return dto(area, areas.members().getOrDefault(area.id(), Map.of()),
                areas.documentCounts().getOrDefault(area.id(), 0L), Permission.DELETE);
    }

    private static AreaDto dto(Area area, Map<String, Permission> members, long documentCount, Permission myAccess) {
        return new AreaDto(area.id(), area.name(), area.everyone(), area.everyoneAccess(),
                List.copyOf(members.keySet()),
                members.entrySet().stream().map(e -> new MemberDto(e.getKey(), e.getValue())).toList(),
                documentCount, myAccess);
    }
}
