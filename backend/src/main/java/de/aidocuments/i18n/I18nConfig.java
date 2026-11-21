package de.aidocuments.i18n;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;

@Configuration
public class I18nConfig {

    /** Ersetzt den Standard von Spring MVC, der ohne passenden Header auf die Sprache des Servers zurückfiele. */
    @Bean
    public LocaleResolver localeResolver(@Value("${spring.messages.basename:messages}") String basename) {
        return AcceptLanguageLocaleResolver.forBundles(basename, I18nConfig.class.getClassLoader());
    }
}
