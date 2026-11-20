package de.aidocuments.mail;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface MailAccountRepository extends MongoRepository<MailAccount, String> {

    List<MailAccount> findByEnabledTrue();
}
