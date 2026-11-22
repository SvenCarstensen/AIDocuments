package de.aidocuments.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.aidocuments.area.Area;
import de.aidocuments.area.AreaRepository;
import de.aidocuments.i18n.Messages;
import jakarta.servlet.http.HttpSession;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Export als ZIP: die PDFs der gewählten Dokumente, ihre Texte ({@code texts/}) und ihre Angaben als
 * {@code documents.csv} (für Tabellen­programme) und {@code documents.json} (vollständig, für Programme – und für den
 * Import in eine andere Installation, siehe {@link ArchiveImport}).
 *
 * <p>Zweistufig: {@link #prepare} merkt sich die Auswahl unter einem zufälligen, einmal gültigen Schlüssel in der
 * Sitzung; der Download ({@link #take}) ist dann ein gewöhnlicher Seitenaufruf. So greift vor dem Export die Bestätigung
 * per App-Code wie bei jeder anderen Anfrage, und die Datei muss nicht erst im Browser zusammengesetzt werden.
 */
@Component
public class DocumentExport {

    private static final Logger log = LoggerFactory.getLogger(DocumentExport.class);
    private static final String SESSION_ATTRIBUTE = "aidocuments.exports";
    private static final Duration VALID_FOR = Duration.ofMinutes(5);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final int MAX_NAME = 120;

    record Ticket(List<String> ids, Instant expiresAt) implements java.io.Serializable {
    }

    private final DocumentRepository documents;
    private final AreaRepository areas;
    private final FileStorage storage;
    private final Messages messages;
    private final DocumentTextRepository texts;

    public DocumentExport(DocumentRepository documents, AreaRepository areas, FileStorage storage, Messages messages,
                          DocumentTextRepository texts) {
        this.texts = texts;
        this.documents = documents;
        this.areas = areas;
        this.storage = storage;
        this.messages = messages;
    }

    /** Merkt sich die Auswahl und liefert den Schlüssel für den Download. */
    public String prepare(HttpSession session, List<String> ids) {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Map<String, Ticket> tickets = tickets(session);
        Instant now = Instant.now();
        tickets.values().removeIf(t -> t.expiresAt().isBefore(now));
        tickets.put(token, new Ticket(List.copyOf(ids), now.plus(VALID_FOR)));
        return token;
    }

    /** Die gemerkte Auswahl – nur einmal und nur in der Sitzung, die sie angelegt hat. */
    public List<String> take(HttpSession session, String token) {
        Ticket ticket = session == null ? null : tickets(session).remove(token);
        return ticket == null || ticket.expiresAt().isBefore(Instant.now()) ? null : ticket.ids();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Ticket> tickets(HttpSession session) {
        synchronized (session) {
            Object existing = session.getAttribute(SESSION_ATTRIBUTE);
            if (existing instanceof Map<?, ?> map) {
                return (Map<String, Ticket>) map;
            }
            Map<String, Ticket> created = new ConcurrentHashMap<>();
            session.setAttribute(SESSION_ATTRIBUTE, created);
            return created;
        }
    }

    /**
     * Schreibt das ZIP. Die Dokumente sind bereits auf die für den Anfragenden sichtbaren eingeschränkt; Reihenfolge wie
     * in der Liste.
     */
    public void write(List<Document> selected, OutputStream target) throws IOException {
        Map<String, String> areaNames = areas.findAll().stream().collect(Collectors.toMap(Area::id,
                a -> a.name() == null || a.name().isBlank() ? messages.get("document.export.generalArea") : a.name()));
        Set<String> usedNames = new HashSet<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        int missing = 0;
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(target, 64 * 1024), StandardCharsets.UTF_8)) {
            zip.setLevel(1); // PDFs sind meist schon komprimiert – Zeit sparen statt Bytes
            for (Document doc : selected) {
                Path file = storage.resolve(doc.getStoragePath());
                String entryName = null;
                if (Files.isRegularFile(file)) {
                    entryName = uniqueName(usedNames, "documents/" + baseName(doc));
                    ZipEntry entry = new ZipEntry(entryName);
                    Instant changed = doc.getModifiedAt() != null ? doc.getModifiedAt() : doc.getCreatedAt();
                    if (changed != null) {
                        entry.setLastModifiedTime(FileTime.from(changed));
                    }
                    zip.putNextEntry(entry);
                    Files.copy(file, zip);
                    zip.closeEntry();
                } else {
                    missing++;
                }
                Map<String, Object> row = row(doc, entryName, areaNames);
                // der gelesene oder erkannte Text, damit ein Import ihn nicht neu gewinnen muss
                String text = entryName == null ? null
                        : texts.findById(doc.getId()).map(DocumentText::content).orElse(null);
                if (text != null && !text.isBlank()) {
                    String textName = "texts/"
                            + entryName.substring("documents/".length()).replaceFirst("\\.pdf$", ".txt");
                    zip.putNextEntry(new ZipEntry(textName));
                    zip.write(text.getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                    row.put("textFile", textName);
                }
                row.put("ocr", doc.isOcrUsed());
                rows.add(row);
            }
            zip.putNextEntry(new ZipEntry("documents.csv"));
            zip.write(csv(rows).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("documents.json"));
            zip.write(JSON.writeValueAsBytes(rows));
            zip.closeEntry();
        }
        if (missing > 0) {
            log.warn("Export: {} Dateien fehlen im Datenverzeichnis", missing);
        }
    }

    /** Die Dokumente zu den IDs, sofern noch vorhanden – in der Reihenfolge der IDs. */
    public List<Document> load(List<String> ids) {
        Map<String, Document> byId = documents.findAllById(ids).stream()
                .collect(Collectors.toMap(Document::getId, Function.identity()));
        return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    private static Map<String, Object> row(Document doc, String file, Map<String, String> areaNames) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", doc.getId());
        row.put("file", file);
        row.put("title", doc.getTitle());
        row.put("documentDate", text(doc.getDocumentDate()));
        row.put("category", doc.getCategory());
        row.put("correspondent", doc.getCorrespondent());
        row.put("tags", List.copyOf(new java.util.TreeSet<>(doc.getTags())));
        row.put("area", areaNames.getOrDefault(doc.getAreaId(), doc.getAreaId()));
        row.put("summary", doc.getSummary());
        row.put("keyFacts", doc.getKeyFacts().stream()
                .map(f -> Map.of("label", nz(f.label()), "value", nz(f.value()))).toList());
        row.put("language", doc.getLanguage());
        row.put("pages", doc.getPageCount());
        row.put("source", doc.getSource() == null ? null : doc.getSource().name());
        row.put("originalFilename", doc.getOriginalFilename());
        row.put("mailSubject", doc.getMailSubject());
        row.put("mailFrom", doc.getMailFrom());
        row.put("added", text(doc.getCreatedAt()));
        row.put("modified", text(doc.getModifiedAt()));
        row.put("retainUntil", text(doc.getRetainUntil()));
        row.put("sha256", doc.getSha256());
        return row;
    }

    private static final List<String> CSV_COLUMNS = List.of("file", "title", "documentDate", "category",
            "correspondent", "tags", "area", "summary", "source", "originalFilename", "added", "retainUntil", "id");

    /** Semikolon und BOM: so öffnet Excel die Datei auch in deutschsprachigen Einstellungen richtig. */
    static String csv(List<Map<String, Object>> rows) {
        StringBuilder out = new StringBuilder("﻿");
        out.append(String.join(";", CSV_COLUMNS)).append("\r\n");
        for (Map<String, Object> row : rows) {
            List<String> cells = new ArrayList<>();
            for (String column : CSV_COLUMNS) {
                Object value = row.get(column);
                String cell = value instanceof List<?> list
                        ? list.stream().map(String::valueOf).collect(Collectors.joining(", "))
                        : value == null ? "" : value.toString();
                cells.add(csvCell(cell));
            }
            out.append(String.join(";", cells)).append("\r\n");
        }
        return out.toString();
    }

    static String csvCell(String value) {
        String cell = value;
        // Formeln nicht ausführen lassen (CSV-Injection): =, +, -, @ am Anfang werden zu Text
        if (!cell.isEmpty() && "=+-@\t\r".indexOf(cell.charAt(0)) >= 0) {
            cell = "'" + cell;
        }
        if (cell.contains(";") || cell.contains("\"") || cell.contains("\n") || cell.contains("\r")) {
            cell = '"' + cell.replace("\"", "\"\"") + '"';
        }
        return cell;
    }

    /** "2025-03-14 Stromrechnung März" – Datum des Dokuments, sonst der Tag der Aufnahme. */
    static String baseName(Document doc) {
        LocalDate date = doc.getDocumentDate() != null ? doc.getDocumentDate()
                : doc.getCreatedAt() != null ? LocalDate.ofInstant(doc.getCreatedAt(), ZoneId.systemDefault()) : null;
        String title = doc.getTitle() != null && !doc.getTitle().isBlank() ? doc.getTitle()
                : doc.getOriginalFilename().replaceFirst("(?i)\\.pdf$", "");
        String name = (date != null ? date + " " : "") + title;
        name = name.replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]", " ").replaceAll("\\s+", " ").strip();
        name = name.replaceAll("^\\.+", "");
        if (name.length() > MAX_NAME) {
            name = name.substring(0, MAX_NAME).strip();
        }
        return name.isEmpty() ? doc.getId() : name;
    }

    /** Gleiche Namen bekommen " (2)", " (3)" … – auch bei abweichender Groß-/Kleinschreibung (Windows). */
    private static String uniqueName(Set<String> used, String base) {
        String candidate = base + ".pdf";
        for (int n = 2; !used.add(candidate.toLowerCase(Locale.ROOT)); n++) {
            candidate = base + " (" + n + ").pdf";
        }
        return candidate;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
