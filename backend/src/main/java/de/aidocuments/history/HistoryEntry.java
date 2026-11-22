package de.aidocuments.history;

import de.aidocuments.document.DocumentSource;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Ein Eintrag der Historie: was mit einem Dokument passiert ist, wann und durch wen. Titel und Herkunft werden beim
 * Eintragen festgehalten – so bleibt der Eintrag lesbar, auch wenn das Dokument später umbenannt oder gelöscht wird.
 * Bearbeitungen merken sich zusätzlich den Stand davor, damit sie sich rückgängig machen lassen.
 *
 * @param source       nur bei Importen: woher das Dokument kam
 * @param origin       nur bei Importen: Postfach und Betreff, Dateiname o. Ä.
 * @param change       bei Bearbeitungen: was geändert wurde; bei Löschungen {@link Change#DUPLICATE}, wenn als Duplikat
 * @param user         wer es getan hat; null = automatisch (Postfach, Eingangsordner)
 * @param before       bei geänderten Angaben: die Angaben davor
 * @param previousFile bei geändertem PDF: die aufbewahrte Fassung davor; null = davor war es noch das Original
 * @param undoneAt     wann der Schritt rückgängig gemacht wurde; null = gilt noch
 */
public record HistoryEntry(
        String id,
        Instant at,
        Action action,
        String documentId,
        String documentTitle,
        DocumentSource source,
        String origin,
        Change change,
        String user,
        Details before,
        String previousFile,
        Instant undoneAt,
        String undoneBy,
        /* der Bereich des Dokuments – danach richtet sich, wer den Eintrag sieht */
        String areaId) {

    public enum Action {
        IMPORTED, EDITED, DELETED
    }

    public enum Change {
        /** Titel, Zusammenfassung, Dokumentart, Absender, Datum oder Schlagworte */
        DETAILS,
        /** das PDF selbst (Markierungen, Notizen, Unterschrift …) */
        FILE,
        /** das aufbewahrte Original wiederhergestellt */
        ORIGINAL_RESTORED,
        /** eine ältere Fassung des PDFs wiederhergestellt */
        VERSION_RESTORED,
        /** gelöscht als Duplikat eines anderen Dokuments */
        DUPLICATE
    }

    /** Die von Hand bearbeitbaren Angaben eines Dokuments. */
    /** @param areaId der Bereich vorher; fehlt bei Einträgen aus älteren Versionen */
    public record Details(String title, String summary, String category, String correspondent, LocalDate documentDate,
                          List<String> tags, String areaId) {
    }

    HistoryEntry undone(String by) {
        return new HistoryEntry(id, at, action, documentId, documentTitle, source, origin, change, user, before,
                previousFile, Instant.now(), by, areaId);
    }
}
