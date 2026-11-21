package de.aidocuments.i18n;

/**
 * Meldungen der Eingabeprüfung (Bean Validation), die an vielen Feldern gleich lauten. Jede Prüfung nennt
 * ihre Meldung ausdrücklich: Die mitgelieferten Standardtexte der Bibliothek gibt es nicht in allen Sprachen,
 * und wo einer fehlt, fiele sie auf die Sprache des Servers zurück.
 */
public final class ValidationMessages {

    public static final String NOT_BLANK = "{validation.notBlank}";
    public static final String NOT_NULL = "{validation.notNull}";
    public static final String SIZE = "{validation.size}";
    public static final String MIN = "{validation.min}";
    public static final String MAX = "{validation.max}";

    private ValidationMessages() {
    }
}
