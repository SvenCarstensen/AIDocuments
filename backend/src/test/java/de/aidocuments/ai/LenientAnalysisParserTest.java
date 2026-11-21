package de.aidocuments.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Lokale Modelle halten das Ergebnis-Schema nicht immer ein – diese Abweichungen müssen lesbar bleiben. */
class LenientAnalysisParserTest {

    @Test
    void readsAWellFormedResult() {
        DocumentAnalysis analysis = LenientAnalysisParser.parse(Map.of(
                "title", "Stromrechnung 2024",
                "tags", List.of("strom", "energie"),
                "keyFacts", List.of(Map.of("label", "Betrag", "value", "482,17 EUR"))));

        assertThat(analysis.title()).isEqualTo("Stromrechnung 2024");
        assertThat(analysis.tags()).containsExactly("strom", "energie");
        assertThat(analysis.keyFacts()).containsExactly(new DocumentAnalysis.Fact("Betrag", "482,17 EUR"));
        assertThat(analysis.summary()).isEmpty();
        assertThat(analysis.fullText()).isEmpty();
    }

    @Test
    void acceptsListsThatArriveAsText() {
        DocumentAnalysis analysis = LenientAnalysisParser.parse(Map.of(
                "tags", "strom, energie; nachzahlung",
                "keyFacts", "[{\"label\":\"Rechnungsnummer\",\"value\":\"4711\"}]"));

        assertThat(analysis.tags()).containsExactly("strom", "energie", "nachzahlung");
        assertThat(analysis.keyFacts()).containsExactly(new DocumentAnalysis.Fact("Rechnungsnummer", "4711"));
    }

    @Test
    void acceptsKeyFactsInOtherShapes() {
        assertThat(LenientAnalysisParser.parse(Map.of("keyFacts", "Betrag: 12,00 EUR\nFällig: 14.03.2025")).keyFacts())
                .containsExactly(new DocumentAnalysis.Fact("Betrag", "12,00 EUR"),
                        new DocumentAnalysis.Fact("Fällig", "14.03.2025"));
        assertThat(LenientAnalysisParser.parse(Map.of("keyFacts", Map.of("IBAN", "DE12 3456"))).keyFacts())
                .containsExactly(new DocumentAnalysis.Fact("IBAN", "DE12 3456"));
        assertThat(LenientAnalysisParser.parse(Map.of("keyFacts", List.of(Map.of("Kundennummer", 88123)))).keyFacts())
                .containsExactly(new DocumentAnalysis.Fact("Kundennummer", "88123"));
        assertThat(LenientAnalysisParser.parse(Map.of("keyFacts", "keine")).keyFacts()).isEmpty();
    }

    @Test
    void findsTheJsonObjectInsideSurroundingText() throws Exception {
        DocumentAnalysis analysis = LenientAnalysisParser.parseText(
                "Hier ist das Ergebnis:\n```json\n{\"title\": \"Mietvertrag\", \"tags\": [\"miete\"], \"pages\": 3}\n```");

        assertThat(analysis.title()).isEqualTo("Mietvertrag");
        assertThat(analysis.tags()).containsExactly("miete");
    }

    @Test
    void namesWhatMakesAResultUnusable() {
        DocumentAnalysis complete = new DocumentAnalysis("Stromrechnung 2024", "Nachzahlung von 482,17 EUR.",
                "Rechnung", "Stadtwerke", "2025-02-28", "de", List.of("strom"), List.of(), "");
        assertThat(AiAnalysisService.problemsOf(complete, false)).isEmpty();
        assertThat(AiAnalysisService.problemsOf(complete, true)).singleElement().asString().contains("fullText");

        DocumentAnalysis sloppy = new DocumentAnalysis("", "", "Stromrechnung", "", "Ende Februar", "de",
                List.of(), List.of(), "");
        assertThat(AiAnalysisService.problemsOf(sloppy, false)).hasSize(5)
                .anySatisfy(problem -> assertThat(problem).contains("category").contains("Stromrechnung"))
                .anySatisfy(problem -> assertThat(problem).contains("documentDate").contains("JJJJ-MM-TT"));
    }

    @Test
    void readsDatesInIsoAndGermanNotation() {
        assertThat(DocumentAnalysis.parseDate("2025-03-14")).hasToString("2025-03-14");
        assertThat(DocumentAnalysis.parseDate(" 14.03.2025 ")).hasToString("2025-03-14");
        assertThat(DocumentAnalysis.parseDate("3.1.2025")).hasToString("2025-01-03");
        assertThat(DocumentAnalysis.parseDate("März 2025")).isNull();
        assertThat(DocumentAnalysis.parseDate("")).isNull();
    }

    /** Für die Claude API leitet das SDK das Antwortschema aus der Klasse ab – das muss ohne Netz gelingen. */
    @Test
    void structuredOutputSchemaCanBeDerivedFromTheResultClass() {
        var params = com.anthropic.models.beta.messages.MessageCreateParams.builder()
                .model("claude-opus-5-5")
                .maxTokens(1000L)
                .addUserMessage("Erschließe das Dokument.")
                .outputConfig(DocumentAnalysis.class)
                .build();

        assertThat(params.rawParams()._body().toString()).contains("keyFacts").contains("documentDate");
    }
}
