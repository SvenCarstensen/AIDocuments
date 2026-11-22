import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Erzeugt THIRD-PARTY-NOTICES.txt für die Auslieferung: Lizenzhinweise der Java-Bibliotheken des Servers, davor die
 * Laufzeitumgebung des Docker-Images und danach die Weboberfläche (beide als fertige Textdateien übergeben).
 * Lizenzen stehen in den POM-Dateien der Bibliotheken; LICENSE- und NOTICE-Dateien aus den JARs werden wörtlich
 * übernommen – Apache 2.0 verlangt das für NOTICE-Dateien, MIT und BSD für die Copyright-Zeilen.
 *
 * <p>Aufruf (ohne Kompilieren, Java 21):
 * <pre>
 *   ./mvnw dependency:list -DincludeScope=runtime -DoutputFile=deps.txt
 *   java tools/ThirdPartyNotices.java deps.txt ~/.m2/repository runtime.txt frontend.txt AUSGABE.txt
 * </pre>
 * runtime.txt und frontend.txt dürfen fehlen; dann bleibt der jeweilige Teil weg. Die Texte sind englisch, wie bei
 * solchen Hinweisen üblich.
 */
public class ThirdPartyNotices {

    private static final Pattern DEPENDENCY =
            Pattern.compile("^\\s+([^:\\s]+):([^:\\s]+):jar(?::[^:\\s]+)?:([^:\\s]+):(compile|runtime)\\b.*");
    private static final Pattern LICENSE_FILE = Pattern.compile(
            "META-INF/(LICEN[CS]E|NOTICE|COPYING)([._-][^/]*)?", Pattern.CASE_INSENSITIVE);
    private static final String RULE = "=".repeat(78);

