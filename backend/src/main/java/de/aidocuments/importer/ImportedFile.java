package de.aidocuments.importer;

import java.time.Instant;

/** Merkt sich, welche Datei des Eingangsordners in welchem Stand (Größe, Änderungszeit) schon geprüft wurde. */
public record ImportedFile(String path, long size, long modifiedAt, String outcome, Instant checkedAt) {
}
