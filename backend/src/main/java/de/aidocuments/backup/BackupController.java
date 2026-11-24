package de.aidocuments.backup;

import de.aidocuments.area.AreaAccess;
import de.aidocuments.document.ArchiveImport;
import de.aidocuments.document.FileStorage;
import de.aidocuments.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sicherung und Import, nur für Admins (wie alles unter {@code /api/settings}); Anlegen und Import verlangen wie jede
 * Änderung dort einen frischen App-Code.
 */
@RestController
@RequestMapping("/api/settings/backup")
public class BackupController {

    private static final Logger log = LoggerFactory.getLogger(BackupController.class);

    public record TicketDto(String token) {
    }

    private final BackupService backups;
    private final AutoBackup autoBackup;
    private final ArchiveImport archiveImport;
    private final FileStorage storage;
    private final AreaAccess access;

    public BackupController(BackupService backups, AutoBackup autoBackup, ArchiveImport archiveImport,
                            FileStorage storage, AreaAccess access) {
        this.backups = backups;
        this.autoBackup = autoBackup;
        this.archiveImport = archiveImport;
        this.storage = storage;
        this.access = access;
    }

    @GetMapping
    public BackupService.BackupStatus status() {
        return backups.status();
    }

    /** Automatische Sicherung: Einstellungen, letzter Lauf, vorhandene Stände. */
    @GetMapping("/auto")
    public AutoBackup.AutoStatus autoStatus() {
        return autoBackup.status();
    }

    @org.springframework.web.bind.annotation.PutMapping("/auto")
    public AutoBackup.AutoStatus updateAuto(@jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody
                                            AutoBackup.UpdateRequest request) {
        autoBackup.update(request);
        return autoBackup.status();
    }

    /** Jetzt automatisch sichern (im Hintergrund); 409, wenn schon eine läuft. */
    @PostMapping("/auto/run")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public void runAuto() {
        if (!autoBackup.runSoon()) {
            throw ApiException.conflict("error.backup.running");
        }
    }

    /**
     * @param password      eigenes Passwort; leer = unverschlüsselt
     * @param useConfigured stattdessen mit dem Passwort aus AIDOCS_BACKUP_PASSWORD verschlüsseln
     */
    public record PrepareRequest(@jakarta.validation.constraints.Size(max = BackupService.MAX_PASSWORD,
            message = de.aidocuments.i18n.ValidationMessages.SIZE) String password, Boolean useConfigured) {
    }

    /** Schritt 1: den Download freigeben – das Passwort steht im Anfragekörper, nie in der Adresse. */
    @PostMapping
    public TicketDto prepare(@jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody(
            required = false) PrepareRequest body, HttpServletRequest request) {
        return new TicketDto(body == null ? backups.prepare(request.getSession())
                : backups.prepare(request.getSession(), body.password(),
                Boolean.TRUE.equals(body.useConfigured())));
    }

    /** Schritt 2: das ZIP – ein gewöhnlicher Seitenaufruf, der Schlüssel gilt einmal. */
    @GetMapping("/{token}")
    public void download(@PathVariable String token, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        BackupService.Ticket ticket = backups.take(request.getSession(false), token);
        if (ticket == null) {
            throw ApiException.notFound("error.export.notFound");
        }
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("AIDocuments-Sicherung-" + LocalDate.now() + (ticket.password() == null ? "" : "-verschluesselt")
                        + ".zip").build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        try {
            backups.write(response.getOutputStream(), LocaleContextHolder.getLocale(), currentUser(),
                    ticket.password());
        } finally {
            if (ticket.password() != null) {
                java.util.Arrays.fill(ticket.password(), '\0');
            }
        }
    }

    /**
     * Ein Export (ZIP) als Rohdaten im Anfragekörper – nicht als Formular-Upload, denn ein Archiv ist schnell größer
     * als die Grenze für einzelne Dateien. Angenommen wird so viel, wie im Datenverzeichnis Platz ist.
     */
    @PostMapping(value = "/import", consumes = {"application/zip", "application/x-zip-compressed",
            "application/octet-stream"})
    public ArchiveImport.ImportResult importArchive(@RequestParam(required = false) String area,
                                                    @RequestParam(defaultValue = "true") boolean matchAreas,
                                                    HttpServletRequest request) throws IOException {
        String target = area == null || area.isBlank() ? access.defaultArea() : area;
        access.requireTarget(target);
        Path zip = storage.temporaryFile(".zip");
        try {
            long room = storage.usableSpace() - FileStorage.MIN_FREE_BYTES;
            if (room <= 0) {
                throw new FileStorage.StorageFullException();
            }
            ArchiveImport.copyLimited(request.getInputStream(), zip, room);
            log.info("Import eines Exports durch {} ({} MB)", currentUser(), Files.size(zip) >> 20);
            return archiveImport.importArchive(zip, target, matchAreas, LocaleContextHolder.getLocale());
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    private static String currentUser() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "?" : auth.getName();
    }
}
