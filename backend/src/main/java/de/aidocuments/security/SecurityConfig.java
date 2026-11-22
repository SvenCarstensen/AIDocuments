package de.aidocuments.security;

import de.aidocuments.i18n.Messages;
import de.aidocuments.user.UserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.servlet.LocaleResolver;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Nur eigene Skripte, keine fremden Quellen: Selbst wenn es jemand schafft, HTML in eine Seite zu schleusen
     * (etwa über ein präpariertes PDF), führt der Browser kein eingeschleustes Skript aus. Der PDF-Betrachter braucht
     * WebAssembly (Bilddekoder, Farbprofile), Inline-Styles und Bilder/Schriften aus data:/blob:-Adressen.
     */
    static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self' 'wasm-unsafe-eval'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data: blob:",
            "font-src 'self' data: blob:",
            "connect-src 'self' data: blob:",
            "worker-src 'self' blob:",
            "frame-src 'self'",
            "media-src 'none'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'self'");

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, @Lazy UserService userService,
                                            CsrfTokenRepository csrfTokens, Messages messages,
                                            LocaleResolver localeResolver) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/status", "/api/auth/login", "/api/auth/login/two-factor", "/api/auth/setup")
                        .permitAll()
                        // geteilte Dokumente: der Link selbst ist die Berechtigung (siehe PublicShareController)
                        .requestMatchers("/api/share/**").permitAll()
                        // Verwaltung: nur Administratoren
                        .requestMatchers("/api/users/**", "/api/mail-accounts/**", "/api/settings/**")
                        .hasRole("ADMIN")
                        // einen Link entziehen darf jeder Angemeldete, Dokumente löschen nur Administratoren
                        .requestMatchers(HttpMethod.DELETE, "/api/documents/*/shares/*").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/documents/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/documents/*/reprocess",
                                "/api/documents/reprocess-unanalyzed").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        // alles andere ist die ausgelieferte Oberfläche (statische Dateien)
                        .anyRequest().permitAll())
                // Cookie XSRF-TOKEN + Header X-XSRF-TOKEN, passend für eine Single-Page-Anwendung
                .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokens))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .headers(headers -> headers
                        // die PDF-Ansicht bettet /pdfjs/web/viewer.html in einen iframe derselben Herkunft ein
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin)
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.SAME_ORIGIN))
                        .permissionsPolicyHeader(permissions -> permissions.policy(
                                "camera=(), microphone=(), geolocation=(), payment=(), usb=()"))
                        .crossOriginOpenerPolicy(coop -> coop.policy(
                                CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy.SAME_ORIGIN)))
                .addFilterBefore(new SessionValidationFilter(userService), AuthorizationFilter.class)
                .addFilterAfter(new AdminTwoFactorFilter(messages, localeResolver), SessionValidationFilter.class);
        return http.build();
    }

    /** Cookie XSRF-TOKEN, lesbar für die Oberfläche; auch die Anmeldung erneuert darüber das Token. */
    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        return CookieCsrfTokenRepository.withHttpOnlyFalse();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }
}
