package de.aidocuments.mail;

public enum MailSecurity {
    /** Verschlüsselte Verbindung von Anfang an (IMAPS, üblich auf Port 993) */
    SSL,
    /** Unverschlüsselt beginnen und per STARTTLS absichern (üblich auf Port 143) */
    STARTTLS,
    /** Unverschlüsselt – nur in vertrauenswürdigen Netzen */
    NONE
}
