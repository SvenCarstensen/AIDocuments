package de.aidocuments.mail;

import de.aidocuments.mail.MailDtos.ConnectionTestDto;
import de.aidocuments.mail.MailDtos.ConnectionTestRequest;
import de.aidocuments.mail.MailDtos.MailAccountDto;
import de.aidocuments.mail.MailDtos.MailAccountRequest;
import de.aidocuments.web.ApiException;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Verwaltung der IMAP-Postfächer – nur für Administratoren (siehe SecurityConfig). */
@RestController
@RequestMapping("/api/mail-accounts")
public class MailAccountController {

    private final MailAccountService service;
    private final MailSyncService syncService;
    private final ThreadPoolTaskExecutor executor;

    public MailAccountController(MailAccountService service, MailSyncService syncService,
                                 @Qualifier("importExecutor") ThreadPoolTaskExecutor executor) {
        this.service = service;
        this.syncService = syncService;
        this.executor = executor;
    }

    @GetMapping
    public List<MailAccountDto> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MailAccountDto create(@Valid @RequestBody MailAccountRequest request) {
        MailAccountDto created = service.create(request);
        if (created.enabled()) {
            executor.execute(() -> syncService.sync(created.id()));
        }
        return created;
    }

    @PutMapping("/{id}")
    public MailAccountDto update(@PathVariable String id, @Valid @RequestBody MailAccountRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        service.delete(id);
    }

    /** Stößt den Abruf im Hintergrund an; das Ergebnis steht danach am Postfach. */
    @PostMapping("/{id}/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void sync(@PathVariable String id) {
        service.require(id);
        if (syncService.isRunning(id)) {
            throw ApiException.conflict("Dieses Postfach wird gerade abgerufen.");
        }
        service.markRunning(id);
        executor.execute(() -> syncService.sync(id));
    }

    @PostMapping("/test")
    public ConnectionTestDto test(@Valid @RequestBody ConnectionTestRequest request) {
        MailAccountRequest data = request.account();
        MailAccount probe = new MailAccount("test");
        probe.setHost(data.host().strip());
        probe.setPort(data.port());
        probe.setSecurity(data.security());
        probe.setUsername(data.username().strip());
        probe.setFolder(data.folder().strip());
        String password = data.password();
        if ((password == null || password.isBlank()) && request.accountId() != null) {
            password = service.password(service.require(request.accountId()));
        }
        if (password == null || password.isBlank()) {
            return new ConnectionTestDto(false, "Bitte ein Passwort angeben.");
        }
        return syncService.test(probe, password);
    }
}
