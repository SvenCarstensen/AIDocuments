package de.aidocuments.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Die Sprache einer Antwort: was der Leser verlangt, soweit es dafür Texte gibt – sonst Englisch. */
class AcceptLanguageLocaleResolverTest {

    private final Locale systemLocale = Locale.getDefault();

    @AfterEach
    void reset() {
        Locale.setDefault(systemLocale);
    }

    @Test
    void findsTheLanguagesThatHaveATranslationFile() {
        AcceptLanguageLocaleResolver resolver = AcceptLanguageLocaleResolver.forBundles("messages",
                getClass().getClassLoader());

        // Englisch sind die Texte ohne Sprachkürzel; jede weitere Sprache kommt allein durch ihre Datei hinzu
        assertThat(resolver.supportedLocales()).startsWith(Locale.ENGLISH).contains(Locale.GERMAN)
                .doesNotContain(Locale.GERMANY).doesNotHaveDuplicates();
    }

    @Test
    void picksTheBestSupportedLanguageAndFallsBackToEnglish() {
        Locale.setDefault(Locale.GERMANY); // die Sprache des Servers darf keine Rolle spielen
        AcceptLanguageLocaleResolver resolver = new AcceptLanguageLocaleResolver(
                List.of(Locale.ENGLISH, Locale.GERMAN, Locale.forLanguageTag("pt-BR")));

        assertThat(resolver.bestMatch(null)).isEqualTo(Locale.ENGLISH);
        assertThat(resolver.bestMatch("")).isEqualTo(Locale.ENGLISH);
        assertThat(resolver.bestMatch("de")).isEqualTo(Locale.GERMAN);
        // die Region bleibt erhalten – für die Schreibweise von Datum und Zahlen; die Texte sind die deutschen
        assertThat(resolver.bestMatch("DE-ch")).isEqualTo(Locale.of("de", "CH"));
        assertThat(resolver.bestMatch("de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7")).isEqualTo(Locale.GERMANY);
        assertThat(resolver.bestMatch("en-GB,de;q=0.9")).isEqualTo(Locale.UK);
        assertThat(resolver.bestMatch("en,en-IE;q=0.5")).isEqualTo(Locale.of("en", "IE"));
        assertThat(resolver.bestMatch("de-419")).isEqualTo(Locale.GERMAN); // Regionen nur als Ländercode
        assertThat(resolver.bestMatch("de;q=0.3,en;q=0.9")).isEqualTo(Locale.ENGLISH);
        assertThat(resolver.bestMatch("pt-BR")).isEqualTo(Locale.forLanguageTag("pt-BR"));
        // keine Übersetzung vorhanden
        assertThat(resolver.bestMatch("fi")).isEqualTo(Locale.ENGLISH);
        assertThat(resolver.bestMatch("fi-FI,sv;q=0.8")).isEqualTo(Locale.ENGLISH);
        assertThat(resolver.bestMatch("fi,de;q=0.5")).isEqualTo(Locale.GERMAN);
        // Unverständliches
        assertThat(resolver.bestMatch("*")).isEqualTo(Locale.ENGLISH);
        assertThat(resolver.bestMatch("%%%")).isEqualTo(Locale.ENGLISH);
        assertThat(resolver.bestMatch("de;q=zwei")).isEqualTo(Locale.ENGLISH);

        MockHttpServletRequest request = new MockHttpServletRequest();
        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.ENGLISH);
        request.addHeader("Accept-Language", "de-AT");
        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.of("de", "AT"));
    }
}
