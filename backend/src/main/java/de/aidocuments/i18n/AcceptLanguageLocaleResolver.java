package de.aidocuments.i18n;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Bestimmt die Sprache einer Antwort aus dem {@code Accept-Language}-Header: die bevorzugte Sprache, für die
 * es eine {@code messages_xx.properties} gibt, sonst Englisch. Ein fehlender oder unverständlicher Header
 * ergibt ebenfalls Englisch – die Sprache des Servers spielt nie eine Rolle.
 *
 * <p>Welche Sprachen es gibt, ergibt sich allein aus den vorhandenen Dateien: Eine neue Übersetzung wird
 * durch das Ablegen ihrer Datei wirksam.
 */
public class AcceptLanguageLocaleResolver implements LocaleResolver {

    private final List<Locale> supported;

    public AcceptLanguageLocaleResolver(List<Locale> supported) {
        this.supported = List.copyOf(supported);
    }

    /** Englisch (die Texte ohne Sprachkürzel) und jede Sprache, für die eine Übersetzungsdatei vorliegt. */
    public static AcceptLanguageLocaleResolver forBundles(String basename, ClassLoader classLoader) {
        List<Locale> found = new ArrayList<>();
        found.add(Messages.DEFAULT_LOCALE);
        // Gezielt nachsehen statt den Klassenpfad nach einem Muster abzusuchen – das funktioniert in jeder
        // Verpackung (Verzeichnis, JAR, ausführbares JAR) gleich.
        Arrays.stream(Locale.getAvailableLocales())
                .filter(locale -> !locale.getLanguage().isEmpty() && !found.contains(locale))
                .filter(locale -> classLoader.getResource(basename + "_" + locale + ".properties") != null)
                .sorted(Comparator.comparing(Locale::toLanguageTag))
                .forEach(found::add);
        return new AcceptLanguageLocaleResolver(found);
    }

    public List<Locale> supportedLocales() {
        return supported;
    }

    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        return bestMatch(request.getHeader(HttpHeaders.ACCEPT_LANGUAGE));
    }

    Locale bestMatch(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return Messages.DEFAULT_LOCALE;
        }
        try {
            // "de-AT" passt auf "de"; unter mehreren Angaben gewinnt die mit dem höchsten Gewicht, die es gibt
            Locale match = Locale.lookup(Locale.LanguageRange.parse(acceptLanguage), supported);
            return match != null ? match : Messages.DEFAULT_LOCALE;
        } catch (IllegalArgumentException e) {
            return Messages.DEFAULT_LOCALE;
        }
    }

    @Override
    public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
        throw new UnsupportedOperationException("Die Sprache bestimmt allein der Accept-Language-Header.");
    }
}
