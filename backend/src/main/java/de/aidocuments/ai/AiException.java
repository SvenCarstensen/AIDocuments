package de.aidocuments.ai;

import de.aidocuments.i18n.Localizable;
import de.aidocuments.i18n.Msg;

/**
 * Die KI-Analyse ist gescheitert. Die Meldung für den Anwender ist sprachneutral (Schlüssel und Argumente):
 * Derselbe Fehler wird beim Verbindungstest sofort in der Sprache der Anfrage angezeigt und bei der Analyse
 * im Hintergrund am Dokument gespeichert – übersetzt erst für den, der es später öffnet.
 */
public class AiException extends Exception implements Localizable {

    private final Msg msg;

    public AiException(Msg msg) {
        super(msg.toString());
        this.msg = msg;
    }

    public AiException(Msg msg, Throwable cause) {
        super(msg.toString(), cause);
        this.msg = msg;
    }

    @Override
    public Msg msg() {
        return msg;
    }
}
