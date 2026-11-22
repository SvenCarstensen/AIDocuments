package de.aidocuments.reminder;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Ein Termin an einem Dokument (Tabelle {@code reminders}).
 *
 * @param assigneeId wer sich kümmert und die Erinnerung per E-Mail bekommt; {@code null} = niemand Bestimmtes
 * @param notifiedAt   wann die E-Mail-Erinnerung verschickt wurde; {@code null} = noch nicht
 * @param repeatMonths ist er erledigt, kommt der nächste Termin so viele Monate später; {@code null} = einmalig
 */
public record Reminder(String id, String documentId, LocalDate dueDate, String note, String assigneeId,
                       Status status, Source source, String createdBy, Instant createdAt, Instant doneAt,
                       String doneBy, Instant notifiedAt, Integer repeatMonths) {

    /** Wie oft sich ein Termin höchstens wiederholen lässt: alle 1 bis 120 Monate. */
    public static final int MAX_REPEAT_MONTHS = 120;

    public enum Status {
        OPEN, DONE,
        /** ein verworfener Vorschlag – er wird nicht wieder angeboten */
        DISMISSED
    }

    public enum Source {
        USER,
        /** aus einem Vorschlag der KI übernommen (oder verworfen) */
        SUGGESTION,
        /** aus den Angaben eines Vertrags: vor Ablauf der Kündigungsfrist */
        CONTRACT
    }

    public static final int MAX_NOTE_LENGTH = 500;
}
