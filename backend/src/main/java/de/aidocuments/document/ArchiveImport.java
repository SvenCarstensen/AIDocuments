package de.aidocuments.document;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.aidocuments.area.Area;
import de.aidocuments.area.AreaRepository;
import de.aidocuments.i18n.Messages;
import de.aidocuments.i18n.Msg;
import de.aidocuments.web.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Nimmt ein ZIP auf, wie es der Export erzeugt ({@code documents.json} plus PDFs, ab dieser Version auch die Texte):
 * jedes PDF wird ein Dokument mit den mitgebrachten Angaben – die KI liest es nicht noch einmal. Schon vorhandene
 * Dateien (gleiche Prüfsumme) werden übersprungen, ein Import lässt sich also gefahrlos wiederholen. Dazu zählt auch
 * die mitgebrachte Prüfsumme der ursprünglich empfangenen Datei: Ein hier bearbeitetes Dokument exportiert seine
 * aktuelle Fassung, die anders aussieht als das Original – es soll beim Import trotzdem als vorhanden gelten.
 *
 * <p>Der Bereich: auf Wunsch der gleichnamige vorhandene, sonst der gewählte.
 */
@Component
public class ArchiveImport {

    private static final Logger log = LoggerFactory.getLogger(ArchiveImport.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String INDEX_ENTRY = "documents.json";
    /** Größer wird {@code documents.json} auch bei sehr vielen Dokumenten nicht. */
    static final long MAX_INDEX_BYTES = 256L * 1024 * 1024;
    /** Texte liegen einzeln im ZIP; mehr nimmt ein Dokument nicht an. */
    static final long MAX_TEXT_BYTES = 16L * 1024 * 1024;
    static final int MAX_DOCUMENTS = 100_000;
    /** So viele Hinweise auf einzelne Dateien stehen höchstens in der Antwort. */
    static final int MAX_PROBLEMS = 20;

    public record ImportResult(int imported, int duplicates, int failed, List<String> problems) {
    }

    private final DocumentService service;
    private final DocumentRepository documents;
    private final AreaRepository areas;
    private final Messages messages;

    public ArchiveImport(DocumentService service, DocumentRepository documents, AreaRepository areas,
                         Messages messages) {
        this.service = service;
        this.documents = documents;
        this.areas = areas;
        this.messages = messages;
    }

    /**
     * @param zip        das hochgeladene Archiv (bleibt liegen; aufräumen muss der Aufrufer)
     * @param areaId     Bereich für alle Dokumente – oder für die, deren Bereich es hier nicht gibt
     * @param matchAreas Dokumente in den gleichnamigen Bereich legen, sofern vorhanden
     */
    public ImportResult importArchive(Path zip, String areaId, boolean matchAreas, Locale locale) throws IOException {
        Map<String, String> byName = new HashMap<>();
        if (matchAreas) {
            for (Area area : areas.findAll()) {
                if (area.name() != null && !area.name().isBlank()) {
                    byName.putIfAbsent(area.name().strip().toLowerCase(Locale.ROOT), area.id());
                }
            }
        }
        int imported = 0;
        int duplicates = 0;
        int failed = 0;
        List<String> problems = new ArrayList<>();
        try (ZipFile archive = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
            List<Map<String, Object>> rows = rows(archive);
            for (Map<String, Object> row : rows) {
                String file = string(row, "file", 1000);
                ZipEntry entry = file == null ? null : archive.getEntry(file);
                if (entry == null || entry.isDirectory()) {
                    failed++;
                    problem(problems, locale, file != null ? file : string(row, "title", 200), "import.fileMissing");
                    continue;
                }
                String original = string(row, "sha256", 64);
                if (original != null && original.matches("[0-9a-f]{64}") && documents.findBySha256(original).isPresent()) {
                    duplicates++;
                    continue;
                }
                String area = byName.getOrDefault(lower(string(row, "area", 255)), areaId);
                String filename = string(row, "originalFilename", 255);
                String mailSubject = string(row, "mailSubject", 998);
                String mailFrom = string(row, "mailFrom", 998);
                DocumentService.MailOrigin origin = mailSubject == null && mailFrom == null ? null
                        : new DocumentService.MailOrigin(null, mailSubject, mailFrom, null);
                String text = text(archive, string(row, "textFile", 1000));
                DocumentService.IngestResult result;
                try (InputStream in = archive.getInputStream(entry)) {
                    result = service.ingestImported(in, filename != null ? filename : Path.of(file).getFileName().toString(),
                            source(row), origin, area, details(row), text, Boolean.TRUE.equals(row.get("ocr")));
                } catch (ZipException e) {
                    failed++;
                    problem(problems, locale, file, "import.fileUnreadable");
                    continue;
                }
                switch (result.outcome()) {
                    case CREATED -> imported++;
                    case DUPLICATE -> duplicates++;
                    default -> {
                        failed++;
                        if (problems.size() < MAX_PROBLEMS) {
                            problems.add(file + ": " + (result.message() == null ? ""
                                    : messages.render(result.message(), locale)));
                        }
                    }
                }
            }
        } catch (ZipException e) {
            throw ApiException.badRequest("error.import.notAnExport");
        }
        log.info("Import: {} Dokumente übernommen, {} schon vorhanden, {} nicht übernommen", imported, duplicates,
                failed);
        return new ImportResult(imported, duplicates, failed, problems);
    }

    private static List<Map<String, Object>> rows(ZipFile archive) throws IOException {
        ZipEntry index = archive.getEntry(INDEX_ENTRY);
        if (index == null) {
            throw ApiException.badRequest("error.import.notAnExport");
        }
        byte[] json;
        try (InputStream in = archive.getInputStream(index)) {
            json = in.readNBytes((int) MAX_INDEX_BYTES + 1);
        }
        if (json.length > MAX_INDEX_BYTES) {
            throw ApiException.badRequest("error.import.tooMany", MAX_DOCUMENTS);
        }
        List<Map<String, Object>> rows;
        try {
            rows = JSON.readValue(json, new TypeReference<>() { });
        } catch (IOException e) {
            throw ApiException.badRequest("error.import.notAnExport");
        }
        if (rows == null) {
            throw ApiException.badRequest("error.import.notAnExport");
        }
        if (rows.size() > MAX_DOCUMENTS) {
            throw ApiException.badRequest("error.import.tooMany", MAX_DOCUMENTS);
        }
        return rows.stream().filter(java.util.Objects::nonNull).toList();
    }

    private static String text(ZipFile archive, String name) throws IOException {
        ZipEntry entry = name == null ? null : archive.getEntry(name);
        if (entry == null || entry.isDirectory()) {
            return null;
        }
        try (InputStream in = archive.getInputStream(entry)) {
            byte[] bytes = in.readNBytes((int) MAX_TEXT_BYTES);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /** Die Angaben aus dem Export, auf dieselben Längen begrenzt wie die der KI. */
    private static Consumer<Document> details(Map<String, Object> row) {
        return doc -> {
            String title = string(row, "title", 500);
            if (title != null) {
                doc.setTitle(title);
            }
            doc.setSummary(string(row, "summary", 4000));
            doc.setCategory(string(row, "category", 100));
            doc.setCorrespondent(string(row, "correspondent", 255));
            doc.setLanguage(string(row, "language", 32));
            doc.setDocumentDate(date(string(row, "documentDate", 10)));
            if (row.get("tags") instanceof List<?> tags) {
                doc.replaceTags(DocumentProcessor.normalizeTags(tags.stream()
                        .filter(String.class::isInstance).map(String.class::cast).toList()));
            }
            if (row.get("keyFacts") instanceof List<?> facts) {
                List<KeyFact> keyFacts = new ArrayList<>();
                for (Object fact : facts) {
                    if (fact instanceof Map<?, ?> map && keyFacts.size() < 20) {
                        String label = clip(map.get("label"), 100);
                        String value = clip(map.get("value"), 500);
                        if (label != null && value != null) {
                            keyFacts.add(new KeyFact(label, value));
                        }
                    }
                }
                doc.replaceKeyFacts(keyFacts);
            }
            // eine Aufbewahrungsfrist reist mit; die Regeln hier können sie nur verlängern
            doc.setRetainUntil(date(string(row, "retainUntil", 10)));
        };
    }

    private static DocumentSource source(Map<String, Object> row) {
        String value = string(row, "source", 20);
        try {
            return value == null ? DocumentSource.UPLOAD : DocumentSource.valueOf(value);
        } catch (IllegalArgumentException e) {
            return DocumentSource.UPLOAD;
        }
    }

    private void problem(List<String> problems, Locale locale, String file, String key) {
        if (problems.size() < MAX_PROBLEMS) {
            problems.add((file == null ? "?" : file) + ": " + messages.render(Msg.of(key), locale));
        }
    }

    private static String string(Map<String, Object> row, String key, int max) {
        return clip(row.get(key), max);
    }

    private static String clip(Object value, int max) {
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        String stripped = text.strip();
        return stripped.length() > max ? stripped.substring(0, max) : stripped;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static LocalDate date(String value) {
        try {
            return value == null ? null : LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /** Für den Controller: den Upload in eine Zwischendatei schreiben, höchstens so viel, wie Platz ist. */
    public static long copyLimited(InputStream in, Path target, long limit) throws IOException {
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (var out = Files.newOutputStream(target)) {
            for (int read; (read = in.read(buffer)) >= 0; ) {
                total += read;
                if (total > limit) {
                    throw new FileStorage.StorageFullException();
                }
                out.write(buffer, 0, read);
            }
        }
        return total;
    }
}
