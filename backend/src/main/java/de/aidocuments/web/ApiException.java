package de.aidocuments.web;

import de.aidocuments.i18n.Localizable;
import de.aidocuments.i18n.Msg;
import org.springframework.http.HttpStatus;

/**
 * Fachlicher Fehler mit HTTP-Status. Die Meldung ist ein Schlüssel aus messages.properties samt Argumenten;
 * übersetzt wird sie erst beim Antworten, in der Sprache der Anfrage (siehe {@link ApiExceptionHandler}).
 */
public class ApiException extends RuntimeException implements Localizable {

    private final HttpStatus status;
    private final Msg msg;

    public ApiException(HttpStatus status, Msg msg) {
        super(msg.toString());
        this.status = status;
        this.msg = msg;
    }

    public HttpStatus getStatus() {
        return status;
    }

    @Override
    public Msg msg() {
        return msg;
    }

    public static ApiException of(HttpStatus status, String key, Object... args) {
        return new ApiException(status, Msg.of(key, args));
    }

    public static ApiException notFound(String key, Object... args) {
        return of(HttpStatus.NOT_FOUND, key, args);
    }

    public static ApiException badRequest(String key, Object... args) {
        return of(HttpStatus.BAD_REQUEST, key, args);
    }

    public static ApiException conflict(String key, Object... args) {
        return of(HttpStatus.CONFLICT, key, args);
    }
}
