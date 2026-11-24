package de.aidocuments.ai;

import com.anthropic.core.RequestOptions;
import com.anthropic.core.http.HttpClient;
import com.anthropic.core.http.HttpRequest;
import com.anthropic.core.http.HttpResponse;
import com.anthropic.core.http.Interceptor;
import java.util.concurrent.CompletableFuture;

/**
 * Für den Anthropic-Client: keinen Weiterleitungen folgen. OkHttp nimmt bei einer Weiterleitung auf einen anderen
 * Rechner nur den Kopf „Authorization“ heraus – „x-api-key“ ginge mit. Ein Server unter der eingetragenen Adresse
 * (oder jemand dazwischen) könnte den Schlüssel so an eine beliebige Adresse umlenken. Eine Weiterleitung ergibt
 * stattdessen eine Fehlerantwort.
 *
 * <p>Das SDK kennt dafür keine Einstellung am Client, wohl aber je Anfrage ({@link RequestOptions#followRedirects()});
 * dieser Interceptor setzt sie für jede Anfrage des Clients.
 */
final class NoRedirects implements Interceptor {

    static final NoRedirects INSTANCE = new NoRedirects();

    private NoRedirects() {
    }

    @Override
    public HttpClient intercept(HttpClient delegate) {
        return new HttpClient() {
            @Override
            public HttpResponse execute(HttpRequest request, RequestOptions options) {
                return delegate.execute(request, withoutRedirects(options));
            }

            @Override
            public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions options) {
                return delegate.executeAsync(request, withoutRedirects(options));
            }

            @Override
            public void close() {
                delegate.close();
            }
        };
    }

    static RequestOptions withoutRedirects(RequestOptions options) {
        return options.toBuilder().followRedirects(false).build();
    }
}
