package de.aidocuments.i18n;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Legt sprachneutrale Texte in einem gewöhnlichen Textfeld ab – für alles, was im Hintergrund ohne Leser
 * entsteht (Hinweis am Dokument, Ergebnis eines Postfach-Abrufs, Stand des Eingangsordners) und erst beim
 * Ausliefern übersetzt wird.
 *
 * <p>Form: {@code i18n:} gefolgt von einer JSON-Liste von Sätzen, z. B.
 * <pre>i18n:[{"k":"mail.sync.checked","a":[2]},{"k":"mail.sync.imported","a":[1]}]</pre>
 * {@code k} ist der Schlüssel, {@code a} sind die Argumente (Text, ganze Zahl, ein Datum als {@code {"d":"JJJJ-MM-TT"}},
 * ein geschachtelter Satz als Objekt oder mehrere Sätze als Liste); {@code {"t":"…"}} ist wörtlicher Text. Alles, was nicht so aussieht –
 * insbesondere die deutschen Klartexte aus älteren Versionen –, gilt als fertiger Text.
 */
public final class MsgCodec {

    static final String PREFIX = "i18n:";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private MsgCodec() {
    }

    public static String encode(Msg... sentences) {
        return encode(List.of(sentences));
    }

    /** @return der Text fürs Datenbankfeld; {@code null}, wenn es nichts zu sagen gibt */
    public static String encode(List<Msg> sentences) {
        if (sentences == null || sentences.isEmpty()) {
            return null;
        }
        return PREFIX + JSON.writeValueAsString(sentences.stream().map(MsgCodec::toJson).toList());
    }

    /** @return die gespeicherten Sätze; {@code null}, wenn {@code stored} kein so abgelegter Text ist */
    public static List<Msg> decode(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return null;
        }
        try {
            return sentences(JSON.readValue(stored.substring(PREFIX.length()), Object.class));
        } catch (RuntimeException e) {
            return null; // kein gültiges JSON oder nicht die erwartete Form
        }
    }

    private static Map<String, Object> toJson(Msg msg) {
        Map<String, Object> json = new LinkedHashMap<>();
        if (msg.isLiteral()) {
            json.put("t", msg.args().getFirst());
            return json;
        }
        json.put("k", msg.key());
        if (!msg.args().isEmpty()) {
            json.put("a", msg.args().stream().map(MsgCodec::argToJson).toList());
        }
        return json;
    }

    private static Object argToJson(Object arg) {
        if (arg instanceof Msg msg) {
            return toJson(msg);
        }
        if (arg instanceof List<?> list) {
            return list.stream().map(part -> toJson((Msg) part)).toList();
        }
        if (arg instanceof java.time.LocalDate date) {
            // als eigenes Objekt, damit es wieder ein Datum wird – {0,date,long} formatiert keinen Text
            return Map.of("d", date.toString());
        }
        return arg; // String oder Long
    }

    private static List<Msg> sentences(Object json) {
        if (!(json instanceof List<?> list)) {
            throw new IllegalArgumentException("Liste erwartet");
        }
        List<Msg> result = new ArrayList<>(list.size());
        for (Object element : list) {
            result.add(sentence(element));
        }
        return result;
    }

    private static Msg sentence(Object json) {
        if (!(json instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Objekt erwartet");
        }
        if (map.get("t") instanceof String text) {
            return Msg.literal(text);
        }
        if (!(map.get("k") instanceof String key) || key.isBlank()) {
            throw new IllegalArgumentException("Schlüssel fehlt");
        }
        List<Object> args = new ArrayList<>();
        if (map.get("a") instanceof List<?> list) {
            for (Object arg : list) {
                args.add(switch (arg) {
                    case String text -> text;
                    case Integer number -> number.longValue();
                    case Long number -> number;
                    case Map<?, ?> date when date.get("d") instanceof String iso -> date(iso);
                    case Map<?, ?> nested -> sentence(nested);
                    case List<?> nested -> sentences(nested);
                    case null -> "";
                    default -> arg.toString();
                });
            }
        }
        return new Msg(key, args);
    }

    private static Object date(String iso) {
        try {
            return java.time.LocalDate.parse(iso);
        } catch (java.time.format.DateTimeParseException e) {
            return iso;
        }
    }
}
