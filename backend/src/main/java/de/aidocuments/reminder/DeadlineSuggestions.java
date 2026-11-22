package de.aidocuments.reminder;

import de.aidocuments.document.KeyFact;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Terminvorschläge aus den wichtigen Angaben, die die KI aus einem Dokument zieht: eine Angabe, deren Bezeichnung
 * nach Frist oder Fälligkeit klingt („Zahlbar bis“, „Kündigungsfrist“, „Fällig am“ …) und deren Wert ein Datum
 * enthält. So stehen Vorschläge auch für Dokumente bereit, die vor dieser Funktion analysiert wurden.
 */
public final class DeadlineSuggestions {

    /** Ein Vorschlag: Datum und die Bezeichnung der Angabe, aus der er stammt. */
    public record Suggestion(LocalDate date, String label) {
    }

    /** Bezeichnungen, die auf einen Termin hindeuten – Deutsch und Englisch, wie die KI sie meist schreibt. */
    private static final Pattern DEADLINE_LABEL = Pattern.compile(
            "f(ä|ae)llig|zahlbar|zahlungsziel|zahlungsfrist|frist|termin|spätestens|spaetestens|einzureichen|"
                    + "ablauf|läuft|laeuft|gültig bis|gueltig bis|kündig|kuendig|widerspruch|einspruch|abgabe|"
                    + "vorlage|due|deadline|payable|expir|valid until|renewal|cancel|appointment",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})\\b");
    private static final Pattern DOTTED_DATE = Pattern.compile("\\b(\\d{1,2})\\.\\s?(\\d{1,2})\\.\\s?(\\d{4})\\b");
    private static final Pattern SLASHED_DATE = Pattern.compile("\\b(\\d{1,2})/(\\d{1,2})/(\\d{4})\\b");

    private DeadlineSuggestions() {
    }

    /**
     * @param from nur Termine ab diesem Tag (vergangene Fristen schlagen wir nicht mehr vor)
     */
    public static List<Suggestion> from(List<KeyFact> facts, LocalDate from) {
        List<Suggestion> result = new ArrayList<>();
        if (facts == null) {
            return result;
        }
        for (KeyFact fact : facts) {
            if (fact == null || fact.label() == null || fact.value() == null
                    || !DEADLINE_LABEL.matcher(fact.label().toLowerCase(Locale.ROOT)).find()) {
                continue;
            }
            LocalDate date = firstDate(fact.value());
            if (date != null && !date.isBefore(from)
                    && result.stream().noneMatch(s -> s.date().equals(date))) {
                result.add(new Suggestion(date, fact.label().strip()));
            }
        }
        return result;
    }

    /** Das erste Datum im Text: JJJJ-MM-TT, TT.MM.JJJJ oder (europäisch gelesen) TT/MM/JJJJ. */
    static LocalDate firstDate(String text) {
        LocalDate best = null;
        int bestAt = Integer.MAX_VALUE;
        for (Pattern pattern : List.of(ISO_DATE, DOTTED_DATE, SLASHED_DATE)) {
            Matcher m = pattern.matcher(text);
            if (m.find() && m.start() < bestAt) {
                LocalDate date = pattern == ISO_DATE
                        ? date(m.group(1), m.group(2), m.group(3))
                        : date(m.group(3), m.group(2), m.group(1));
                if (date != null) {
                    best = date;
                    bestAt = m.start();
                }
            }
        }
        return best;
    }

    private static LocalDate date(String year, String month, String day) {
        try {
            return LocalDate.of(Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day));
        } catch (DateTimeException | NumberFormatException e) {
            return null;
        }
    }
}
