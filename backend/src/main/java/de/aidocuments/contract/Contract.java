package de.aidocuments.contract;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Vertragsangaben zu einem Dokument (Tabelle {@code contracts}).
 *
 * @param endDate       Ende der laufenden Laufzeit; {@code null} = unbefristet
 * @param noticeMonths  Kündigungsfrist in Monaten vor dem Laufzeitende; {@code null} = bis zum Laufzeitende
 * @param renewalMonths verlängert sich ohne Kündigung um so viele Monate; {@code null} = endet
 * @param reminderDays  so viele Tage vor dem Stichtag erinnert eine Wiedervorlage
 * @param reminderId    die zuletzt dafür angelegte Wiedervorlage
 */
public record Contract(String documentId, String partner, LocalDate startDate, LocalDate endDate,
                       Integer noticeMonths, Integer renewalMonths, String amount, int reminderDays,
                       boolean cancelled, String reminderId, String assigneeId, String createdBy, Instant createdAt,
                       Instant updatedAt) {

    public static final int DEFAULT_REMINDER_DAYS = 14;

    /** Bis wann gekündigt sein muss; {@code null}, wenn der Vertrag sich nicht verlängert oder unbefristet ist. */
    public LocalDate noticeDeadline() {
        if (endDate == null || renewalMonths == null) {
            return null;
        }
        return noticeMonths == null ? endDate : endDate.minusMonths(noticeMonths);
    }

    /** Der Tag, an dem etwas zu tun ist: das Ende der Kündigungsfrist – oder, wenn er ohnehin endet, das Laufzeitende. */
    public LocalDate keyDate() {
        LocalDate deadline = noticeDeadline();
        return deadline != null ? deadline : endDate;
    }

    public Contract withEndDate(LocalDate end) {
        return new Contract(documentId, partner, startDate, end, noticeMonths, renewalMonths, amount, reminderDays,
                cancelled, reminderId, assigneeId, createdBy, createdAt, Instant.now());
    }

    public Contract withReminder(String id) {
        return new Contract(documentId, partner, startDate, endDate, noticeMonths, renewalMonths, amount, reminderDays,
                cancelled, id, assigneeId, createdBy, createdAt, updatedAt);
    }
}
