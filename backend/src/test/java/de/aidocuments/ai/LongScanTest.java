package de.aidocuments.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import de.aidocuments.document.PdfExtractor;
import de.aidocuments.i18n.Msg;
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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lange Scans im Kompatibilitätsmodus: Die KI bekommt nicht nur die ersten Seiten zu sehen, sondern alle – blockweise
 * abgeschrieben, dann anhand des ganzen Texts erschlossen.
 */
class LongScanTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ANALYSIS = """
            {"title":"Mietvertrag Musterstraße 1","summary":"Mietvertrag über eine Wohnung mit Anlagen.",\
            "category":"Vertrag","correspondent":"Muster Hausverwaltung","documentDate":"2025-02-01","language":"de",\
            "tags":["mietvertrag","wohnung"],"keyFacts":[{"label":"Kaltmiete","value":"850,00 EUR"}],"fullText":""}""";

    @TempDir
    Path dir;

    private final Deque<String> responses = new ArrayDeque<>();
    private final List<JsonNode> requests = new ArrayList<>();
    private HttpServer server;
    private AiAnalysisService service;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] bytes = responses.removeFirst().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
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
    void everyPageOfALongScanIsReadInBlocksAndTheAnalysisSeesAllOfIt() throws Exception {
        Path scan = scan(10);
        responses.add(reply("tool_calls", "seiten_abschreiben", "{\"text\":\"Mietvertrag Seite 1 bis 8\"}"));
        // die Seiten 9 und 10 passen nicht in eine Antwort: der Block wird geteilt
        responses.add(reply("length", "seiten_abschreiben", "{\"text\":\"Anlage A Hausordn"));
        responses.add(reply("tool_calls", "seiten_abschreiben", "{\"text\":\"Anlage A: Hausordnung\"}"));
        // auch die letzte Seite allein ist zu lang – es bleibt, was ankam
        responses.add(reply("length", "seiten_abschreiben", "{\"text\":\"Anlage B: \\u00dcbergabeprotokoll, Zähler"));
        responses.add(reply("tool_calls", "dokument_erschliessen", ANALYSIS));

        AiAnalysisService.Result result = service.analyze(new AiAnalysisService.Request(scan, "mietvertrag.pdf",
                4096, 10, "", true, null, null, List.of(), List.of()));

        assertThat(requests).hasSize(5);
        assertThat(requests.stream().map(LongScanTest::images).toList()).containsExactly(8, 2, 1, 1, 1);
        assertThat(requests.subList(0, 4)).allSatisfy(request ->
                assertThat(request.at("/tools/0/function/name").asText()).isEqualTo("seiten_abschreiben"));
        assertThat(text(requests.get(0))).contains("Seiten 1 bis 8 von 10");
        assertThat(text(requests.get(3))).contains("Seite 10 von 10");

        // die Erschließung bekommt den Text aller Seiten
        String analysisRequest = text(requests.get(4));
        assertThat(requests.get(4).at("/tools/0/function/name").asText()).isEqualTo("dokument_erschliessen");
        assertThat(analysisRequest).contains("Scan mit 10 Seiten", "Mietvertrag Seite 1 bis 8",
                "Anlage A: Hausordnung", "Anlage B: Übergabeprotokoll, Zähler");

        assertThat(result.analysis().title()).isEqualTo("Mietvertrag Musterstraße 1");
        assertThat(result.transcribed()).isTrue();
        assertThat(result.analysis().fullText()).isEqualTo(
                "Mietvertrag Seite 1 bis 8\n\nAnlage A: Hausordnung\n\nAnlage B: Übergabeprotokoll, Zähler");
        assertThat(result.notes()).extracting(Msg::key).containsExactly("ai.note.scanTooLarge");
    }

    @Test
    void shortScansStillGoInOneRequest() throws Exception {
        Path scan = scan(8);
        responses.add(reply("tool_calls", "dokument_erschliessen",
                ANALYSIS.replace("\"fullText\":\"\"", "\"fullText\":\"Mietvertrag\"")));

        AiAnalysisService.Result result = service.analyze(new AiAnalysisService.Request(scan, "mietvertrag.pdf",
                4096, 8, "", true, null, null, List.of(), List.of()));

        assertThat(requests).hasSize(1);
        assertThat(images(requests.getFirst())).isEqualTo(8);
        assertThat(text(requests.getFirst())).contains("alle 8 Seiten");
        assertThat(result.notes()).isEmpty();
    }

    @Test
    void transcriptsAreTakenFromToolCallsJsonOrPlainText() {
        assertThat(AiAnalysisService.transcriptOf("{\"text\":\"Seite eins\"}", "")).isEqualTo("Seite eins");
        assertThat(AiAnalysisService.transcriptOf(null, "{\"text\": \"aus dem Text\"}")).isEqualTo("aus dem Text");
        assertThat(AiAnalysisService.transcriptOf(null, "Einfach die Abschrift.")).isEqualTo("Einfach die Abschrift.");
        assertThat(AiAnalysisService.transcriptOf(null, "")).isNull();
        assertThat(AiAnalysisService.transcriptOf("{kaputt", "")).isNull();
        assertThat(AiAnalysisService.partialTranscript("{\"text\": \"Zeile 1\\nZeile \\\"2\\\" \\u00e")).isEqualTo(
                "Zeile 1\nZeile \"2\"");
        assertThat(AiAnalysisService.partialTranscript(null)).isEmpty();
    }

    private Path scan(int pages) throws Exception {
        Path file = dir.resolve("scan-" + pages + ".pdf");
        try (PDDocument pdf = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                pdf.addPage(new PDPage(PDRectangle.A6));
            }
            pdf.save(file.toFile());
        }
        return file;
    }

    private static int images(JsonNode request) {
        int count = 0;
        for (JsonNode part : request.at("/messages/1/content")) {
            if ("image_url".equals(part.path("type").asText())) {
                count++;
            }
        }
        return count;
    }

    private static String text(JsonNode request) {
        StringBuilder text = new StringBuilder();
        for (JsonNode part : request.at("/messages/1/content")) {
            text.append(part.path("text").asText()).append('\n');
        }
        return text.toString();
    }

    private static String reply(String finishReason, String tool, String arguments) {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode choice = root.putArray("choices").addObject();
        choice.put("finish_reason", finishReason);
        ObjectNode message = choice.putObject("message").put("role", "assistant").put("content", "");
        message.putArray("tool_calls").addObject().put("type", "function").putObject("function")
                .put("name", tool).put("arguments", arguments);
        root.putObject("usage").put("completion_tokens", 1234);
        return root.toString();
    }
}
