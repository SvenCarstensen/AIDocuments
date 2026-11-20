package de.aidocuments.search;

import de.aidocuments.config.AppProperties;
import de.aidocuments.document.Document;
import de.aidocuments.document.DocumentStatus;
import de.aidocuments.document.KeyFact;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.BreakIterator;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.analysis.de.GermanAnalyzer;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.simple.SimpleQueryParser;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.uhighlight.DefaultPassageFormatter;
import org.apache.lucene.search.uhighlight.LengthGoalBreakIterator;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.stereotype.Component;

/**
 * Volltextindex (Apache Lucene, deutscher Analyzer mit Stemming) über alle Dokumente.
 * Die Datenbank bleibt die Quelle der Wahrheit; der Index lässt sich jederzeit daraus neu aufbauen.
 */
@Component
public class SearchIndex {

    /** Markieren im Trefferausschnitt Anfang und Ende eines Treffers (Private-Use-Zeichen, kein HTML). */
    public static final String MARK_START = "";
    public static final String MARK_END = "";

    public enum SortOrder {
        RELEVANCE, NEWEST, DOCUMENT_DATE
    }

    public record Criteria(String text, String category, String correspondent, List<String> tags,
                           DocumentStatus status, LocalDate from, LocalDate to,
                           SortOrder sort, int page, int size) {
    }

    public record Hit(String id, String snippet) {
    }

    public record Result(List<Hit> hits, long total) {
    }

    private static final String F_ID = "id";
    private static final String F_TITLE = "title";
    private static final String F_SUMMARY = "summary";
    private static final String F_CONTENT = "content";
    private static final String F_TAGS = "tags";
    private static final String F_CORRESPONDENT = "correspondent";
    private static final String F_CATEGORY = "category";
    private static final String F_FACTS = "facts";
    private static final String F_FILENAME = "filename";
    private static final String F_MAIL = "mail";
    /** Alle Texte unverändert (nur kleingeschrieben) – Grundlage der Teilwortsuche. */
    private static final String F_ALL_PLAIN = "all_plain";
    private static final String K_CATEGORY = "category_k";
    private static final String K_CORRESPONDENT = "correspondent_k";
    private static final String K_TAG = "tag_k";
    private static final String K_STATUS = "status_k";
    private static final String P_DOC_DATE = "docdate_p";
    private static final String S_DOC_DATE = "docdate_s";
    private static final String S_CREATED = "created_s";

    private static final Map<String, Float> FIELD_WEIGHTS = Map.of(
            F_TITLE, 4f,
            F_TAGS, 3f,
            F_CORRESPONDENT, 3f,
            F_CATEGORY, 2f,
            F_SUMMARY, 2f,
            F_FACTS, 2f,
            F_FILENAME, 1.5f,
            F_MAIL, 1f,
            F_CONTENT, 1f);

    private static final int SNIPPET_LENGTH = 220;
    private static final int MAX_HIGHLIGHT_CHARS = 2_000_000;

    private static final Pattern PLAIN_WORD = Pattern.compile("[\\p{L}\\p{N}]{3,}");
    private static final int MAX_INFIX_TERMS = 6;

    private final Analyzer analyzer = new PerFieldAnalyzerWrapper(
            new GermanAnalyzer(), Map.of(F_ALL_PLAIN, plainAnalyzer()));
    private final Directory directory;
    private final IndexWriter writer;
    private final SearcherManager searcherManager;

    public SearchIndex(AppProperties properties) {
        try {
            Path path = properties.dataRoot().resolve("index");
            Files.createDirectories(path);
            this.directory = FSDirectory.open(path);
            this.writer = new IndexWriter(directory, new IndexWriterConfig(analyzer));
            this.writer.commit();
            this.searcherManager = new SearcherManager(writer, null);
        } catch (IOException e) {
            throw new UncheckedIOException("Suchindex kann nicht geöffnet werden", e);
        }
    }

    @PreDestroy
    void close() throws IOException {
        searcherManager.close();
        writer.close();
        directory.close();
    }

