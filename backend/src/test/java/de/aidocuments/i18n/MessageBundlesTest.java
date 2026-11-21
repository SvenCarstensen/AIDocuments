package de.aidocuments.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

/**
 * Wacht über die Übersetzungsdateien: Jede {@code messages_xx.properties} muss genau die Schlüssel und
 * Platzhalter der englischen {@code messages.properties} haben. Schlägt dieser Test nach dem Hinzufügen einer
 * Sprache fehl, nennt er Datei, Schlüssel und Grund.
 */
class MessageBundlesTest {

    private static final Path RESOURCES = Path.of("src/main/resources");
    private static final Path SOURCES = Path.of("src/main/java");
    private static final String BASE = "messages.properties";
    /** {0}, {1,number}, {max} */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_]+)(?:,[^}]*)?}");
    /** Ein Schlüssel als Zeichenkette im Quelltext: "error.user.notFound" oder "{validation.password}". */
    private static final Pattern KEY_IN_SOURCE = Pattern.compile(
            "\"\\{?((?:error|validation|upload|document|mailbox|folder|ai)(?:\\.[A-Za-z][A-Za-z0-9]*)+)}?\"");
    private static final Pattern CONSTRAINT = Pattern.compile(
            "@(NotBlank|NotNull|NotEmpty|Size|Min|Max|Pattern|Email|Positive|PositiveOrZero|Negative|NegativeOrZero"
                    + "|Past|PastOrPresent|Future|FutureOrPresent|Digits|DecimalMin|DecimalMax|AssertTrue|AssertFalse)"
                    + "\\b(\\([^)]*\\))?");

    @Test
    void germanTranslationExists() {
        assertThat(bundles()).containsKeys(BASE, "messages_de.properties");
    }

    @Test
    void everyTranslationHasExactlyTheKeysAndPlaceholdersOfTheEnglishTexts() {
        Map<String, Map<String, String>> bundles = bundles();
        Map<String, String> english = bundles.get(BASE);
        SoftAssertions problems = new SoftAssertions();
        bundles.forEach((file, texts) -> {
            if (file.equals(BASE)) {
                return;
            }
            problems.assertThat(missing(english.keySet(), texts.keySet()))
                    .as("%s: keys of %s without translation", file, BASE).isEmpty();
            problems.assertThat(missing(texts.keySet(), english.keySet()))
                    .as("%s: keys that %s does not have (typo, or removed from the application?)", file, BASE)
                    .isEmpty();
            english.forEach((key, text) -> {
                if (texts.containsKey(key)) {
                    problems.assertThat(placeholders(texts.get(key)))
                            .as("%s: placeholders of %s – the English text is: %s", file, key, text)
                            .isEqualTo(placeholders(text));
                    problems.assertThat(texts.get(key).isBlank()).as("%s: %s is empty", file, key).isFalse();
                }
            });
        });
        problems.assertAll();
    }

    @Test
    void everyTextIsAValidPattern() {
        SoftAssertions problems = new SoftAssertions();
        bundles().forEach((file, texts) -> texts.forEach((key, text) -> {
            if (key.startsWith("validation.")) {
                // Vorlagen der Eingabeprüfung: benannte Platzhalter, Apostroph einfach
                problems.assertThat(placeholders(text)).as("%s: %s – only {min}, {max} and {value} exist", file, key)
                        .isSubsetOf("min", "max", "value");
                return;
            }
            // alle übrigen: java.text.MessageFormat – nummerierte Platzhalter, Apostroph doppelt
            problems.assertThat(placeholders(text)).as("%s: %s – placeholders must be numbers", file, key)
                    .allMatch(name -> name.matches("\\d+"));
            problems.assertThat(text.replace("''", "")).as("%s: %s – write an apostrophe twice ('')", file, key)
                    .doesNotContain("'");
            try {
                new MessageFormat(text, Locale.ROOT);
            } catch (IllegalArgumentException e) {
                problems.fail("%s: %s is not a valid MessageFormat pattern (%s): %s", file, key, e.getMessage(), text);
            }
        }));
        problems.assertAll();
    }

    /** Vertippte Schlüssel fielen sonst erst dem Anwender auf – er sähe den Schlüssel statt eines Satzes. */
    @Test
    void everyKeyUsedInTheCodeHasATextAndEveryTextIsUsed() {
        Set<String> used = new TreeSet<>();
        for (String source : sources()) {
            Matcher matcher = KEY_IN_SOURCE.matcher(source);
            while (matcher.find()) {
                used.add(matcher.group(1));
            }
        }
        Set<String> defined = bundles().get(BASE).keySet();

        assertThat(used).as("keys found in the source code").hasSizeGreaterThan(50);
        assertThat(missing(used, defined)).as("keys used in the code without a text in %s", BASE).isEmpty();
        assertThat(missing(defined, used)).as("texts in %s that the code does not use", BASE).isEmpty();
    }

    /**
     * Die Standardmeldungen der Validierungsbibliothek gibt es nicht in jeder Sprache; fehlt eine, fällt die
     * Bibliothek auf die Sprache des Servers zurück. Deshalb nennt jede Prüfung ihren Text aus unseren Dateien.
     */
    @Test
    void everyValidationConstraintNamesItsMessage() {
        List<String> withoutMessage = new ArrayList<>();
        for (String source : sources()) {
            Matcher matcher = CONSTRAINT.matcher(source);
            while (matcher.find()) {
                if (matcher.group(2) == null || !matcher.group(2).contains("message")) {
                    withoutMessage.add(matcher.group());
                }
            }
        }
        assertThat(withoutMessage).isEmpty();
    }

    private static Set<String> missing(Set<String> expected, Set<String> actual) {
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        return missing;
    }

    private static Set<String> placeholders(String text) {
        Set<String> names = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /** Dateiname → (Schlüssel → Text) für messages.properties und alle messages_xx.properties. */
    private static Map<String, Map<String, String>> bundles() {
        Map<String, Map<String, String>> bundles = new TreeMap<>();
        try (Stream<Path> files = Files.list(RESOURCES)) {
            for (Path file : files.filter(MessageBundlesTest::isBundle).toList()) {
                bundles.put(file.getFileName().toString(), load(file));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bundles;
    }

    private static boolean isBundle(Path file) {
        String name = file.getFileName().toString();
        return name.equals(BASE) || name.matches("messages_[A-Za-z_]+\\.properties");
    }

    private static Map<String, String> load(Path file) throws IOException {
        String name = file.getFileName().toString();
        List<Object> duplicates = new ArrayList<>();
        Properties properties = new Properties() {
            @Override
            public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) {
                    duplicates.add(key);
                }
                return super.put(key, value);
            }
        };
        String content;
        try {
            content = Files.readString(file); // streng UTF-8: scheitert an ISO-8859-1-Umlauten
        } catch (IOException e) {
            throw new AssertionError(name + " is not a valid UTF-8 file", e);
        }
        properties.load(new StringReader(content));
        assertThat(duplicates).as("%s: keys that occur more than once", name).isEmpty();
        assertThat(content).as("%s: starts with a byte order mark – save as UTF-8 without BOM", name)
                .doesNotStartWith("\uFEFF");
        Map<String, String> texts = new TreeMap<>();
        properties.forEach((key, value) -> texts.put((String) key, (String) value));
        return texts;
    }

    private static List<String> sources() {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            List<String> sources = new ArrayList<>();
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                sources.add(Files.readString(file));
            }
            return sources;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
