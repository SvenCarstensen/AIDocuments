package de.aidocuments.mail;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import de.aidocuments.document.Document;
import de.aidocuments.document.DocumentRepository;
import de.aidocuments.mail.MailDtos.MailAccountDto;
import de.aidocuments.mail.MailDtos.MailAccountRequest;
import de.aidocuments.security.SecretCipher;
import de.aidocuments.web.ApiException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
public class MailAccountService {

    private final MailAccountRepository accounts;
    private final DocumentRepository documents;
    private final SecretCipher cipher;
    private final MongoTemplate mongo;

    public MailAccountService(MailAccountRepository accounts, DocumentRepository documents, SecretCipher cipher,
                              MongoTemplate mongo) {
        this.accounts = accounts;
        this.documents = documents;
        this.cipher = cipher;
        this.mongo = mongo;
    }

    public List<MailAccountDto> list() {
        return accounts.findAll().stream()
                .sorted(Comparator.comparing(MailAccount::getCreatedAt))
                .map(this::toDto)
                .toList();
    }

    public MailAccount require(String id) {
        return accounts.findById(id).orElseThrow(() -> ApiException.notFound("Das Postfach"));
    }

    public MailAccountDto create(MailAccountRequest request) {
        if (request.password() == null || request.password().isBlank()) {
            throw ApiException.badRequest("Bitte ein Passwort angeben.");
        }
        MailAccount account = new MailAccount(UUID.randomUUID().toString());
        apply(account, request);
        return toDto(accounts.save(account));
    }

    public MailAccountDto update(String id, MailAccountRequest request) {
        MailAccount account = require(id);
        boolean mailboxChanged = !Objects.equals(account.getHost(), request.host().strip())
                || !Objects.equals(account.getUsername(), request.username().strip())
                || !Objects.equals(account.getFolder(), request.folder().strip())
                || !Objects.equals(account.getImportSince(), request.importSince());
        apply(account, request);
        if (mailboxChanged) {
            // anderes Postfach oder anderer Zeitraum: von vorn prüfen (Duplikate werden ohnehin erkannt)
            account.resetCursor();
        }
        return toDto(accounts.save(account));
    }

    public void delete(String id) {
        MailAccount account = require(id);
        accounts.delete(account);
        // importierte Dokumente bleiben erhalten, verlieren aber den Verweis auf das Postfach
        mongo.updateMulti(query(where("mailAccountId").is(id)), new Update().unset("mailAccountId"), Document.class);
    }

    public String password(MailAccount account) {
        return cipher.decrypt(account.getPasswordEncrypted());
    }

    // --- Abrufstand: gezielte Feld-Updates, damit ein parallel gespeichertes Formular nichts überschreibt ---

    void markRunning(String id) {
        mongo.updateFirst(query(where("_id").is(id)),
                new Update().set("lastSyncStatus", MailAccount.SyncStatus.RUNNING), MailAccount.class);
    }

    void saveCursor(String id, long uidValidity, long lastUid) {
        mongo.updateFirst(query(where("_id").is(id)),
                new Update().set("uidValidity", uidValidity).set("lastUid", lastUid), MailAccount.class);
    }

    void markFinished(String id, MailAccount.SyncStatus status, String message) {
        mongo.updateFirst(query(where("_id").is(id)), new Update()
                .set("lastSyncStatus", status)
                .set("lastSyncMessage", message)
                .set("lastSyncAt", Instant.now()), MailAccount.class);
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
        if (request.password() != null && !request.password().isBlank()) {
            account.setPasswordEncrypted(cipher.encrypt(request.password()));
        }
    }

    private MailAccountDto toDto(MailAccount a) {
        return new MailAccountDto(a.getId(), a.getName(), a.getHost(), a.getPort(), a.getSecurity(),
                a.getUsername(), a.getFolder(), a.getImportSince(), a.isEnabled(), a.getLastSyncAt(),
                a.getLastSyncStatus(), a.getLastSyncMessage(), documents.countByMailAccountId(a.getId()));
    }
}
