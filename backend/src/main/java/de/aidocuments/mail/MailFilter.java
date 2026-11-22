package de.aidocuments.mail;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Welche E-Mails eines Postfachs ausgewertet werden: Betreff und Absender müssen – sofern angegeben – einen der
 * gewünschten Begriffe enthalten und dürfen keinen der ausgeschlossenen enthalten. Verglichen wird ohne Rücksicht
 * auf Groß-/Kleinschreibung auf Teilen des Textes; beim Absender auf Name und Adresse („Kopierer 2. OG
 * &lt;kopierer@example.com&gt;“), sodass „@example.com“ ebenso passt wie „Kopierer“.
 *
 * <p>Alle Listen leer = keine Einschränkung.
 */
public record MailFilter(List<String> subjectIncludes, List<String> subjectExcludes,
                         List<String> senderIncludes, List<String> senderExcludes) {

    /** Höchstens so viele Begriffe je Liste und so viele Zeichen je Begriff. */
    public static final int MAX_TERMS = 50;
    public static final int MAX_TERM_LENGTH = 200;

    public static final MailFilter NONE = new MailFilter(List.of(), List.of(), List.of(), List.of());

    public MailFilter {
        subjectIncludes = clean(subjectIncludes);
        subjectExcludes = clean(subjectExcludes);
        senderIncludes = clean(senderIncludes);
        senderExcludes = clean(senderExcludes);
    }

    public boolean isEmpty() {
        return subjectIncludes.isEmpty() && subjectExcludes.isEmpty() && senderIncludes.isEmpty()
                && senderExcludes.isEmpty();
    }

    /**
     * @param subject der (bereits dekodierte) Betreff, {@code null} = keiner
     * @param senders die Absender der E-Mail als „Name &lt;Adresse&gt;“
     */
    public boolean matches(String subject, List<String> senders) {
        String normalizedSubject = normalize(subject == null ? "" : subject);
        List<String> normalizedSenders = senders.stream().map(MailFilter::normalize).toList();
        return (subjectIncludes.isEmpty() || containsAny(List.of(normalizedSubject), subjectIncludes))
                && !containsAny(List.of(normalizedSubject), subjectExcludes)
                && (senderIncludes.isEmpty() || containsAny(normalizedSenders, senderIncludes))
                && !containsAny(normalizedSenders, senderExcludes);
    }

    private static boolean containsAny(List<String> texts, List<String> terms) {
        for (String term : terms) {
            String needle = normalize(term);
            for (String text : texts) {
                if (text.contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Gleiche Schreibweisen gleich behandeln: Unicode-Normalform, Kleinbuchstaben, Leerraum zusammengefasst. */
    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    /** Leere Einträge und Dubletten fallen weg, Steuerzeichen werden zu Leerzeichen. */
    private static List<String> clean(List<String> terms) {
        if (terms == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> result = new ArrayList<>();
        for (String term : terms) {
            if (term == null) {
                continue;
            }
            String value = term.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
            if (!value.isEmpty() && seen.add(normalize(value))) {
                result.add(value);
            }
        }
        return List.copyOf(result);
    }

    // --- Ablage in der Datenbank: ein Begriff pro Zeile ---

    static String join(List<String> terms) {
        return terms.isEmpty() ? null : String.join("\n", terms);
    }

    static List<String> split(String stored) {
        return stored == null || stored.isBlank() ? List.of() : List.of(stored.split("\n"));
    }
}
