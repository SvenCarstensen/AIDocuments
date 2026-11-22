package de.aidocuments.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MailFilterTest {

    private static final List<String> COPIER = List.of("Kopierer 2. OG <scanner@büro.example>");

    @Test
    void withoutTermsEveryMailPasses() {
        assertThat(MailFilter.NONE.isEmpty()).isTrue();
        assertThat(MailFilter.NONE.matches(null, List.of())).isTrue();
        assertThat(new MailFilter(null, List.of(" ", ""), null, null).isEmpty()).isTrue();
    }

    @Test
    void subjectMustContainOneOfTheTermsAndNoneOfTheExcludedOnes() {
        MailFilter filter = new MailFilter(List.of("Scan", "Rechnung"), List.of("Werbung"), List.of(), List.of());
        assertThat(filter.matches("Ihr SCAN vom Kopierer", COPIER)).isTrue();
        assertThat(filter.matches("Rechnungsnummer 4711", COPIER)).isTrue(); // Teil eines Wortes genügt
        assertThat(filter.matches("Newsletter", COPIER)).isFalse();
        assertThat(filter.matches("Scan – Werbung", COPIER)).isFalse();
        assertThat(filter.matches(null, COPIER)).isFalse();
    }

    @Test
    void senderIsComparedWithNameAndAddress() {
        assertThat(new MailFilter(null, null, List.of("@büro.example"), null).matches("x", COPIER)).isTrue();
        assertThat(new MailFilter(null, null, List.of("kopierer"), null).matches("x", COPIER)).isTrue();
        assertThat(new MailFilter(null, null, List.of("@other.example"), null).matches("x", COPIER)).isFalse();
        assertThat(new MailFilter(null, null, List.of("@other.example"), null).matches("x", List.of())).isFalse();
        assertThat(new MailFilter(null, null, null, List.of("2. og")).matches("x", COPIER)).isFalse();
        // mehrere Absender: einer, der passt, genügt – einer, der ausgeschlossen ist, schließt aus
        List<String> two = List.of("a@other.example", "Kopierer <scanner@büro.example>");
        assertThat(new MailFilter(null, null, List.of("scanner@"), null).matches("x", two)).isTrue();
        assertThat(new MailFilter(null, null, null, List.of("a@other")).matches("x", two)).isFalse();
    }

    @Test
    void allConditionsMustHold() {
        MailFilter filter = new MailFilter(List.of("Scan"), List.of(), List.of("@büro.example"), List.of());
        assertThat(filter.matches("Scan 17", COPIER)).isTrue();
        assertThat(filter.matches("Scan 17", List.of("someone@other.example"))).isFalse();
        assertThat(filter.matches("Rechnung", COPIER)).isFalse();
    }

    @Test
    void spellingDifferencesDoNotMatter() {
        // „ü“ zusammengesetzt (NFD) im Betreff, vorkomponiert im Begriff; Zeilenumbruch aus einem gefalteten Header
        MailFilter filter = new MailFilter(List.of("Prüfbericht  Nr"), null, null, null);
        assertThat(filter.matches("Prüfbericht\r\n Nr. 5", COPIER)).isTrue();
    }

    @Test
    void termsAreCleanedAndStoredOnePerLine() {
        MailFilter filter = new MailFilter(List.of(" Scan ", "scan", "", "Rech\nnung", "Fax"), null, null, null);
        assertThat(filter.subjectIncludes()).containsExactly("Scan", "Rech nung", "Fax");
        assertThat(MailFilter.split(MailFilter.join(filter.subjectIncludes()))).isEqualTo(filter.subjectIncludes());
        assertThat(MailFilter.join(List.of())).isNull();
        assertThat(MailFilter.split(null)).isEmpty();
        assertThat(filter).isEqualTo(new MailFilter(List.of("Scan", "Rech nung", "Fax"), List.of(), null, null));
    }
}
