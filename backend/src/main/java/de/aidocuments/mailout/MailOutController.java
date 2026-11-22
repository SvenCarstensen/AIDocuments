package de.aidocuments.mailout;

import de.aidocuments.i18n.Messages;
import de.aidocuments.i18n.Msg;
import de.aidocuments.mailout.MailOutService.MailOutDto;
import de.aidocuments.mailout.MailOutService.MailOutRequest;
import de.aidocuments.user.AppUserRepository;
import de.aidocuments.user.UserPrincipal;
import de.aidocuments.web.ApiException;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** E-Mail-Versand einrichten – nur für Administratoren (unter /api/settings, siehe SecurityConfig). */
@RestController
@RequestMapping("/api/settings/mail-out")
public class MailOutController {

    public record TestResultDto(boolean ok, String message) {
    }

    private final MailOutService service;
    private final AppUserRepository users;
    private final Messages messages;

    public MailOutController(MailOutService service, AppUserRepository users, Messages messages) {
        this.service = service;
        this.users = users;
        this.messages = messages;
    }

    @GetMapping
    public MailOutDto get() {
        return service.dto();
    }

    @PutMapping
    public MailOutDto update(@Valid @RequestBody MailOutRequest request) {
        return service.update(request);
    }

    /** Schickt eine Testnachricht an die eigene Adresse (aus dem Benutzermenü). */
    @PostMapping("/test")
    public TestResultDto test(@AuthenticationPrincipal UserPrincipal principal) {
        String to = users.findById(principal.id()).map(u -> u.getEmail()).orElse(null);
        if (to == null || to.isBlank()) {
            throw ApiException.badRequest("error.mailOut.noOwnAddress");
        }
        Msg result = service.test(to, new MailOutService.Mail(to, messages.get("mailOut.test.subject"),
                messages.get("mailOut.test.text")));
        return new TestResultDto("mailOut.test.ok".equals(result.key()), messages.render(result));
    }
}
