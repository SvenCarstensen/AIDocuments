package de.aidocuments.document;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fingerabdruck des Inhalts eines Dokuments – um zu erkennen, ob zwei Dokumente dasselbe Schreiben sind, auch wenn
 * die Dateien verschieden sind (zweimal gescannt, einmal per Mail und einmal vom Scanner, Texterkennung mit kleinen
 * Abweichungen).
 *
 * <p>Ähnlicher Text allein genügt nicht: Serienbriefe – monatliche Rechnungen, Kontoauszüge, Beitragsbescheide –
 * gleichen sich bis auf wenige Stellen. Diese Stellen sind fast immer Datumsangaben und Beträge. Deshalb gilt als
 * dasselbe Dokument nur, was im Text weitgehend übereinstimmt <em>und</em> dieselben Daten und Beträge nennt.
 *
 * <p>Die Textähnlichkeit wird über MinHash geschätzt: 128 Werte je Dokument statt des ganzen Textes, der Vergleich
 * bleibt damit auch bei vielen tausend Dokumenten schnell.
 *
 * @param minHash  MinHash-Signatur der Folgen von je drei Wörtern
 * @param shingles Anzahl verschiedener Wortfolgen – sehr unterschiedlich lange Texte sind nie dasselbe Dokument
 * @param dates    alle Datumsangaben, einheitlich als TT.MM.JJJJ
 * @param amounts  alle Beträge mit Nachkommastellen, z. B. 1.074,29
 */
public record ContentFingerprint(int[] minHash, int shingles, List<String> dates, List<String> amounts) {

    private static final int HASHES = 128;
    /** Unter so vielen Wörtern ist ein Text zu kurz für einen verlässlichen Vergleich (etwa ein leerer Scan). */
    private static final int MIN_WORDS = 30;
    private static final Pattern WORD = Pattern.compile("[\\p{L}]{2,}");
    private static final Pattern DATE = Pattern.compile("\\b(\\d{1,2})\\.\\s?(\\d{1,2})\\.\\s?(\\d{2}|\\d{4})\\b");
    private static final Pattern AMOUNT = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{3})*,\\d{2}\\b");
    private static final long[] SEEDS = new long[HASHES];

    static {
        long seed = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < HASHES; i++) {
            seed = mix(seed + i);
            SEEDS[i] = seed;
        }
    }

    /** @return {@code null}, wenn der Text zu kurz für einen Vergleich ist */
    public static ContentFingerprint of(String text) {
        if (text == null) {
            return null;
        }
        List<String> words = WORD.matcher(text.toLowerCase(Locale.ROOT)).results().map(java.util.regex.MatchResult::group).toList();
        if (words.size() < MIN_WORDS) {
            return null;
        }
        Set<Long> shingles = new HashSet<>();
        for (int i = 0; i + 3 <= words.size(); i++) {
            shingles.add(hash(words.get(i) + ' ' + words.get(i + 1) + ' ' + words.get(i + 2)));
        }
        int[] minHash = new int[HASHES];
        Arrays.fill(minHash, Integer.MAX_VALUE);
        for (long shingle : shingles) {
            for (int i = 0; i < HASHES; i++) {
                int value = (int) (mix(shingle ^ SEEDS[i]) >>> 33);
                if (value < minHash[i]) {
                    minHash[i] = value;
                }
            }
        }
        Set<String> dates = new TreeSet<>();
        Matcher date = DATE.matcher(text);
        while (date.find()) {
            String year = date.group(3).length() == 2 ? "20" + date.group(3) : date.group(3);
            dates.add("%02d.%02d.%s".formatted(Integer.parseInt(date.group(1)), Integer.parseInt(date.group(2)), year));
        }
        Set<String> amounts = new TreeSet<>(AMOUNT.matcher(text).results().map(java.util.regex.MatchResult::group).toList());
        return new ContentFingerprint(minHash, shingles.size(), List.copyOf(dates), List.copyOf(amounts));
    }

    /** Geschätzter Anteil gemeinsamer Wortfolgen (Jaccard), 0 bis 1. */
    public double textSimilarity(ContentFingerprint other) {
        int same = 0;
        for (int i = 0; i < HASHES; i++) {
            if (minHash[i] == other.minHash[i]) {
                same++;
            }
        }
        return (double) same / HASHES;
    }

    /** Ob beide Fingerabdrücke zum selben Schreiben gehören (siehe Klassenbeschreibung). */
    public boolean sameDocumentAs(ContentFingerprint other) {
        if ((double) Math.min(shingles, other.shingles) / Math.max(shingles, other.shingles) < 0.5) {
            return false;
        }
        double text = textSimilarity(other);
        boolean datesKnown = !dates.isEmpty() || !other.dates.isEmpty();
        // Ohne Datum fehlt das wichtigste Unterscheidungsmerkmal von Serienbriefen – dann muss der Text fast gleich sein.
        if (text < (datesKnown ? 0.75 : 0.92)) {
            return false;
        }
        if (datesKnown && jaccard(dates, other.dates) < 0.9) {
            return false;
        }
        return (amounts.isEmpty() && other.amounts.isEmpty()) || jaccard(amounts, other.amounts) >= 0.9;
    }

    private static double jaccard(List<String> a, List<String> b) {
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        if (union.isEmpty()) {
            return 1;
        }
        Set<String> common = new HashSet<>(a);
        common.retainAll(new HashSet<>(b));
        return (double) common.size() / union.size();
    }

    private static long hash(String value) {
        long h = 0xcbf29ce484222325L; // FNV-1a, 64 Bit
        for (int i = 0; i < value.length(); i++) {
            h ^= value.charAt(i);
            h *= 0x100000001b3L;
        }
        return mix(h);
    }

    /** SplitMix64 – verteilt die Bits gleichmäßig, damit die 128 Hashfunktionen unabhängig wirken. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
