package de.aidocuments.retention;

import java.time.Instant;

/** Dokumente der Kategorie {@code category} (Groß-/Kleinschreibung egal) werden {@code years} Jahre aufbewahrt. */
public record RetentionRule(String id, String category, int years, Instant createdAt) {

    public static final int MIN_YEARS = 1;
    public static final int MAX_YEARS = 100;
}
