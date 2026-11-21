package de.aidocuments.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import de.aidocuments.document.PdfExtractor;
import de.aidocuments.settings.AiSettingsService;
import de.aidocuments.settings.AiSettingsService.Effective;
import de.aidocuments.settings.AiSettingsService.KeySource;
import de.aidocuments.settings.LlmProvider;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Antworten, die länger geraten als das Token-Limit – typisch für Modelle, die vor dem Antworten „nachdenken“:
 * Erst gibt es einen zweiten Anlauf mit mehr Platz, dann wird aus dem Abgeschnittenen gerettet, was vollständig ist.
 */
class LongAnswerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COMPLETE = """
            {"title":"Rechnung Steuerberatung 2023","summary":"Der Steuerberater rechnet seine Leistungen ab.",\
            "category":"Rechnung","correspondent":"Muster Steuerberatung","documentDate":"2024-12-31","language":"de",\
            "tags":["steuerberatung","rechnung"],"keyFacts":[{"label":"Betrag","value":"482,17 EUR"},\
            {"label":"Rechnungsnummer","value":"2024/4711"}],"fullText":""}""";

    /** Antworten des Stellvertreter-Servers in der Reihenfolge der Anfragen: Status und Rumpf. */
    private final Deque<Object[]> responses = new ArrayDeque<>();
    private final List<Long> requestedLimits = new ArrayList<>();
    private HttpServer server;
    private AiAnalysisService service;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestedLimits.add(JSON.readTree(body).path("max_tokens").asLong());
            Object[] next = responses.removeFirst();
            byte[] bytes = ((String) next[1]).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders((Integer) next[0], bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        AiSettingsService settings = mock(AiSettingsService.class);
        when(settings.current()).thenReturn(new Effective(LlmProvider.OPENAI_COMPATIBLE,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "", KeySource.NONE, "modell",
                "medium", "Deutsch", true, false, false));
        service = new AiAnalysisService(settings, new PdfExtractor());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void asksAgainWithMoreRoomWhenThinkingUsedUpTheLimit() throws Exception {
        respond(200, reply("length", null, "Wir müssen das Dokument erschließen. ".repeat(50)));
        respond(200, reply("tool_calls", COMPLETE, "Kurz überlegt."));

        AiAnalysisService.Result result = service.analyze(textDocument());

        assertThat(result.analysis().title()).isEqualTo("Rechnung Steuerberatung 2023");
        assertThat(result.analysis().keyFacts()).hasSize(2);
        assertThat(result.notes()).isEmpty();
        assertThat(requestedLimits).containsExactly(16000L, 32000L);
    }

    @Test
    void keepsWhatArrivedCompleteWhenTheLargerLimitIsNotEnoughEither() throws Exception {
        String cutInSecondFact = COMPLETE.substring(0, COMPLETE.indexOf("2024/4711") + 4);
        respond(200, reply("length", null, "Sehr langes Nachdenken."));
        respond(200, reply("length", cutInSecondFact, "Noch längeres Nachdenken."));

        AiAnalysisService.Result result = service.analyze(textDocument());

        assertThat(result.analysis().title()).isEqualTo("Rechnung Steuerberatung 2023");
        assertThat(result.analysis().tags()).containsExactly("steuerberatung", "rechnung");
        assertThat(result.analysis().documentDate()).isEqualTo("2024-12-31");
        assertThat(result.analysis().keyFacts()).containsExactly(new DocumentAnalysis.Fact("Betrag", "482,17 EUR"));
        assertThat(requestedLimits).containsExactly(16000L, 32000L);
    }

    @Test
    void fallsBackToTheFirstAnswerWhenTheServerRejectsTheLargerLimit() throws Exception {
        String cutInFacts = COMPLETE.substring(0, COMPLETE.indexOf("\"keyFacts\"") + 12);
        respond(200, reply("length", cutInFacts, ""));
        respond(400, "{\"error\":{\"message\":\"max_tokens is too large: 32000\"}}");

        AiAnalysisService.Result result = service.analyze(textDocument());

        assertThat(result.analysis().summary()).startsWith("Der Steuerberater");
        assertThat(result.analysis().keyFacts()).isEmpty();
    }

    @Test
    void reportsTruncationWhenNothingUsableArrived() {
        String cutInSummary = COMPLETE.substring(0, COMPLETE.indexOf("rechnet"));
        respond(200, reply("length", null, "Nachdenken ohne Ende."));
        respond(200, reply("length", cutInSummary, "Nachdenken ohne Ende."));

        assertThatThrownBy(() -> service.analyze(textDocument()))
                .isInstanceOfSatisfying(AiException.class,
                        e -> assertThat(e.msg().key()).isEqualTo("ai.error.truncated"));
        assertThat(requestedLimits).containsExactly(16000L, 32000L);
    }

    @Test
    void rescuesOnlyAnswersWhoseCoreIsComplete() {
        assertThat(AiAnalysisService.rescue(COMPLETE.substring(0, COMPLETE.indexOf("\"keyFacts\"")))).isNotNull();
        // mitten in den Schlagworten: das angefangene entfällt, die Liste davor genügt
        DocumentAnalysis cutInTags = AiAnalysisService.rescue(COMPLETE.substring(0, COMPLETE.indexOf("\"rechnung\"]") + 4));
        assertThat(cutInTags.tags()).containsExactly("steuerberatung");
        // ein angefangener Volltext wäre nur ein Bruchstück
        assertThat(AiAnalysisService.rescue(COMPLETE.replace("\"fullText\":\"\"}", "\"fullText\":\"Sehr geehrte Dam"))
                .fullText()).isEmpty();
        assertThat(AiAnalysisService.rescue(COMPLETE.substring(0, COMPLETE.indexOf("\"category\"")))).isNull();
        assertThat(AiAnalysisService.rescue("")).isNull();
        assertThat(AiAnalysisService.rescue("Ich überlege noch")).isNull();
    }

    private void respond(int status, String body) {
        responses.add(new Object[] {status, body});
    }

    /** Eine Antwort, wie sie ein llama.cpp-Server liefert: Nachdenken getrennt vom Ergebnis. */
    private static String reply(String finishReason, String toolArguments, String reasoning) {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode choice = root.putArray("choices").addObject();
        choice.put("finish_reason", finishReason);
        ObjectNode message = choice.putObject("message").put("role", "assistant").put("content", "");
        message.put("reasoning_content", reasoning);
        if (toolArguments != null) {
            message.putArray("tool_calls").addObject().put("type", "function").putObject("function")
                    .put("name", "dokument_erschliessen").put("arguments", toolArguments);
        }
        root.putObject("usage").put("completion_tokens", 1234);
        return root.toString();
    }

    private static AiAnalysisService.Request textDocument() {
        return new AiAnalysisService.Request(Path.of("rechnung.pdf"), "rechnung.pdf", 2048, 1,
                "Muster Steuerberatung GmbH – Rechnung 2024/4711 vom 31.12.2024 über 482,17 EUR.", false,
                null, null, List.of(), List.of());
    }
}
