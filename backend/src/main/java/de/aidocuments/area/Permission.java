package de.aidocuments.area;

/** Was jemand mit den Dokumenten eines Bereichs darf; jede Stufe schließt die vorigen ein. */
public enum Permission {
    /** ansehen, suchen, herunterladen, Fragen stellen, exportieren, Wiedervorlagen */
    READ,
    /** zusätzlich: hochladen, Angaben und PDF ändern, Fassungen, Teilen-Links, Verträge */
    EDIT,
    /** zusätzlich: in den Papierkorb legen und daraus zurückholen */
    DELETE;

    public boolean allows(Permission needed) {
        return ordinal() >= needed.ordinal();
    }

    public static Permission max(Permission a, Permission b) {
        return a == null ? b : b == null ? a : a.ordinal() >= b.ordinal() ? a : b;
    }

    /** Aus der Datenbank oder einer Anfrage; Unbekanntes als die Vorgabe EDIT. */
    public static Permission parse(String value) {
        if (value == null) {
            return EDIT;
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            return EDIT;
        }
    }
}
