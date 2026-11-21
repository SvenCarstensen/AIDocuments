package de.aidocuments.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Was die KI als Filter vorschlägt, muss im Bestand vorkommen – sonst wird es zum Suchbegriff. */
class AiSearchServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> CORRESPONDENTS = List.of("Kia Leasing GmbH", "Stadtwerke Musterstadt");
    private static final List<String> TAGS = List.of("leasing", "strom");

    private static AiSearchService.Interpretation validate(String json) throws Exception {
        return AiSearchService.validate("frage", JSON.readTree(json), CORRESPONDENTS, TAGS);
    }

    @Test
    void takesKnownFiltersAndNormalizesTheirSpelling() throws Exception {
        AiSearchService.Interpretation result = validate("""
                {"text":"","category":"rechnung","correspondent":"kia leasing gmbh","tags":["Leasing"],
                 "from":"2024-12-31","to":"2024-01-01"}""");

        assertThat(result.category()).isEqualTo("Rechnung");
        assertThat(result.correspondent()).isEqualTo("Kia Leasing GmbH");
        assertThat(result.tags()).containsExactly("leasing");
        assertThat(result.text()).isEmpty();
        // vertauschter Zeitraum wird gerade gerückt
        assertThat(result.from()).isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(result.to()).isEqualTo(LocalDate.of(2024, 12, 31));
    }

    @Test
    void unknownValuesBecomeSearchTermsInsteadOfEmptyResults() throws Exception {
        AiSearchService.Interpretation result = validate("""
                {"text":"Fahrzeugschein","category":"Fahrzeugpapier","correspondent":"Kia","tags":["auto","strom"],
                 "from":"letztes Jahr","to":""}""");

        assertThat(result.category()).isNull();
        assertThat(result.correspondent()).isNull();
        assertThat(result.tags()).containsExactly("strom");
        assertThat(result.text()).isEqualTo("Fahrzeugschein Fahrzeugpapier Kia auto");
        assertThat(result.from()).isNull();
        assertThat(result.to()).isNull();
    }
}
