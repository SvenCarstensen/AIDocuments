package de.aidocuments.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Prüft Anfrageform und Antwortauswertung der OpenAI-Schnittstelle gegen einen kleinen Stellvertreter-Server. */
class OpenAiChatClientTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<String> receivedAuthorization = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String response = "{}";
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsDocumentPartsAndToolAndReadsTheToolCall() throws Exception {
        response = """
                {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
                 "tool_calls":[{"type":"function","function":{"name":"dokument_erschliessen",
                   "arguments":"{\\"title\\":\\"Stromrechnung\\",\\"tags\\":[\\"strom\\"]}"}}]}}]}""";

        OpenAiChatClient.Reply reply = new OpenAiChatClient(baseUrl + "/", "geheim").complete(
                "modell-x", "Systemanweisung",
                List.of(OpenAiChatClient.Part.image(new byte[] {1, 2, 3}), OpenAiChatClient.Part.text("Aufgabe")),
                "dokument_erschliessen", "Speichert das Ergebnis", AiAnalysisService.resultSchema(), 8000,
                "max_tokens", TIMEOUT);

        assertThat(reply.toolArguments()).contains("Stromrechnung");
        assertThat(reply.truncated()).isFalse();
        assertThat(LenientAnalysisParser.parseText(reply.toolArguments()).tags()).containsExactly("strom");

        assertThat(receivedAuthorization.get()).isEqualTo("Bearer geheim");
        JsonNode request = new ObjectMapper().readTree(receivedBody.get());
        assertThat(request.path("model").asText()).isEqualTo("modell-x");
        assertThat(request.path("max_tokens").asLong()).isEqualTo(8000);
        assertThat(request.path("messages").path(0).path("role").asText()).isEqualTo("system");
        JsonNode userContent = request.path("messages").path(1).path("content");
        assertThat(userContent.path(0).path("image_url").path("url").asText()).isEqualTo("data:image/jpeg;base64,AQID");
        assertThat(userContent.path(1).path("text").asText()).isEqualTo("Aufgabe");
        JsonNode function = request.path("tools").path(0).path("function");
        assertThat(function.path("name").asText()).isEqualTo("dokument_erschliessen");
        assertThat(function.path("parameters").path("required")).hasSize(9);
        assertThat(function.path("parameters").path("properties").path("keyFacts").path("items").path("required"))
                .hasSize(2);
        assertThat(request.path("tool_choice").asText()).isEqualTo("auto");
    }

    @Test
    void reportsTextAnswersTruncationAndRefusals() {
        response = """
                {"choices":[{"finish_reason":"length","message":{"content":[{"type":"text","text":"{\\"title\\":"}]}}]}""";
        OpenAiChatClient.Reply cut = new OpenAiChatClient(baseUrl, "").complete("m", "s",
                List.of(OpenAiChatClient.Part.text("a")), "t", "d", AiAnalysisService.resultSchema(), 10,
                "max_completion_tokens", TIMEOUT);
        assertThat(cut.toolArguments()).isNull();
        assertThat(cut.text()).isEqualTo("{\"title\":");
        assertThat(cut.truncated()).isTrue();
        assertThat(receivedBody.get()).contains("\"max_completion_tokens\":10").doesNotContain("\"max_tokens\"");
        // ohne Schlüssel wird auch kein leerer Authorization-Header geschickt
        assertThat(receivedAuthorization.get()).isNull();

        response = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":null,\"refusal\":\"Nein.\"}}]}";
        assertThat(new OpenAiChatClient(baseUrl, "").complete("m", "s", List.of(OpenAiChatClient.Part.text("a")),
                "t", "d", AiAnalysisService.resultSchema(), 10, "max_tokens", TIMEOUT).refusal()).isEqualTo("Nein.");
    }

    @Test
    void listsModelsAndTranslatesErrors() {
        response = "{\"object\":\"list\",\"data\":[{\"id\":\"modell-b\"},{\"id\":\"modell-a\"}]}";
        assertThat(new OpenAiChatClient(baseUrl, "k").listModels(TIMEOUT)).containsExactly("modell-b", "modell-a");

        status = 401;
        response = "{\"error\":{\"message\":\"Incorrect API key provided\",\"type\":\"invalid_request_error\"}}";
        assertThatThrownBy(() -> new OpenAiChatClient(baseUrl, "falsch").listModels(TIMEOUT))
                .isInstanceOfSatisfying(OpenAiChatClient.StatusException.class, e -> {
                    assertThat(e.status()).isEqualTo(401);
                    assertThat(e).hasMessage("Incorrect API key provided");
                });

        server.stop(0);
        assertThatThrownBy(() -> new OpenAiChatClient(baseUrl, "k").listModels(TIMEOUT))
                .isInstanceOfSatisfying(OpenAiChatClient.ConnectionException.class,
                        e -> assertThat(e.timeout()).isFalse());
    }

    @Test
    void oversizedAnswersAreNotReadIntoMemory() {
        response = "{\"object\":\"list\",\"data\":[" + "{\"id\":\"m\"},".repeat(100) + "{\"id\":\"z\"}]}";
        // zu groß: wie eine unbrauchbare Antwort
        assertThatThrownBy(() -> new OpenAiChatClient(baseUrl, "k", 200).listModels(TIMEOUT))
                .isInstanceOf(OpenAiChatClient.NotJsonException.class);
        assertThat(new OpenAiChatClient(baseUrl, "k", 10_000).listModels(TIMEOUT)).hasSize(101);
        // auch eine riesige Fehlerantwort wird nur angelesen
        status = 500;
        response = "x".repeat(2 * 1024 * 1024);
        assertThatThrownBy(() -> new OpenAiChatClient(baseUrl, "k").listModels(TIMEOUT))
                .isInstanceOfSatisfying(OpenAiChatClient.StatusException.class, e -> {
                    assertThat(e.status()).isEqualTo(500);
                    assertThat(e.getMessage().length()).isLessThanOrEqualTo(OpenAiChatClient.MAX_ERROR_BYTES);
                });
    }

    @Test
    void redirectsAreNotFollowed() {
        AtomicReference<String> forwarded = new AtomicReference<>();
        server.createContext("/umgeleitet", exchange -> {
            forwarded.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/weiter", exchange -> {
            exchange.getResponseHeaders().add("Location", baseUrl.replace("/v1", "/umgeleitet"));
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        String redirecting = baseUrl.replace("/v1", "/weiter");
        assertThatThrownBy(() -> new OpenAiChatClient(redirecting, "geheim").listModels(TIMEOUT))
                .isInstanceOfSatisfying(OpenAiChatClient.StatusException.class,
                        e -> assertThat(e.status()).isEqualTo(307));
        assertThat(forwarded.get()).isNull();
    }
}
