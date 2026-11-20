package de.aidocuments.ai;

/** Die KI-Analyse ist gescheitert; die Meldung ist für den Anwender formuliert. */
public class AiException extends Exception {

    public AiException(String message) {
        super(message);
    }

    public AiException(String message, Throwable cause) {
        super(message, cause);
    }
}
