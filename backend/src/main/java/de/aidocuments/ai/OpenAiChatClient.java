package de.aidocuments.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Schlanker Client für die OpenAI-Schnittstelle (Chat Completions) – gesprochen von OpenAI, Moonshot,
 * LM Studio und vielen selbst betriebenen Servern. Bewusst ohne weiteres SDK: Gebraucht werden genau
 * zwei Aufrufe, und die Server unterscheiden sich in Details, die sich hier an einer Stelle abfangen lassen.
 */
final class OpenAiChatClient {

    /** Ein Baustein der Nutzernachricht: Text oder ein JPEG-Bild. */
    record Part(String text, byte[] jpeg) {

        static Part text(String text) {
            return new Part(text, null);
        }

        static Part image(byte[] jpeg) {
            return new Part(null, jpeg);
        }
    }

    /**
     * Antwort des Modells: die Argumente des Werkzeugaufrufs (JSON) und/oder freier Text.
     *
     * @param truncated        der Server hat die Antwort am Token-Limit abgeschnitten
     * @param completionTokens erzeugte Token laut Server, {@code -1} ohne Angabe. Bei Modellen, die vor dem
     *                         Antworten „nachdenken“, zählt das Nachdenken mit – und gegen das Limit.
     * @param reasoningChars   Länge des mitgelieferten Nachdenkens in Zeichen (0, wenn der Server keines liefert)
     */
    record Reply(String toolArguments, String text, boolean truncated, String refusal, long completionTokens,
                 int reasoningChars) {
    }

    /** Der Server hat mit einem Fehlerstatus geantwortet. */
    static final class StatusException extends RuntimeException {
        private final int status;

        StatusException(int status, String message) {
            super(message);
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    /** Der Server hat geantwortet, aber nicht mit JSON – meist stimmt dann die Adresse nicht. */
    static final class NotJsonException extends RuntimeException {
        NotJsonException(Throwable cause) {
            super("Antwort ist kein JSON", cause);
        }
    }

    /** Der Server war nicht erreichbar oder hat nicht rechtzeitig geantwortet. */
    static final class ConnectionException extends RuntimeException {
        private final boolean timeout;

        ConnectionException(String message, boolean timeout, Throwable cause) {
            super(message, cause);
            this.timeout = timeout;
        }

        boolean timeout() {
            return timeout;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final String baseUrl;
    private final String apiKey;

    OpenAiChatClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
    }

    /** IDs der Modelle, die der Server anbietet. */
    List<String> listModels(Duration timeout) {
        JsonNode body = send(HttpRequest.newBuilder(URI.create(baseUrl + "/models")).GET(), timeout);
        List<String> ids = new ArrayList<>();
        JsonNode data = body.has("data") ? body.get("data") : body;
        if (data.isArray()) {
            data.forEach(model -> {
                if (model.hasNonNull("id")) {
                    ids.add(model.get("id").asText());
                }
            });
        }
        return ids;
    }

    /**
     * Stellt eine Anfrage mit genau einem Werkzeug, über das das Modell sein Ergebnis abliefern soll.
     *
     * @param tokenLimitField "max_completion_tokens" (OpenAI selbst verlangt das bei neueren Modellen) oder
     *                        "max_tokens" (alle übrigen Server)
     */
    Reply complete(String model, String system, List<Part> userParts, String toolName, String toolDescription,
                   Map<String, Object> toolSchema, long maxTokens, String tokenLimitField, Duration timeout) {
        ObjectNode request = JSON.createObjectNode();
        request.put("model", model);
        request.put(tokenLimitField, maxTokens);

        ArrayNode messages = request.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);
        ArrayNode content = messages.addObject().put("role", "user").putArray("content");
        for (Part part : userParts) {
            if (part.jpeg() != null) {
                content.addObject().put("type", "image_url").putObject("image_url")
                        .put("url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(part.jpeg()));
            } else {
                content.addObject().put("type", "text").put("text", part.text());
            }
        }

        ObjectNode function = request.putArray("tools").addObject().put("type", "function").putObject("function");
        function.put("name", toolName);
        function.put("description", toolDescription);
        function.set("parameters", JSON.valueToTree(toolSchema));
        request.put("tool_choice", "auto");

        JsonNode body = send(HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request.toString(), StandardCharsets.UTF_8)), timeout);

        JsonNode choice = body.path("choices").path(0);
        JsonNode message = choice.path("message");
        String arguments = null;
        for (JsonNode call : message.path("tool_calls")) {
            JsonNode fn = call.path("function");
            if (toolName.equals(fn.path("name").asText()) && fn.hasNonNull("arguments")) {
                // laut Schnittstelle ein JSON-Text; manche Server liefern gleich das Objekt
                arguments = fn.get("arguments").isTextual() ? fn.get("arguments").asText() : fn.get("arguments").toString();
                break;
            }
        }
        return new Reply(
                arguments,
                textOf(message.path("content")),
                "length".equals(choice.path("finish_reason").asText()),
                message.hasNonNull("refusal") ? message.get("refusal").asText() : null,
                body.path("usage").path("completion_tokens").asLong(-1),
                reasoningOf(message).length());
    }

    /** Das Nachdenken heißt je nach Server "reasoning_content" (llama.cpp, vLLM, DeepSeek) oder "reasoning". */
    private static String reasoningOf(JsonNode message) {
        String reasoning = textOf(message.path("reasoning_content"));
        return reasoning.isEmpty() ? textOf(message.path("reasoning")) : reasoning;
    }

    /** "content" ist meist ein Text, bei manchen Servern eine Liste von Textbausteinen. */
    private static String textOf(JsonNode content) {
        if (content.isTextual()) {
            return content.asText();
        }
        StringBuilder text = new StringBuilder();
        if (content.isArray()) {
            content.forEach(part -> text.append(part.path("text").asText("")));
        }
        return text.toString();
    }

    private JsonNode send(HttpRequest.Builder builder, Duration timeout) {
        builder.timeout(timeout).header("Accept", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new ConnectionException("Zeitüberschreitung", true, e);
        } catch (IOException e) {
            throw new ConnectionException(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                    false, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectionException("unterbrochen", false, e);
        }
        if (response.statusCode() >= 400) {
            throw new StatusException(response.statusCode(), errorMessage(response.body()));
        }
        try {
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new NotJsonException(e);
        }
    }

    /** {"error": {"message": "…"}} oder {"error": "…"} – sonst der Anfang des Rohtexts. */
    private static String errorMessage(String body) {
        try {
            JsonNode error = JSON.readTree(body).path("error");
            if (error.isTextual()) {
                return error.asText();
            }
            if (error.hasNonNull("message")) {
                return error.get("message").asText();
            }
        } catch (IOException ignored) {
            // kein JSON
        }
        String text = body == null ? "" : body.strip();
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }
}
