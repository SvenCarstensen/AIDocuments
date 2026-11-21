package de.aidocuments.i18n;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Ein sprachneutraler Text: Schlüssel aus {@code messages.properties} plus Argumente. Erst {@link Messages}
 * macht daraus einen Satz – in der Sprache dessen, der ihn liest.
 *
 * <p>Argumente sind Text, ganze Zahlen, ein weiterer {@code Msg} (z. B. die Ursache in „Analyse
 * fehlgeschlagen: {0}“) oder eine Liste von {@code Msg} (mehrere Sätze hintereinander).
 *
 * @param key  Schlüssel; {@code null} bei wörtlichem Text, siehe {@link #literal(String)}
 * @param args Argumente für die Platzhalter {0}, {1}, …
 */
public record Msg(String key, List<Object> args) {

    /** Technische Details fremder Server können beliebig lang sein; gespeichert wird nur der Anfang. */
    static final int MAX_TEXT_LENGTH = 1000;

    public Msg {
        args = List.copyOf(args);
    }

    public static Msg of(String key, Object... args) {
        List<Object> normalized = new ArrayList<>(args.length);
        for (Object arg : args) {
            normalized.add(normalize(arg));
        }
        return new Msg(key, normalized);
    }

    /**
     * Text, der unübersetzt durchgereicht wird – für technische Details, die von außen kommen
     * (Fehlermeldung eines Mailservers, einer Bibliothek).
     */
    public static Msg literal(String text) {
        return new Msg(null, List.of(clip(text == null ? "" : text)));
    }

    /**
     * Das technische Detail zu einem unerwarteten Fehler: seine eigene übersetzbare Meldung, sonst der Text
     * der Ausnahme, wie er ist.
     */
    public static Msg detailOf(Throwable e) {
        if (e instanceof Localizable localizable) {
            return localizable.msg();
        }
        return literal(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
    }

    public boolean isLiteral() {
        return key == null;
    }

    private static Object normalize(Object arg) {
        if (arg instanceof Msg) {
            return arg;
        }
        if (arg instanceof Collection<?> parts) {
            return parts.stream().map(part -> part instanceof Msg msg ? msg : literal(String.valueOf(part))).toList();
        }
        if (arg instanceof Integer || arg instanceof Long || arg instanceof Short || arg instanceof Byte) {
            return ((Number) arg).longValue();
        }
        return clip(arg == null ? "" : arg.toString());
    }

    private static String clip(String text) {
        return text.length() <= MAX_TEXT_LENGTH ? text : text.substring(0, MAX_TEXT_LENGTH - 1) + "…";
    }
}
