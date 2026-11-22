package de.aidocuments.area;

import java.time.Instant;

/**
 * Ein Bereich trennt Dokumente nach Zuständigkeit (etwa Kasse, Vorstand, Privat). Jedes Dokument liegt in genau einem
 * Bereich; wer ihn sieht, sieht seine Dokumente.
 *
 * @param name     {@code null} nur beim vorgegebenen Bereich {@link #GENERAL} – die Oberfläche übersetzt ihn dann
 * @param everyone       für alle Konten sichtbar, ohne Mitgliedschaft
 * @param everyoneAccess was alle in einem solchen Bereich dürfen (Mitglieder können mehr dürfen)
 */
public record Area(String id, String name, boolean everyone, Permission everyoneAccess, Instant createdAt) {

    /** Der vorgegebene Bereich; er lässt sich umbenennen, aber nicht löschen. */
    public static final String GENERAL = "general";

    public boolean isGeneral() {
        return GENERAL.equals(id);
    }
}
