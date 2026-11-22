package de.aidocuments.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Die Oberfläche ist eine Single-Page-Anwendung: Ihre Routen kennt nur der Browser.
 * Wird eine davon direkt aufgerufen oder neu geladen, liefert der Server die index.html aus.
 */
@Controller
public class SpaController {

    @GetMapping({"/documents", "/documents/**", "/settings", "/settings/**", "/history", "/duplicates/**"})
    public String forward() {
        return "forward:/index.html";
    }

    /** Geteilte Dokumente (Link für nicht angemeldete Personen) – nicht in Suchmaschinen. */
    @GetMapping("/s/**")
    public String forwardShare(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        return "forward:/index.html";
    }
}
