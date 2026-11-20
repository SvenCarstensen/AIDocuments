package de.aidocuments.document;

import org.springframework.data.annotation.Id;

/** Volltext eines Dokuments – eigene Collection, damit Listenabfragen ihn nicht mitladen. */
@org.springframework.data.mongodb.core.mapping.Document(collection = "document_texts")
public record DocumentText(@Id String documentId, String content) {
}