    public void index(Document doc, String fullText) {
        try {
            writer.updateDocument(new Term(F_ID, doc.getId()), toLuceneDocument(doc, fullText));
            commitAndRefresh();
        } catch (IOException e) {
            throw new UncheckedIOException("Dokument konnte nicht indiziert werden", e);
        }
    }

    public void delete(String id) {
        try {
            writer.deleteDocuments(new Term(F_ID, id));
            commitAndRefresh();
        } catch (IOException e) {
            throw new UncheckedIOException("Dokument konnte nicht aus dem Index entfernt werden", e);
        }
    }

    /** Ersetzt den gesamten Index; {@code fullTexts} liefert zu jeder Dokument-ID den Volltext. */
    public void rebuild(List<Document> documents, Map<String, String> fullTexts) {
        try {
            writer.deleteAll();
            for (Document doc : documents) {
                writer.addDocument(toLuceneDocument(doc, fullTexts.getOrDefault(doc.getId(), "")));
            }
            commitAndRefresh();
        } catch (IOException e) {
            throw new UncheckedIOException("Suchindex konnte nicht neu aufgebaut werden", e);
        }
    }

    public int size() {
        try {
            IndexSearcher searcher = searcherManager.acquire();
            try {
                return searcher.getIndexReader().numDocs();
            } finally {
                searcherManager.release(searcher);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Result search(Criteria criteria) {
        boolean hasText = criteria.text() != null && !criteria.text().isBlank();
        Query textQuery = hasText ? buildTextQuery(criteria.text().strip()) : null;

        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(textQuery != null ? textQuery : new MatchAllDocsQuery(), Occur.MUST);
        addFilter(builder, K_CATEGORY, criteria.category());
        addFilter(builder, K_CORRESPONDENT, criteria.correspondent());
        if (criteria.tags() != null) {
            criteria.tags().forEach(tag -> addFilter(builder, K_TAG, tag));
        }
        if (criteria.status() != null) {
            addFilter(builder, K_STATUS, criteria.status().name());
        }
        if (criteria.from() != null || criteria.to() != null) {
            long from = criteria.from() != null ? criteria.from().toEpochDay() : Long.MIN_VALUE;
            long to = criteria.to() != null ? criteria.to().toEpochDay() : Long.MAX_VALUE;
            builder.add(LongPoint.newRangeQuery(P_DOC_DATE, from, to), Occur.FILTER);
        }
        Query query = builder.build();

        int offset = criteria.page() * criteria.size();
        int needed = offset + criteria.size();
        try {
            IndexSearcher searcher = searcherManager.acquire();
            try {
                long total = searcher.count(query);
                TopDocs top = switch (effectiveSort(criteria.sort(), hasText)) {
                    case RELEVANCE -> searcher.search(query, needed);
                    case NEWEST -> searcher.search(query, needed,
                            new Sort(new SortField(S_CREATED, SortField.Type.LONG, true)));
                    case DOCUMENT_DATE -> searcher.search(query, needed, new Sort(
                            new SortField(S_DOC_DATE, SortField.Type.LONG, true),
                            new SortField(S_CREATED, SortField.Type.LONG, true)));
                };
                ScoreDoc[] scoreDocs = top.scoreDocs;
                List<ScoreDoc> pageDocs = offset >= scoreDocs.length
                        ? List.of()
                        : List.of(scoreDocs).subList(offset, scoreDocs.length);
                String[] snippets = textQuery != null ? highlight(searcher, textQuery, pageDocs) : null;

                List<Hit> hits = new ArrayList<>(pageDocs.size());
                for (int i = 0; i < pageDocs.size(); i++) {
                    String id = searcher.storedFields().document(pageDocs.get(i).doc).get(F_ID);
                    hits.add(new Hit(id, snippets != null ? trimSnippet(snippets[i]) : null));
                }
                return new Result(hits, total);
            } finally {
                searcherManager.release(searcher);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Suche fehlgeschlagen", e);
        }
    }

    private static SortOrder effectiveSort(SortOrder requested, boolean hasText) {
        if (requested == null || (requested == SortOrder.RELEVANCE && !hasText)) {
            return hasText ? SortOrder.RELEVANCE : SortOrder.NEWEST;
        }
        return requested;
    }

    /**
     * Baut die Volltextabfrage aus drei Stufen, die sich ergänzen:
     * 1. Wortsuche mit deutscher Stammformbildung ("Rechnungen" findet "Rechnung"); unterstützt
     *    "exakte Phrasen", -ausschluss, a | b und präfix*.
     * 2. Das zuletzt getippte Wort als Wortanfang, damit schon beim Tippen Treffer erscheinen.
     * 3. Teiltextsuche: jedes Wort darf auch mitten in einem Wort stehen
     *    ("versicherung" findet "Haftpflichtversicherung"). Zählt bei der Sortierung am wenigsten.
     */
    private Query buildTextQuery(String text) {
        BooleanQuery.Builder combined = new BooleanQuery.Builder();
        combined.add(buildWordQuery(text), Occur.SHOULD);
        Query infix = buildInfixQuery(text);
        if (infix != null) {
            combined.add(new BoostQuery(infix, 0.3f), Occur.SHOULD);
        }
        return combined.build();
    }

    private Query buildInfixQuery(String text) {
        String[] words = text.toLowerCase(Locale.ROOT).split("\\s+");
        if (words.length > MAX_INFIX_TERMS) {
            return null;
        }
        BooleanQuery.Builder all = new BooleanQuery.Builder();
        for (String word : words) {
            if (!PLAIN_WORD.matcher(word).matches()) {
                return null; // Operatoren, Phrasen oder sehr kurze Wörter: nur die Wortsuche anwenden
            }
            all.add(new WildcardQuery(new Term(F_ALL_PLAIN, "*" + word + "*")), Occur.MUST);
        }
        return all.build();
    }

    private static Analyzer plainAnalyzer() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                StandardTokenizer tokenizer = new StandardTokenizer();
                return new TokenStreamComponents(tokenizer, new LowerCaseFilter(tokenizer));
            }
        };
    }

    private Query buildWordQuery(String text) {
        int flags = SimpleQueryParser.PHRASE_OPERATOR | SimpleQueryParser.PREFIX_OPERATOR
                | SimpleQueryParser.NOT_OPERATOR | SimpleQueryParser.OR_OPERATOR
                | SimpleQueryParser.WHITESPACE_OPERATOR | SimpleQueryParser.PRECEDENCE_OPERATORS;
        SimpleQueryParser parser = new SimpleQueryParser(analyzer, FIELD_WEIGHTS, flags);
        parser.setDefaultOperator(Occur.MUST);
        Query exact = parser.parse(text);
        if (!Character.isLetterOrDigit(text.charAt(text.length() - 1))) {
            return exact;
        }
        Query asYouType = parser.parse(text + "*");
        return new BooleanQuery.Builder()
                .add(exact, Occur.SHOULD)
                .add(asYouType, Occur.SHOULD)
                .build();
    }

    private String[] highlight(IndexSearcher searcher, Query query, List<ScoreDoc> docs) throws IOException {
        if (docs.isEmpty()) {
            return new String[0];
        }
        UnifiedHighlighter highlighter = UnifiedHighlighter.builder(searcher, analyzer)
                .withFormatter(new DefaultPassageFormatter(MARK_START, MARK_END, " … ", false))
                .withBreakIterator(() -> LengthGoalBreakIterator.createClosestToLength(
                        BreakIterator.getSentenceInstance(Locale.GERMAN), SNIPPET_LENGTH, 0.5f))
                .withMaxLength(MAX_HIGHLIGHT_CHARS)
                .withMaxNoHighlightPassages(0)
                // auch Treffer der Teiltextsuche (anderes Feld) im Volltext hervorheben
                .withFieldMatcher(field -> true)
                .build();
        int[] docIds = docs.stream().mapToInt(d -> d.doc).toArray();
        return highlighter.highlightFields(new String[] {F_CONTENT}, query, docIds, new int[] {2}).get(F_CONTENT);
    }

    /** Scans liefern oft Text ohne Satzgrenzen – dann den Ausschnitt um den ersten Treffer herum kürzen. */
    private static String trimSnippet(String snippet) {
        if (snippet == null) {
            return null;
        }
        String normalized = snippet.replaceAll("\\s+", " ").strip();
        int max = SNIPPET_LENGTH * 2;
        if (normalized.length() <= max) {
            return normalized;
        }
        int firstMark = Math.max(0, normalized.indexOf(MARK_START));
        int start = Math.max(0, firstMark - SNIPPET_LENGTH / 2);
        int end = Math.min(normalized.length(), start + max);
        String cut = normalized.substring(start, end);
        // keine halb abgeschnittene Markierung zurückgeben
        if (cut.lastIndexOf(MARK_START) > cut.lastIndexOf(MARK_END)) {
            cut = cut + MARK_END;
        }
        return (start > 0 ? "… " : "") + cut + (end < normalized.length() ? " …" : "");
    }

    private static void addFilter(BooleanQuery.Builder builder, String field, String value) {
        if (value != null && !value.isBlank()) {
            builder.add(new TermQuery(new Term(field, value)), Occur.FILTER);
        }
    }

    private static org.apache.lucene.document.Document toLuceneDocument(Document doc, String fullText) {
        var lucene = new org.apache.lucene.document.Document();
        lucene.add(new StringField(F_ID, doc.getId(), Field.Store.YES));

        addText(lucene, F_TITLE, doc.getTitle(), false);
        addText(lucene, F_SUMMARY, doc.getSummary(), false);
        addText(lucene, F_CONTENT, fullText, true);
        addText(lucene, F_CORRESPONDENT, doc.getCorrespondent(), false);
        addText(lucene, F_CATEGORY, doc.getCategory(), false);
        addText(lucene, F_FILENAME, doc.getOriginalFilename(), false);
        addText(lucene, F_TAGS, String.join(" ", doc.getTags()), false);
        addText(lucene, F_FACTS, doc.getKeyFacts().stream()
                .map((KeyFact f) -> f.label() + " " + f.value())
                .collect(Collectors.joining("\n")), false);
        addText(lucene, F_MAIL, joinNonNull(doc.getMailSubject(), doc.getMailFrom()), false);
        addText(lucene, F_ALL_PLAIN, joinNonNull(doc.getTitle(), String.join(" ", doc.getTags()),
                doc.getCorrespondent(), doc.getCategory(), doc.getOriginalFilename(), doc.getSummary(),
                fullText), false);

        addKeyword(lucene, K_CATEGORY, doc.getCategory());
        addKeyword(lucene, K_CORRESPONDENT, doc.getCorrespondent());
        addKeyword(lucene, K_STATUS, doc.getStatus().name());
        doc.getTags().forEach(tag -> addKeyword(lucene, K_TAG, tag));

        long created = doc.getCreatedAt().toEpochMilli();
        LocalDate effectiveDate = doc.getDocumentDate() != null
                ? doc.getDocumentDate()
                : doc.getCreatedAt().atZone(ZoneId.systemDefault()).toLocalDate();
        lucene.add(new LongPoint(P_DOC_DATE, effectiveDate.toEpochDay()));
        lucene.add(new NumericDocValuesField(S_DOC_DATE, effectiveDate.toEpochDay()));
        lucene.add(new NumericDocValuesField(S_CREATED, created));
        return lucene;
    }

    private static void addText(org.apache.lucene.document.Document lucene, String field, String value,
                                boolean stored) {
        if (value != null && !value.isBlank()) {
            lucene.add(new TextField(field, value, stored ? Field.Store.YES : Field.Store.NO));
        }
    }

    private static void addKeyword(org.apache.lucene.document.Document lucene, String field, String value) {
        if (value != null && !value.isBlank()) {
            lucene.add(new StringField(field, value, Field.Store.NO));
        }
    }

    private static String joinNonNull(String... parts) {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                seen.put(part, true);
            }
        }
        return String.join(" ", seen.keySet());
    }

    private void commitAndRefresh() throws IOException {
        writer.commit();
        searcherManager.maybeRefreshBlocking();
    }
}
