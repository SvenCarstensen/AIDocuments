package de.aidocuments.importer;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Merkt sich, welche Datei des Eingangsordners in welchem Stand (Größe, Änderungszeit) schon geprüft wurde.
 * Die ID ist der absolute Pfad.
 */
@Document(collection = "imported_files")
public record ImportedFile(@Id String path, long size, long modifiedAt, String outcome, Instant checkedAt) {
}
