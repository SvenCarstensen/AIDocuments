package de.aidocuments.document;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface DocumentRepository extends MongoRepository<Document, String> {

    Optional<Document> findBySha256(String sha256);

    List<Document> findByStatusIn(Collection<DocumentStatus> statuses);

    long countByStatusIn(Collection<DocumentStatus> statuses);

    long countByMailAccountId(String mailAccountId);

    /** Die zuletzt fertig bearbeiteten Dokumente, neueste zuerst – Grundlage der Restzeit-Schätzung. */
    List<Document> findTop25ByProcessedAtNotNullOrderByProcessedAtDesc();
}