    record Library(String coordinates, String name, String version, String license, String url,
                   Map<String, String> texts) {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            System.err.println("Aufruf: java ThirdPartyNotices.java deps.txt m2-repository runtime.txt frontend.txt"
                    + " ausgabe.txt");
            System.exit(2);
        }
        Path repository = Path.of(args[1]);
        List<Library> libraries = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(args[0]))) {
            Matcher m = DEPENDENCY.matcher(line);
            if (m.matches()) {
                libraries.add(library(repository, m.group(1), m.group(2), m.group(3)));
            }
        }
        libraries.sort((a, b) -> a.coordinates().compareTo(b.coordinates()));

        StringBuilder out = new StringBuilder();
        out.append("THIRD-PARTY SOFTWARE NOTICES – AIDocuments\n\n");
        out.append("AIDocuments includes third-party software. Each component is distributed under its own\n");
        out.append("license. This file has three parts:\n\n");
        out.append("  Part 1 – Runtime environment of the Docker image\n");
        out.append("  Part 2 – Server: Java libraries\n");
        out.append("  Part 3 – Web interface\n");
        appendFile(out, "PART 1 – RUNTIME ENVIRONMENT OF THE DOCKER IMAGE", Path.of(args[2]));
        appendLibraries(out, libraries);
        appendFile(out, "PART 3 – WEB INTERFACE", Path.of(args[3]));

        Path target = Path.of(args[4]);
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.writeString(target, out.toString(), StandardCharsets.UTF_8);
        System.out.println("Lizenzhinweise für " + libraries.size() + " Java-Bibliotheken → " + target);
    }

    private static void appendFile(StringBuilder out, String title, Path file) throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        out.append("\n\n").append(RULE).append('\n').append(RULE).append('\n').append(title).append('\n')
                .append(RULE).append('\n').append(RULE).append("\n\n");
        out.append(Files.readString(file, StandardCharsets.UTF_8).strip()).append('\n');
    }

    private static void appendLibraries(StringBuilder out, List<Library> libraries) throws Exception {
        out.append("\n\n").append(RULE).append('\n').append(RULE).append('\n')
                .append("PART 2 – SERVER: JAVA LIBRARIES\n")
                .append(RULE).append('\n').append(RULE).append("\n\n");
        out.append("The AIDocuments server includes the following Java libraries:\n\n");
        for (Library lib : libraries) {
            out.append("  ").append(lib.coordinates()).append(' ').append(lib.version()).append(" – ")
                    .append(lib.license());
            if (!lib.url().isBlank()) {
                out.append(" – ").append(lib.url());
            }
            out.append('\n');
        }
        // Für die LGPL (MariaDB Connector/J, Logback): unverändert, austauschbar, Quellcode erhältlich
        out.append("""

                All libraries are included unmodified, each as its own JAR file in BOOT-INF/lib inside the
                application archive (/app/aidocuments.jar in the Docker image), and can be replaced by another
                version of the same library. The source code of every library is available from Maven Central
                (https://repo1.maven.org/maven2/, file <name>-<version>-sources.jar next to the library) and from the
                project website named above.
                """);
        // Gleiche Texte nur einmal, mit allen Bibliotheken, die sie mitbringen
        Map<String, List<String>> owners = new LinkedHashMap<>();
        Map<String, String> textByHash = new LinkedHashMap<>();
        List<Library> withoutText = new ArrayList<>();
        for (Library lib : libraries) {
            if (lib.texts().isEmpty()) {
                withoutText.add(lib);
            }
            for (String text : lib.texts().values()) {
                String hash = sha256(text);
                textByHash.putIfAbsent(hash, text);
                owners.computeIfAbsent(hash, h -> new ArrayList<>()).add(lib.coordinates() + " " + lib.version());
            }
        }
        for (Map.Entry<String, String> entry : textByHash.entrySet()) {
            out.append('\n').append(RULE).append('\n').append(String.join(", ", owners.get(entry.getKey())))
                    .append('\n').append(RULE).append("\n\n").append(entry.getValue()).append('\n');
        }
        if (!withoutText.isEmpty()) {
            out.append('\n').append(RULE).append('\n').append("Libraries without a bundled license file")
                    .append('\n').append(RULE).append("\n\n");
            out.append("These libraries are distributed under the license named; its full text is reproduced\n");
            out.append("above where another component carries it, and is otherwise available at the address given.\n\n");
            for (Library lib : withoutText) {
                out.append("  ").append(lib.coordinates()).append(' ').append(lib.version()).append(" – ")
                        .append(lib.license()).append('\n');
            }
            appendStandardTexts(out, withoutText, textByHash.values());
        }
    }

    /**
     * Volltexte der Lizenzen, die keine der Bibliotheken selbst mitbringt – aus /usr/share/common-licenses
     * (Ubuntu/Debian, im Docker-Build vorhanden); MIT als Vorlage, da es dort keine Datei dafür gibt.
     */
    private static void appendStandardTexts(StringBuilder out, List<Library> libraries,
                                            java.util.Collection<String> alreadyIncluded) throws IOException {
        Path common = Path.of("/usr/share/common-licenses");
        boolean apacheIncluded = alreadyIncluded.stream()
                .anyMatch(t -> t.contains("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION"));
        Map<String, String> texts = new LinkedHashMap<>();
        for (Library lib : libraries) {
            String license = lib.license();
            if (license.contains("LGPL-2.1") && Files.exists(common.resolve("LGPL-2.1"))) {
                texts.putIfAbsent("GNU Lesser General Public License, version 2.1",
                        Files.readString(common.resolve("LGPL-2.1")));
            }
            if (license.contains("Apache") && !apacheIncluded && Files.exists(common.resolve("Apache-2.0"))) {
                texts.putIfAbsent("Apache License, Version 2.0", Files.readString(common.resolve("Apache-2.0")));
            }
            if (license.contains("MIT")) {
                texts.putIfAbsent("MIT License", MIT);
            }
        }
        for (Map.Entry<String, String> entry : texts.entrySet()) {
            out.append('\n').append(RULE).append('\n').append(entry.getKey()).append('\n').append(RULE)
                    .append("\n\n").append(entry.getValue().strip()).append('\n');
        }
    }

    private static final String MIT = """
            Copyright (c) the respective authors, as named in the source code of the component.

            Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
            associated documentation files (the "Software"), to deal in the Software without restriction, including
            without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
            copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the
            following conditions:

            The above copyright notice and this permission notice shall be included in all copies or substantial
            portions of the Software.

            THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
            LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
            EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
            IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
            THE USE OR OTHER DEALINGS IN THE SOFTWARE.
            """;

    private static Library library(Path repository, String group, String artifact, String version) throws Exception {
        Path dir = repository.resolve(group.replace('.', '/')).resolve(artifact).resolve(version);
        Pom pom = Pom.read(repository, dir.resolve(artifact + "-" + version + ".pom"));
        Map<String, String> texts = new LinkedHashMap<>();
        Path jar = dir.resolve(artifact + "-" + version + ".jar");
        if (Files.exists(jar)) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                for (ZipEntry entry : zip.stream().sorted((a, b) -> a.getName().compareTo(b.getName())).toList()) {
                    if (!entry.isDirectory() && LICENSE_FILE.matcher(entry.getName()).matches()) {
                        try (InputStream in = zip.getInputStream(entry)) {
                            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
                            if (!text.isEmpty()) {
                                texts.put(entry.getName(), text);
                            }
                        }
                    }
                }
            }
        }
        String license = pom == null || pom.licenses().isEmpty() ? "see project page" : pom.licenses();
        return new Library(group + ":" + artifact, pom == null ? artifact : pom.name(), version, license,
                pom == null ? "" : pom.url(), texts);
    }

    /** Die für Lizenzhinweise nötigen Angaben einer POM-Datei, fehlende aus den Eltern-POMs ergänzt. */
    record Pom(String name, String url, String licenses) {

        static Pom read(Path repository, Path file) throws Exception {
            if (!Files.exists(file)) {
                return null;
            }
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Element project = factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
            String name = text(project, "name");
            String url = text(project, "url");
            List<String> licenses = new ArrayList<>();
            Element block = child(project, "licenses");
            if (block != null) {
                for (Node n = block.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (n instanceof Element license && license.getTagName().equals("license")) {
                        String licenseName = text(license, "name");
                        licenses.add(licenseName.isBlank() ? text(license, "url") : licenseName);
                    }
                }
            }
            Element parent = child(project, "parent");
            if (parent != null && (licenses.isEmpty() || url.isBlank())) {
                Path parentFile = repository.resolve(text(parent, "groupId").replace('.', '/'))
                        .resolve(text(parent, "artifactId")).resolve(text(parent, "version"))
                        .resolve(text(parent, "artifactId") + "-" + text(parent, "version") + ".pom");
                Pom inherited = read(repository, parentFile);
                if (inherited != null) {
                    if (licenses.isEmpty() && !inherited.licenses().isEmpty()) {
                        licenses.add(inherited.licenses());
                    }
                    if (url.isBlank()) {
                        url = inherited.url();
                    }
                }
            }
            return new Pom(name, url.contains("${") ? "" : url, String.join("; ", licenses));
        }

        private static Element child(Element parent, String tag) {
            for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element e && e.getTagName().equals(tag)) {
                    return e;
                }
            }
            return null;
        }

        private static String text(Element parent, String tag) {
            Element e = child(parent, tag);
            return e == null ? "" : e.getTextContent().strip().replaceAll("\\s+", " ");
        }
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
