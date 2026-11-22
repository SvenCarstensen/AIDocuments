package de.aidocuments.mail;

import de.aidocuments.document.DocumentRepository;
import de.aidocuments.i18n.Messages;
import de.aidocuments.i18n.Msg;
import de.aidocuments.i18n.MsgCodec;
import de.aidocuments.mail.MailDtos.MailAccountDto;
import de.aidocuments.mail.MailDtos.MailAccountRequest;
import de.aidocuments.security.SecretCipher;
import de.aidocuments.web.ApiException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class MailAccountService {

    private final MailAccountRepository accounts;
    private final DocumentRepository documents;
    private final SecretCipher cipher;
    private final Messages messages;
    private final MailSchedule schedule;

    public MailAccountService(MailAccountRepository accounts, DocumentRepository documents, SecretCipher cipher,
                              Messages messages, MailSchedule schedule) {
        this.messages = messages;
        this.schedule = schedule;
        this.accounts = accounts;
        this.documents = documents;
        this.cipher = cipher;
    }

    public List<MailAccountDto> list() {
        return accounts.findAll().stream()
                .sorted(Comparator.comparing(MailAccount::getCreatedAt))
                .map(this::toDto)
                .toList();
    }

    public MailAccount require(String id) {
        return accounts.findById(id).orElseThrow(() -> ApiException.notFound("error.mailbox.notFound"));
    }

    public MailAccountDto create(MailAccountRequest request) {
        if (request.password() == null || request.password().isBlank()) {
            throw ApiException.badRequest("error.mailbox.passwordRequired");
        }
        MailAccount account = new MailAccount(UUID.randomUUID().toString());
        apply(account, request);
        return toDto(accounts.save(account));
    }

    public MailAccountDto update(String id, MailAccountRequest request) {
        MailAccount account = require(id);
        boolean newPassword = request.password() != null && !request.password().isBlank();
        if (!newPassword && !mayReusePassword(account, request.host(), request.security())) {
            throw ApiException.badRequest("error.mailbox.passwordForNewServer");
        }
        boolean mailboxChanged = !Objects.equals(account.getHost(), request.host().strip())
                || !Objects.equals(account.getUsername(), request.username().strip())
                || !Objects.equals(account.getFolder(), request.folder().strip())
                || !Objects.equals(account.getImportSince(), request.importSince());
        apply(account, request);
        if (mailboxChanged) {
            // anderes Postfach oder bewusst anderer Zeitraum: von vorn prüfen (Duplikate werden ohnehin erkannt).
            // Ein geänderter Filter gilt dagegen nur für E-Mails, die nach dem letzten Abruf eingehen.
            account.resetCursor();
        }
        return toDto(accounts.save(account));
    }

    public void delete(String id) {
        MailAccount account = require(id);
        accounts.delete(account);
        schedule.forget(id);
        // importierte Dokumente bleiben erhalten, verlieren aber den Verweis auf das Postfach
        documents.clearMailAccount(id);
    }

    public String password(MailAccount account) {
        return cipher.decrypt(account.getPasswordEncrypted());
    }

    /**
     * Ob das gespeicherte Passwort für diese Verbindung verwendet werden darf: nur für denselben Server und nicht
     * unverschlüsselt, wenn es bisher verschlüsselt ging. Sonst ließe sich das Passwort mit einer geänderten Adresse
     * an einen fremden Server schicken und dort mitlesen.
     */
    public boolean mayReusePassword(MailAccount account, String host, MailSecurity security) {
        boolean sameHost = account.getHost() != null && host != null
                && account.getHost().strip().equalsIgnoreCase(host.strip());
        boolean downgrade = security == MailSecurity.NONE && account.getSecurity() != MailSecurity.NONE;
        return sameHost && !downgrade;
    }

    // --- Abrufstand: gezielte Feld-Updates, damit ein parallel gespeichertes Formular nichts überschreibt ---

    void markRunning(String id) {
        accounts.markRunning(id);
    }

    void saveCursor(String id, long uidValidity, long lastUid) {
        accounts.saveCursor(id, uidValidity, lastUid);
    }

    void saveCheckedUntil(String id, Instant startedAt) {
        accounts.saveCheckedUntil(id, startedAt);
    }

    /**
     * @param result das Ergebnis als sprachneutrale Sätze – der Abruf läuft im Hintergrund, ohne Leser;
     *               übersetzt wird beim Ausliefern (siehe {@link #toDto})
     */
    void markFinished(String id, MailAccount.SyncStatus status, List<Msg> result) {
        accounts.markFinished(id, status, MsgCodec.encode(result), Instant.now());
    }

    private void apply(MailAccount account, MailAccountRequest request) {
        account.setName(request.name().strip());
        account.setHost(request.host().strip());
        account.setPort(request.port());
        account.setSecurity(request.security());
        account.setUsername(request.username().strip());
        account.setFolder(request.folder().strip());
        account.setImportSince(request.importSince());
        account.setEnabled(request.enabled());
        account.setFilter(filter(request));
        account.setIntervalMinutes(request.intervalMinutes());
        if (request.password() != null && !request.password().isBlank()) {
            account.setPasswordEncrypted(cipher.encrypt(request.password()));
        }
    }

    private static MailFilter filter(MailAccountRequest request) {
        return request.filter() == null ? MailFilter.NONE : request.filter().toFilter();
    }

    private MailAccountDto toDto(MailAccount a) {
        return new MailAccountDto(a.getId(), a.getName(), a.getHost(), a.getPort(), a.getSecurity(),
                a.getUsername(), a.getFolder(), a.getImportSince(), a.isEnabled(), MailDtos.MailFilterDto.of(a.getFilter()),
                a.getIntervalMinutes(), schedule.intervalMinutes(a), schedule.nextSyncAt(a), a.getLastSyncAt(),
                a.getLastSyncStatus(), messages.localize(a.getLastSyncMessage()),
                documents.countByMailAccountId(a.getId()));
    }
}
