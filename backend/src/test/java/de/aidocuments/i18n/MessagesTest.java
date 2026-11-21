package de.aidocuments.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import de.aidocuments.ai.AiException;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

/** Sprachneutrale Texte: wie sie gespeichert werden und in welcher Sprache sie beim Leser ankommen. */
class MessagesTest {

    /** Eine Sprache ohne Übersetzungsdatei – bewusst keine, für die je eine hinzukommen dürfte (Klingonisch). */
    private static final Locale UNTRANSLATED = Locale.forLanguageTag("tlh");

    private final Messages messages = TestMessages.create();
    private final Locale systemLocale = Locale.getDefault();

    @AfterEach
    void reset() {
        Locale.setDefault(systemLocale);
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void englishIsTheDefaultAndNeverTheLanguageOfTheServer() {
        Locale.setDefault(Locale.GERMANY);
        Msg msg = Msg.of("error.user.nameTaken", "erika");

        // außerhalb einer Anfrage (Hintergrund) und für Sprachen ohne Übersetzungsdatei
        assertThat(messages.render(msg)).isEqualTo("The username “erika” is already taken.");
        assertThat(messages.render(msg, UNTRANSLATED)).isEqualTo("The username “erika” is already taken.");
        assertThat(messages.render(msg, Locale.GERMAN)).isEqualTo("Der Benutzername „erika“ ist bereits vergeben.");
        assertThat(messages.render(msg, Locale.forLanguageTag("de-AT")))
                .isEqualTo("Der Benutzername „erika“ ist bereits vergeben.");

        // innerhalb einer Anfrage gilt deren Sprache
        LocaleContextHolder.setLocale(Locale.GERMAN);
        assertThat(messages.render(msg)).isEqualTo("Der Benutzername „erika“ ist bereits vergeben.");
        assertThat(messages.get("error.document.notFound")).isEqualTo("Das Dokument wurde nicht gefunden.");
    }

    @Test
    void storedSummaryIsRenderedForEachReaderWithCountsThatNeedNoPluralForms() {
        String stored = MsgCodec.encode(List.of(
                Msg.of("mailbox.sync.checked", 1), Msg.of("mailbox.sync.imported", 0),
                Msg.of("mailbox.sync.duplicates", 21)));

        assertThat(stored).isEqualTo("i18n:[{\"k\":\"mailbox.sync.checked\",\"a\":[1]},"
                + "{\"k\":\"mailbox.sync.imported\",\"a\":[0]},{\"k\":\"mailbox.sync.duplicates\",\"a\":[21]}]");
        assertThat(messages.localize(stored, Locale.ENGLISH))
                .isEqualTo("New e-mails checked: 1. PDFs imported: 0. Already present: 21.");
        assertThat(messages.localize(stored, Locale.GERMAN))
                .isEqualTo("Neue E-Mails geprüft: 1. PDFs übernommen: 0. Bereits vorhanden: 21.");
        assertThat(messages.localize(stored, UNTRANSLATED)).isEqualTo(messages.localize(stored, Locale.ENGLISH));
    }

    @Test
    void sameAiErrorServesTheConnectionTestAndTheNoteStoredOnADocument() {
        AiException error = new AiException(Msg.of("ai.error.quotaExhausted", "You exceeded your current quota"));

        // Verbindungstest: sofort, in der Sprache der Anfrage
        assertThat(messages.render(error.msg(), Locale.GERMAN)).isEqualTo(
                "Das Anfragelimit oder Guthaben beim KI-Anbieter ist erschöpft: You exceeded your current quota");

        // Analyse im Hintergrund: als Ursache in der Meldung des Dokuments gespeichert
        String stored = MsgCodec.encode(Msg.of("document.error.aiFailed", error.msg()));
        assertThat(messages.localize(stored, Locale.ENGLISH)).isEqualTo("AI analysis failed: The request limit or "
                + "the credit at the AI provider is exhausted: You exceeded your current quota");
        assertThat(messages.localize(stored, Locale.GERMAN)).isEqualTo("KI-Analyse fehlgeschlagen: Das Anfragelimit "
                + "oder Guthaben beim KI-Anbieter ist erschöpft: You exceeded your current quota");
    }

    @Test
    void argumentsMayBeSentencesTextNumbersAndTechnicalDetailsWithAnyCharacters() {
        Msg nested = Msg.of("ai.error.unprocessable", List.of(
                Msg.of("ai.problem.titleEmpty"), Msg.of("ai.problem.categoryInvalid", "Strom\"rechnung {0} 'x'")));
        Msg detail = Msg.literal("javax.net.ssl.SSLException: it's {broken}, \\ \"really\"\n2nd line");
        Msg count = Msg.of("mailbox.test.success", "Posteingang/Scans", 12345);

        String stored = MsgCodec.encode(nested, detail, count);
        assertThat(MsgCodec.decode(stored)).containsExactly(nested, detail, count);
        assertThat(messages.localize(stored, Locale.GERMAN)).isEqualTo(
                "Das Modell hat auch im zweiten Anlauf keine verarbeitbare Antwort geliefert: Das Feld title war "
                        + "leer. Das Feld category war „Strom\"rechnung {0} 'x'“ – erlaubt ist nur genau einer der "
                        + "Begriffe aus der Liste der Dokumentarten."
                        + " javax.net.ssl.SSLException: it's {broken}, \\ \"really\"\n2nd line"
                        + " Verbindung erfolgreich – E-Mails im Ordner „Posteingang/Scans“: 12.345.");
        assertThat(messages.localize(stored, Locale.ENGLISH))
                .startsWith("Even on the second attempt the model did not return a processable answer: "
                        + "The field title was empty. The field category was “Strom\"rechnung {0} 'x'” – ")
                .endsWith("Connection successful – e-mails in folder “Posteingang/Scans”: 12,345.");
    }

    @Test
    void plainTextFromBeforeTranslationPassesThroughUnchanged() {
        for (String legacy : List.of(
                "2 neue E-Mails geprüft, 1 PDF übernommen.",
                "KI-Analyse fehlgeschlagen: Der API-Schlüssel wurde vom KI-Server abgelehnt.",
                "Der Ordner „/import“ ist nicht erreichbar.",
                "{\"k\":\"mailbox.sync.none\"}",
                "[AUTHENTICATIONFAILED] Invalid credentials",
                "i18n: kein JSON",
                "i18n:[{\"a\":[1]}]",
                "i18n:{\"k\":\"mailbox.sync.none\"}",
                "")) {
            assertThat(MsgCodec.decode(legacy)).as(legacy).isNull();
            assertThat(messages.localize(legacy, Locale.ENGLISH)).isEqualTo(legacy);
            assertThat(messages.localize(legacy, Locale.GERMAN)).isEqualTo(legacy);
        }
        assertThat(messages.localize(null, Locale.GERMAN)).isNull();
        assertThat(MsgCodec.encode(List.of())).isNull();
    }

    @Test
    void unknownKeysAndOverlongDetailsDoNotBreakAnything() {
        // ein Schlüssel, den diese Version nicht (mehr) kennt
        assertThat(messages.localize(MsgCodec.encode(Msg.of("gibt.es.nicht", 3)), Locale.GERMAN))
                .isEqualTo("gibt.es.nicht");

        String stored = MsgCodec.encode(Msg.of("error.unexpected", "x".repeat(50_000)));
        assertThat(stored.length()).isLessThan(1200);
        assertThat(messages.localize(stored, Locale.ENGLISH)).startsWith("Unexpected error: xxx").endsWith("x…");
        assertThat(messages.localize(MsgCodec.encode(Msg.of("error.unexpected", (Object) null)), Locale.ENGLISH))
                .isEqualTo("Unexpected error: ");
    }

    @Test
    void exceptionDetailsKeepTheirOwnTranslatableMessage() {
        AiException cause = new AiException(Msg.of("ai.error.timeout"));
        assertThat(messages.render(Msg.of("error.unexpected", Msg.detailOf(cause)), Locale.GERMAN))
                .isEqualTo("Unerwarteter Fehler: Der KI-Server hat nicht rechtzeitig geantwortet.");
        assertThat(messages.render(Msg.of("error.unexpected", Msg.detailOf(new IllegalStateException("boom"))),
                Locale.GERMAN)).isEqualTo("Unerwarteter Fehler: boom");
        assertThat(messages.render(Msg.detailOf(new NullPointerException()), Locale.GERMAN))
                .isEqualTo("NullPointerException");
    }
}
