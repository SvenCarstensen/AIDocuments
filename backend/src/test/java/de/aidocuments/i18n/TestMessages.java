package de.aidocuments.i18n;

import org.springframework.context.support.ResourceBundleMessageSource;

/** Die Texte der Anwendung für Tests ohne Spring-Kontext – eingerichtet wie in application.yml. */
public final class TestMessages {

    private TestMessages() {
    }

    public static Messages create() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return new Messages(source);
    }
}
