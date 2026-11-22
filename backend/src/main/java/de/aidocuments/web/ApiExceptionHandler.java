package de.aidocuments.web;

import de.aidocuments.i18n.Localizable;
import de.aidocuments.i18n.Messages;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Macht aus Fehlern Antworten nach RFC 7807, deren {@code detail} dem Anwender angezeigt wird – in der Sprache
 * der Anfrage.
 *
 * <p>Die Reihenfolge ist wesentlich: Spring Boot bringt (spring.mvc.problemdetails) einen eigenen Handler mit
 * {@code @Order(0)} mit, der Eingabefehler und zu große Uploads ebenfalls behandelt – mit festen englischen
 * Sätzen („Invalid request content.“). Ohne Vorrang käme dieser hier für beides nie zum Zug.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final Messages messages;

    public ApiExceptionHandler(Messages messages) {
        this.messages = messages;
    }

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApi(ApiException e) {
        return ProblemDetail.forStatusAndDetail(e.getStatus(), messages.render(e.msg()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        // Die Meldungen der einzelnen Prüfungen sind hier schon übersetzt (Bean Validation liest dieselben
        // messages.properties in der Sprache der Anfrage).
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                detail.isBlank() ? messages.get("error.validation.invalid") : detail);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail handleTooLarge(MaxUploadSizeExceededException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE,
                messages.get("error.upload.tooLarge"));
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail handleIllegalState(IllegalStateException e) {
        log.error("Interner Fehler", e);
        // interne Meldungen (Pfade, Klassennamen) gehen nur ins Protokoll, nicht an den Browser
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                e instanceof Localizable localizable ? messages.render(localizable.msg())
                        : messages.get("error.internal"));
    }
}
