package de.aidocuments.i18n;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * Macht aus sprachneutralen Texten ({@link Msg}) Sätze für den Anwender. Die Texte stehen in
 * {@code messages.properties} (Englisch, zugleich Rückfallsprache) und {@code messages_xx.properties}.
 *
 * <p>Ohne ausdrückliche Sprache gilt die der laufenden Anfrage ({@code Accept-Language}, siehe
 * {@link AcceptLanguageLocaleResolver}); außerhalb einer Anfrage Englisch – nie die Sprache des Servers.
 */
@Component
public class Messages {

    public static final Locale DEFAULT_LOCALE = Locale.ENGLISH;
    /** Die Protokollzeilen der Anwendung sind deutsch; Meldungen, die dort zitiert werden, sind es auch. */
    private static final Locale LOG_LOCALE = Locale.GERMAN;

    private final MessageSource source;

    public Messages(MessageSource source) {
        this.source = source;
    }

    /** Die Sprache dessen, der gerade fragt. */
    public Locale locale() {
        LocaleContext context = LocaleContextHolder.getLocaleContext();
        Locale locale = context == null ? null : context.getLocale();
        return locale != null ? locale : DEFAULT_LOCALE;
    }

    public String get(String key, Object... args) {
        return render(Msg.of(key, args), locale());
    }

    public String render(Msg msg) {
        return render(msg, locale());
    }

    public String render(Msg msg, Locale locale) {
        if (msg.isLiteral()) {
            return String.valueOf(msg.args().getFirst());
        }
        // Ohne Argumente liefert die MessageSource das Muster roh; formatiert wird hier – immer, damit für
        // alle Texte dieselbe Regel gilt (Apostroph verdoppeln), ob sie Platzhalter haben oder nicht.
        String pattern = source.getMessage(msg.key(), null, null, locale);
        if (pattern == null) {
            return msg.key(); // unbekannter Schlüssel, z. B. von einer neueren Version gespeichert
        }
        List<Object> args = new ArrayList<>(msg.args().size());
        for (Object arg : msg.args()) {
            args.add(switch (arg) {
                case Msg nested -> render(nested, locale);
                case List<?> nested -> join(nested, locale);
                // MessageFormat kennt nur java.util.Date – Mitternacht in der Zeitzone, mit der es formatiert
                case java.time.LocalDate date -> java.util.Date.from(date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
                default -> arg;
            });
        }
        try {
            return new MessageFormat(pattern, locale).format(args.toArray());
        } catch (IllegalArgumentException e) {
            return pattern; // fehlerhaftes Muster in einer Übersetzung: lieber roh als gar nicht
        }
    }

    /** Mehrere Sätze hintereinander. */
    public String render(List<Msg> sentences, Locale locale) {
        return join(sentences, locale);
    }

    /**
     * Übersetzt einen gespeicherten Text (siehe {@link MsgCodec}) für den, der gerade fragt. Klartext –
     * etwa aus der Zeit, bevor die Anwendung mehrsprachig wurde – kommt unverändert zurück.
     */
    public String localize(String stored) {
        return localize(stored, locale());
    }

    public String localize(String stored, Locale locale) {
        List<Msg> sentences = MsgCodec.decode(stored);
        return sentences == null ? stored : join(sentences, locale);
    }

    /** Für Protokollzeilen: dieselbe Meldung in der Sprache des Protokolls. */
    public String forLog(Msg msg) {
        return render(msg, LOG_LOCALE);
    }

    private String join(List<?> sentences, Locale locale) {
        return sentences.stream().map(sentence -> render((Msg) sentence, locale)).collect(Collectors.joining(" "));
    }
}
