package de.aidocuments.document;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Schätzt, wie lange die Warteschlange noch braucht: aus den Abständen, in denen die zuletzt
 * bearbeiteten Dokumente fertig geworden sind. Da die Dokumente nacheinander verarbeitet werden,
 * ist der mittlere Abstand zugleich die Zeit je Dokument.
 */
public final class ProcessingEstimate {

    /** Längere Lücken sind Pausen (Neustart, leere Warteschlange, Server nicht erreichbar), keine Bearbeitungszeit. */
    static final Duration LONGEST_PLAUSIBLE_GAP = Duration.ofMinutes(20);
    /** Mit weniger Messwerten wäre die Schätzung Zufall. */
    static final int MIN_INTERVALS = 2;

    private ProcessingEstimate() {
    }

    /**
     * @param waiting          Dokumente, die noch warten oder gerade bearbeitet werden
     * @param finishedNewestFirst Fertigstellungszeiten der zuletzt bearbeiteten Dokumente, neueste zuerst
     * @return geschätzte Restdauer in Sekunden oder {@code null}, solange es keine belastbare Grundlage gibt
     */
    public static Long secondsRemaining(long waiting, List<Instant> finishedNewestFirst) {
        if (waiting <= 0) {
            return null;
        }
        long totalMillis = 0;
        int intervals = 0;
        for (int i = 0; i + 1 < finishedNewestFirst.size(); i++) {
            Duration gap = Duration.between(finishedNewestFirst.get(i + 1), finishedNewestFirst.get(i));
            if (!gap.isNegative() && gap.compareTo(LONGEST_PLAUSIBLE_GAP) <= 0) {
                totalMillis += gap.toMillis();
                intervals++;
            }
        }
        if (intervals < MIN_INTERVALS) {
            return null;
        }
        double millisPerDocument = (double) totalMillis / intervals;
        return Math.max(1, Math.round(waiting * millisPerDocument / 1000.0));
    }
}
