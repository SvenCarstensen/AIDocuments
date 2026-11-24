package de.aidocuments.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.models.ModelListParams;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Der Anthropic-Client folgt keiner Weiterleitung – sonst ginge der Schlüssel („x-api-key“) mit. */
class NoRedirectsTest {

    @Test
    void theApiKeyIsNotForwardedToAnotherAddress() throws Exception {
        AtomicReference<String> forwardedKey = new AtomicReference<>();
        HttpServer elsewhere = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        elsewhere.createContext("/", exchange -> {
            forwardedKey.set(String.valueOf(exchange.getRequestHeaders().getFirst("x-api-key")));
            byte[] body = "{\"data\":[],\"has_more\":false}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        HttpServer configured = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        configured.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Location",
                    "http://localhost:" + elsewhere.getAddress().getPort() + exchange.getRequestURI());
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        elsewhere.start();
        configured.start();
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .baseUrl("http://127.0.0.1:" + configured.getAddress().getPort())
                .apiKey("sk-ant-geheim")
                .timeout(Duration.ofSeconds(5))
                .maxRetries(0)
                .addInterceptor(NoRedirects.INSTANCE)
                .build();
        try {
            assertThatThrownBy(() -> client.models().list(ModelListParams.none()))
                    .isInstanceOf(RuntimeException.class);
            assertThat(forwardedKey.get()).isNull();
        } finally {
            client.close();
            configured.stop(0);
            elsewhere.stop(0);
        }
    }
}
