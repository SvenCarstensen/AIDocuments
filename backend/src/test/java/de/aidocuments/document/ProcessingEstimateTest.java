package de.aidocuments.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProcessingEstimateTest {

    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");

    @Test
    void multipliesTheAverageIntervalByTheNumberOfWaitingDocuments() {
        // fertig geworden im Abstand von 40 s und 60 s → im Mittel 50 s je Dokument
        List<Instant> finished = List.of(NOW, NOW.minusSeconds(40), NOW.minusSeconds(100));

        assertThat(ProcessingEstimate.secondsRemaining(960, finished)).isEqualTo(48_000L);
        assertThat(ProcessingEstimate.secondsRemaining(1, finished)).isEqualTo(50L);
    }

    @Test
    void ignoresPausesBetweenRuns() {
        // dazwischen lag eine Nacht – die zählt nicht als Bearbeitungszeit
        List<Instant> finished = List.of(NOW, NOW.minusSeconds(30), NOW.minusSeconds(60),
                NOW.minusSeconds(60 + 9 * 3600), NOW.minusSeconds(90 + 9 * 3600));

        assertThat(ProcessingEstimate.secondsRemaining(10, finished)).isEqualTo(300L);
    }

    @Test
    void givesNoEstimateWithoutEnoughEvidence() {
        assertThat(ProcessingEstimate.secondsRemaining(0, List.of(NOW, NOW.minusSeconds(30), NOW.minusSeconds(60))))
                .isNull();
        assertThat(ProcessingEstimate.secondsRemaining(5, List.of())).isNull();
        assertThat(ProcessingEstimate.secondsRemaining(5, List.of(NOW, NOW.minusSeconds(30)))).isNull();
        assertThat(ProcessingEstimate.secondsRemaining(5, List.of(NOW, NOW.minusSeconds(7200), NOW.minusSeconds(14400))))
                .isNull();
    }
}
