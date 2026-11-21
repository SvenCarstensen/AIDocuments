package de.aidocuments.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Liest das Ergebnis eines Modells, das sich nicht streng an das Schema hält. Lokale Modelle liefern
 * Listen gelegentlich als Text ("a, b, c" oder ein JSON-Array in Anführungszeichen), Kerndaten als
 * Objekt statt als Liste oder Zahlen statt Zeichenketten. Alles, was sich sinnvoll deuten lässt, wird
 * übernommen; der Rest bleibt leer, statt die ganze Analyse zu verwerfen.
 */
final class LenientAnalysisParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private LenientAnalysisParser() {
    }

    /** Sucht im Text das äußerste JSON-Objekt und liest es. */
    static DocumentAnalysis parseText(String text) throws JsonProcessingException {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new JsonProcessingException("kein JSON-Objekt in der Antwort") {
            };
        }
        return parse(JSON.readTree(text.substring(start, end + 1)));
    }

    /**
     * Liest eine Antwort, die mittendrin abgeschnitten wurde: Übernommen wird alles bis zum letzten vollständigen
     * Wert, der angefangene Rest entfällt. Eine vollständige Antwort wird gelesen wie von {@link #parseText}.
     */
    static DocumentAnalysis parseTruncated(String text) throws JsonProcessingException {
        String json = closeTruncated(text);
        if (json == null) {
            throw new JsonProcessingException("kein JSON-Objekt in der Antwort") {
            };
        }
        return parse(JSON.readTree(json));
    }

    /**
     * Macht aus abgeschnittenem JSON wieder gültiges: Der Text wird hinter dem letzten vollständigen Wert gekappt,
     * und die noch offenen Listen und Objekte werden geschlossen. {@code null}, wenn kein Objekt beginnt.
     */
    static String closeTruncated(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        Deque<Character> open = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;
        boolean stringIsKey = false;
        boolean keyExpected = false;
        int safeEnd = -1;
        String safeClosers = "";
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                    if (!stringIsKey) {
                        safeEnd = i + 1;
                        safeClosers = closers(open);
                    }
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    inString = true;
                    stringIsKey = keyExpected;
                }
                case '{', '[' -> {
                    open.push(c);
                    keyExpected = c == '{';
                    safeEnd = i + 1;
                    safeClosers = closers(open);
                }
                case '}', ']' -> {
                    if (open.isEmpty()) {
                        return null;
                    }
                    open.pop();
                    if (open.isEmpty()) {
                        return text.substring(start, i + 1); // vollständig – was danach kommt, ist Beiwerk
                    }
                    keyExpected = false;
                    safeEnd = i + 1;
                    safeClosers = closers(open);
                }
                case ',' -> {
                    // was vor dem Komma steht, ist vollständig – auch eine Zahl, die sonst kein Ende erkennen lässt
                    safeEnd = i;
                    safeClosers = closers(open);
                    keyExpected = !open.isEmpty() && open.peek() == '{';
                }
                case ':' -> keyExpected = false;
                default -> {
                }
            }
        }
        return safeEnd < 0 ? null : text.substring(start, safeEnd) + safeClosers;
    }

    private static String closers(Deque<Character> open) {
        StringBuilder closers = new StringBuilder();
        open.forEach(bracket -> closers.append(bracket == '{' ? '}' : ']'));
        return closers.toString();
    }

    static DocumentAnalysis parse(Object value) {
        return parse(JSON.valueToTree(value));
    }

    static DocumentAnalysis parse(JsonNode node) {
        return new DocumentAnalysis(
                text(node.get("title")),
                text(node.get("summary")),
                text(node.get("category")),
                text(node.get("correspondent")),
                text(node.get("documentDate")),
                text(node.get("language")),
                strings(node.get("tags")),
                facts(node.get("keyFacts")),
                text(node.get("fullText")));
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        if (node.isValueNode()) {
            return node.asText();
        }
        if (node.isArray()) {
            List<String> parts = new ArrayList<>();
            node.forEach(element -> parts.add(text(element)));
            return String.join("\n", parts);
        }
        return node.toString();
    }

    private static List<String> strings(JsonNode node) {
        JsonNode list = unwrap(node);
        List<String> result = new ArrayList<>();
        if (list == null || list.isNull()) {
            return result;
        }
        if (list.isArray()) {
            list.forEach(element -> addIfPresent(result, text(element)));
        } else if (list.isValueNode()) {
            for (String part : list.asText().split("[,;\\n]")) {
                addIfPresent(result, part);
            }
        }
        return result;
    }

    private static List<DocumentAnalysis.Fact> facts(JsonNode node) {
        JsonNode list = unwrap(node);
        List<DocumentAnalysis.Fact> result = new ArrayList<>();
        if (list == null || list.isNull()) {
            return result;
        }
        if (list.isArray()) {
            for (JsonNode element : list) {
                if (element.isObject()) {
                    String label = firstText(element, "label", "name", "key", "bezeichnung");
                    String value = firstText(element, "value", "wert");
                    if (label.isBlank() && value.isBlank() && element.size() == 1) {
                        // {"Rechnungsnummer": "4711"}
                        String only = element.fieldNames().next();
                        label = only;
                        value = text(element.get(only));
                    }
                    addFact(result, label, value);
                } else {
                    addFact(result, text(element));
                }
            }
        } else if (list.isObject()) {
            // {"Rechnungsnummer": "4711", "Betrag": "12,00 €"}
            list.fields().forEachRemaining(entry -> addFact(result, entry.getKey(), text(entry.getValue())));
        } else if (list.isValueNode()) {
            for (String line : list.asText().split("[;\\n]")) {
                addFact(result, line);
            }
        }
        return result;
    }

    /** Ein JSON-Array oder -Objekt, das als Zeichenkette verpackt ankam, wieder auspacken. */
    private static JsonNode unwrap(JsonNode node) {
        if (node != null && node.isTextual()) {
            String raw = node.asText().strip();
            if (raw.startsWith("[") || raw.startsWith("{")) {
                try {
                    return JSON.readTree(raw);
                } catch (JsonProcessingException e) {
                    return node;
                }
            }
        }
        return node;
    }

    private static String firstText(JsonNode object, String... names) {
        for (String name : names) {
            if (object.hasNonNull(name)) {
                return text(object.get(name));
            }
        }
        return "";
    }

    /** "Rechnungsnummer: 4711" → Bezeichnung und Wert. */
    private static void addFact(List<DocumentAnalysis.Fact> result, String line) {
        int colon = line.indexOf(':');
        if (colon > 0) {
            addFact(result, line.substring(0, colon), line.substring(colon + 1));
        }
    }

    private static void addFact(List<DocumentAnalysis.Fact> result, String label, String value) {
        if (label != null && value != null && !label.isBlank() && !value.isBlank()) {
            result.add(new DocumentAnalysis.Fact(label.strip(), value.strip()));
        }
    }

    private static void addIfPresent(List<String> result, String value) {
        if (value != null && !value.isBlank()) {
            result.add(value.strip());
        }
    }
}
