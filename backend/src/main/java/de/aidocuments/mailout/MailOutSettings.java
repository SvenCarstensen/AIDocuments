package de.aidocuments.mailout;

import de.aidocuments.mail.MailSecurity;

/**
 * Versand von E-Mails (Erinnerungen) über einen SMTP-Server – Datensatz "mail-out" der Tabelle settings. Das Passwort
 * liegt verschlüsselt (SecretCipher) und wird nie ausgeliefert.
 *
 * @param appUrl     Adresse, unter der die Anwendung erreichbar ist, für Links in den E-Mails; leer = keine Links
 * @param digestHour ab dieser Stunde (Ortszeit des Servers) gehen die Erinnerungen eines Tages hinaus
 */
public record MailOutSettings(boolean enabled, String host, int port, MailSecurity security, String username,
                              String passwordEncrypted, String from, String appUrl, int digestHour) {

    public static final String ID = "mail-out";

    static MailOutSettings defaults() {
        return new MailOutSettings(false, "", 587, MailSecurity.STARTTLS, "", null, "", "", 7);
    }

    boolean complete() {
        return host != null && !host.isBlank() && from != null && !from.isBlank();
    }
}
