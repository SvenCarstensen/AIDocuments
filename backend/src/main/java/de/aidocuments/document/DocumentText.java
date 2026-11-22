package de.aidocuments.document;

/** Volltext eines Dokuments – eigene Tabelle, damit Listenabfragen ihn nicht mitladen. */
public record DocumentText(String documentId, String content) {
}
