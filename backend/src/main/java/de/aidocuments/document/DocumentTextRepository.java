package de.aidocuments.document;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface DocumentTextRepository extends MongoRepository<DocumentText, String> {
}
