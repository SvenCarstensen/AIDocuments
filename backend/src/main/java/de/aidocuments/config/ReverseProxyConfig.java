package de.aidocuments.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.catalina.valves.RemoteIpValve;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Betrieb hinter einem Reverse-Proxy (HTTPS): Nur von ausdrücklich eingetragenen Proxys werden X-Forwarded-For und
 * X-Forwarded-Proto übernommen. Dann gelten Cookies als „Secure“, HSTS wird gesendet, und die Anmeldesperre sieht die
 * IP-Adresse des Browsers.
 *
 * <p>Ohne Eintrag werden die Kopfzeilen ignoriert – sonst könnte jeder, dessen Verbindung (etwa über die
 * Portweiterleitung von Docker) aus einem privaten Netz zu kommen scheint, sich per X-Forwarded-For bei jedem Versuch
 * eine neue IP-Adresse geben und so die Anmeldesperre umgehen.
 *
 * <p>{@code AIDOCS_TRUSTED_PROXIES}: leer = keinem Proxy vertrauen; {@code private} = allen Adressen aus privaten
 * Netzen (10.x, 172.16–31.x, 192.168.x, localhost …) – passend, wenn der Proxy im selben Docker-Netz läuft und Port
 * 8080 nicht veröffentlicht ist; sonst eine durch Kommas getrennte Liste von IP-Adressen.
 */
@Configuration
public class ReverseProxyConfig {

    private static final Logger log = LoggerFactory.getLogger(ReverseProxyConfig.class);

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> trustedProxies(
            @Value("${app.trusted-proxies:}") String trustedProxies) {
        return factory -> {
            String value = trustedProxies.strip();
            if (value.isEmpty()) {
                return;
            }
            RemoteIpValve valve = new RemoteIpValve();
            valve.setRemoteIpHeader("X-Forwarded-For");
            valve.setProtocolHeader("X-Forwarded-Proto");
            valve.setHostHeader("X-Forwarded-Host");
            valve.setPortHeader("X-Forwarded-Port");
            if (!"private".equalsIgnoreCase(value)) {
                valve.setInternalProxies(regexFor(value));
            }
            factory.addEngineValves(valve);
            log.info("Reverse-Proxy: X-Forwarded-* wird übernommen von {}", value);
        };
    }

    /**
     * Liste von IP-Adressen → regulärer Ausdruck, der genau diese Adressen trifft (IPv6 in der Schreibweise, in der
     * Tomcat die Gegenstelle meldet). Netzbereiche (CIDR) und Rechnernamen werden nicht unterstützt – statt sie
     * stillschweigend nie zu treffen, startet die Anwendung dann nicht.
     */
    static String regexFor(String addresses) {
        return Arrays.stream(addresses.split("[,\\s]+"))
                .map(String::strip)
                .filter(address -> !address.isEmpty())
                .map(ReverseProxyConfig::canonical)
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
    }

    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");

    private static String canonical(String address) {
        if (IPV4.matcher(address).matches()) {
            return address;
        }
        if (address.contains(":") && !address.contains("/")) {
            try {
                // ein IPv6-Literal löst getByName ohne DNS-Anfrage auf
                return java.net.InetAddress.getByName(address.replaceAll("^\\[|]$", "")).getHostAddress();
            } catch (java.net.UnknownHostException e) {
                // fällt unten in die Fehlermeldung
            }
        }
        throw new IllegalStateException("AIDOCS_TRUSTED_PROXIES: \"" + address + "\" ist keine IP-Adresse. Erlaubt sind "
                + "\"private\" oder einzelne IP-Adressen, durch Kommas getrennt (keine Netzbereiche wie 172.18.0.0/16).");
    }

    /**
     * Meldet einmalig, wenn X-Forwarded-For von einer Adresse kommt, der nicht vertraut wird – typisch für einen
     * Reverse-Proxy, der in AIDOCS_TRUSTED_PROXIES fehlt. Dann sähe die Anmeldesperre alle Browser als eine einzige
     * Adresse (die des Proxys).
     */
    @Bean
    OncePerRequestFilter untrustedForwardedHeaderWarning(@Value("${app.trusted-proxies:}") String trustedProxies) {
        AtomicBoolean warned = new AtomicBoolean();
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain) throws ServletException, IOException {
                // Hat der RemoteIpValve den Kopf ausgewertet, ist er entfernt; steht er noch da, kam er von außen
                if (request.getHeader("X-Forwarded-For") != null && warned.compareAndSet(false, true)) {
                    log.warn("X-Forwarded-For von {}, die nicht als Reverse-Proxy eingetragen ist ({}). Läuft die "
                                    + "Anwendung hinter einem Proxy, dessen Adresse in AIDOCS_TRUSTED_PROXIES eintragen – "
                                    + "sonst gelten alle Anmeldeversuche als von einer Adresse.",
                            request.getRemoteAddr(),
                            trustedProxies.isBlank() ? "AIDOCS_TRUSTED_PROXIES ist leer" : trustedProxies);
                }
                chain.doFilter(request, response);
            }
        };
    }
}
