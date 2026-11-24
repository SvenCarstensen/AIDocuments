package de.aidocuments.document;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.filter.FilterFactory;

/**
 * Vorprüfung der Rohdatei, bevor PDFBox sie sieht. PDFBox entpackt Querverweis- und Objektströme (und später
 * eingebettete Dateien) vollständig im Arbeitsspeicher, ehe {@link PdfExtractor#checkStreams} greifen kann – ein
 * präpariertes PDF („Zip-Bombe“: wenige MB, entpackt Gigabytes) brächte den Server schon beim Laden zum Stehen.
 *
 * <p>Darum wird hier jeder Datenstrom mit FlateDecode stückweise mit einem {@link Inflater} entpackt – nur gezählt,
 * nichts aufbewahrt. Abgelehnt wird das PDF, wenn
 *
 * <ul>
 *   <li>ein Datenstrom entpackt größer ist als {@link PdfExtractor#MAX_DECODED_STREAM_BYTES} (Bilder mit genau einem
 *       Filter: {@link #MAX_IMAGE_STREAM_BYTES}) – dieselbe Grenze, die {@link PdfExtractor#checkStreams} nach dem
 *       Laden ohnehin zieht;</li>
 *   <li>alle zusammen entpackt mehr ergeben als das {@value #MAX_RATIO}-Fache der Datei (mindestens
 *       {@link #MIN_TOTAL_LIMIT}).</li>
 * </ul>
 *
 * <p>Mehrere Filter hintereinander (etwa zweimal FlateDecode) werden Stufe für Stufe entpackt; jede Stufe zählt mit,
 * und es gilt immer die strenge Grenze. Bilddekoder (JPEG, JPEG 2000, JBIG2, CCITT) beenden die Kette – deren Daten
 * liest der Parser nicht. Die Prüfung ist bewusst grob (ohne den Aufbau des PDFs zu verstehen): Sie sucht das
 * Schlüsselwort {@code stream} und liest die Angabe {@code /Filter} davor. Weil sich ein Wörterbuch mit Texten darin
 * nicht sicher abgrenzen lässt, wird im Zweifel mehr geprüft: jede Filterkette, die kurz davor genannt ist, und
 * immer auch ein Entpacken der Daten mit FlateDecode. Daten, die gar nicht so gepackt sind, scheitern daran sofort.
 *
 * <p>Nicht erfasst: verschlüsselte PDFs (deren Daten sind erst nach dem Entschlüsseln entpackbar).
 */
final class PdfPrescan {

    /** Bilder dürfen größer sein (eine A4-Seite in 600 dpi, Farbe: gut 100 MB). */
    static final long MAX_IMAGE_STREAM_BYTES = 256L * 1024 * 1024;
    static final int MAX_RATIO = 200;
    static final long MIN_TOTAL_LIMIT = 2L * 1024 * 1024 * 1024;
    /** So weit vor „stream“ wird das Wörterbuch gesucht. */
    private static final int LOOKBACK = 4096;
    private static final int MAX_FILTERS = 8;
    /** Höchstens so viele verschiedene Filterketten je Datenstrom. */
    private static final int MAX_CHAINS = 3;
    /** Rohdaten für einen anderen ersten Filter als FlateDecode (ASCII85 …) – größer wird nicht gelesen. */
    private static final int MAX_RAW_STAGE_BYTES = 256 * 1024 * 1024;
    private static final byte[] STREAM = "stream".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ENDSTREAM = "endstream".getBytes(StandardCharsets.US_ASCII);
    private static final Set<String> FLATE = Set.of("FlateDecode", "Fl");
    private static final Set<String> IMAGE_FILTERS = Set.of("DCTDecode", "DCT", "JPXDecode", "JBIG2Decode",
            "CCITTFaxDecode", "CCF");
    /** Platzhalter für einen Filter, der nicht direkt dasteht (indirektes Objekt) – behandelt wie FlateDecode. */
    private static final String UNKNOWN = "?";
    private static final Pattern NAME_ESCAPE = Pattern.compile("#([0-9A-Fa-f]{2})");
    private static final Pattern FILTER = Pattern.compile("/Filter\\s*(/[^\\s/\\[\\]<>()%]+|\\[([^\\]]*)]|\\d+\\s+\\d+\\s+R)");
    private static final Pattern FILTER_NAME = Pattern.compile("/([^\\s/\\[\\]<>()%]+)");
    private static final Pattern IMAGE = Pattern.compile("/Subtype\\s*/Image(?![A-Za-z0-9])");
    private static final Pattern NOT_AN_IMAGE = Pattern.compile("/(ObjStm|XRef|EmbeddedFile)(?![A-Za-z0-9])");

    private final Window in;
    private final long totalLimit;
    /**
     * Höchstens so viele Rohbytes werden insgesamt entpackt – sonst ließe sich die Prüfung mit vielen „stream“ in
     * einem großen Datenstrom endlos beschäftigen. Ein gewöhnliches PDF liest jeden Datenstrom einmal.
     */
    private final long inputBudget;
    private long total;
    private long inputUsed;

    private PdfPrescan(FileChannel channel) throws IOException {
        long size = channel.size();
        this.in = new Window(channel, size);
        this.totalLimit = Math.max(MIN_TOTAL_LIMIT, MAX_RATIO * size);
        this.inputBudget = 4 * size + 16L * 1024 * 1024;
    }

    /** @throws PdfExtractor.OversizedStreamException wenn das PDF entpackt zu groß würde */
    static void check(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            new PdfPrescan(channel).run();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private void run() throws IOException {
        long pos = 0;
        while (true) {
            long keyword = in.indexOf(STREAM, pos);
            if (keyword < 0) {
                return;
            }
            long next = keyword + STREAM.length;
            // „endstream“ enthält „stream“ – davor steht dann ein Buchstabe
            // (weitergesucht wird direkt dahinter – auch in den Daten, deren Länge hier niemand prüft)
            if (keyword == 0 || !isRegular(in.byteAt(keyword - 1))) {
                stream(keyword, dataStart(next));
            }
            pos = next;
        }
    }

    /** Nach „stream“: Leerzeichen, dann CR LF, LF oder CR (PDFBox nimmt es da nicht genau). */
    private long dataStart(long pos) throws IOException {
        while (in.byteAt(pos) == ' ' || in.byteAt(pos) == '\t') {
            pos++;
        }
        if (in.byteAt(pos) == '\r') {
            pos++;
        }
        if (in.byteAt(pos) == '\n') {
            pos++;
        }
        return pos;
    }

    /** Prüft einen Datenstrom mit jeder Filterkette, die in Frage kommt. */
    private void stream(long keyword, long dataStart) throws IOException {
        String before = textBefore(keyword);
        List<List<String>> chains = new ArrayList<>();
        Matcher filter = FILTER.matcher(before);
        List<List<String>> found = new ArrayList<>();
        while (filter.find()) {
            found.add(filters(filter));
        }
        // die zuletzt genannte zuerst – sie gehört am ehesten zu diesem Datenstrom
        for (int i = found.size() - 1; i >= 0 && chains.size() < MAX_CHAINS; i--) {
            if (!found.get(i).isEmpty() && !chains.contains(found.get(i))) {
                chains.add(found.get(i));
            }
        }
        if (chains.stream().noneMatch(chain -> FLATE.contains(chain.getFirst()) || UNKNOWN.equals(chain.getFirst()))) {
            chains.add(List.of(UNKNOWN));
        }
        boolean image = IMAGE.matcher(before).find() && !NOT_AN_IMAGE.matcher(before).find();
        for (List<String> chain : chains) {
            boolean lenient = image && chain.size() == 1 && FLATE.contains(chain.getFirst());
            decode(chain, dataStart, lenient ? MAX_IMAGE_STREAM_BYTES : PdfExtractor.MAX_DECODED_STREAM_BYTES);
        }
    }

    private void decode(List<String> filters, long dataStart, long limit) throws IOException {
        byte[] previous = null;
        for (int i = 0; i < Math.min(filters.size(), MAX_FILTERS); i++) {
            String filter = filters.get(i);
            if (IMAGE_FILTERS.contains(filter)) {
                return;
            }
            boolean last = i == filters.size() - 1 || i == MAX_FILTERS - 1;
            Sink sink = new Sink(limit, !last);
            if (FLATE.contains(filter) || UNKNOWN.equals(filter)) {
                inflate(i == 0 ? new FileSource(dataStart) : new ArraySource(previous), sink);
            } else {
                byte[] input = i == 0 ? rawUntilEndstream(dataStart) : previous;
                if (input == null || !decodeWithPdfBox(filter, input, sink)) {
                    return; // unbekannter Filter: PDFBox entpackt ihn auch nicht
                }
            }
            previous = sink.bytes();
            if (previous != null && previous.length == 0) {
                return;
            }
        }
    }

    /** Was vor „stream“ steht (das Wörterbuch und womöglich mehr), mit aufgelösten #xx in Namen. */
    private String textBefore(long keyword) throws IOException {
        long from = Math.max(0, keyword - LOOKBACK);
        String text = new String(in.bytes(from, (int) (keyword - from)), StandardCharsets.ISO_8859_1);
        // #xx in Namen: /Fl#61teDecode ist für PDFBox FlateDecode
        Matcher escape = NAME_ESCAPE.matcher(text);
        StringBuilder decoded = new StringBuilder();
        while (escape.find()) {
            escape.appendReplacement(decoded,
                    Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(escape.group(1), 16))));
        }
        escape.appendTail(decoded);
        return decoded.toString();
    }

    /** Die Filter eines /Filter-Eintrags in ihrer Reihenfolge. Ein indirekter Eintrag gilt als FlateDecode. */
    private static List<String> filters(Matcher filter) {
        List<String> result = new ArrayList<>();
        String value = filter.group(1);
        if (value.startsWith("/")) {
            result.add(value.substring(1));
        } else if (value.startsWith("[")) {
            Matcher name = FILTER_NAME.matcher(filter.group(2));
            while (name.find()) {
                result.add(name.group(1));
            }
            if (result.isEmpty() && !filter.group(2).isBlank()) {
                result.add(UNKNOWN); // etwa [5 0 R]
            }
        } else {
            result.add(UNKNOWN);
        }
        return result;
    }

    /**
     * Entpackt FlateDecode und zählt dabei. Beginnt der Strom nicht mit einem zlib-Kopf, wird es noch einmal ohne
     * versucht (rohes Deflate) – PDFBox ist bei kaputten Strömen ähnlich nachsichtig.
     */
    private void inflate(Source source, Sink sink) throws IOException {
        boolean fromFile = source instanceof FileSource;
        for (boolean nowrap : new boolean[] {false, true}) {
            Inflater inflater = new Inflater(nowrap);
            try {
                source.rewind();
                long written = sink.count();
                Result result = inflate(inflater, source, sink, fromFile);
                if (result != Result.BROKEN || sink.count() != written) {
                    return;
                }
                // gar nichts entpackt: vielleicht ohne zlib-Kopf
            } finally {
                if (fromFile) {
                    inputUsed += inflater.getBytesRead();
                }
                inflater.end();
            }
        }
    }

    private enum Result { FINISHED, ENDED, BROKEN }

    private Result inflate(Inflater inflater, Source source, Sink sink, boolean fromFile) throws IOException {
        byte[] input = new byte[4096];
        byte[] output = new byte[64 * 1024];
        while (!inflater.finished()) {
            if (inflater.needsDictionary()) {
                return Result.BROKEN;
            }
            if (inflater.needsInput()) {
                int read = source.read(input);
                if (read <= 0) {
                    return Result.ENDED;
                }
                inflater.setInput(input, 0, read);
                if (input.length < 256 * 1024) {
                    input = new byte[input.length * 2]; // kleine Ströme klein lesen, große in großen Stücken
                }
            }
            int n;
            try {
                n = inflater.inflate(output);
            } catch (DataFormatException e) {
                return Result.BROKEN;
            }
            sink.write(output, 0, n);
            if (fromFile && inputUsed + inflater.getBytesRead() > inputBudget) {
                throw new PdfExtractor.OversizedStreamException("PDF ungewöhnlich aufgebaut (Datenströme überlappen)");
            }
            if (n == 0 && !inflater.needsInput() && !inflater.finished() && !inflater.needsDictionary()) {
                return Result.BROKEN;
            }
        }
        return Result.FINISHED;
    }

    /** Für einen anderen ersten Filter (ASCII85, ASCIIHex, LZW …): die Rohdaten bis „endstream“. */
    private byte[] rawUntilEndstream(long dataStart) throws IOException {
        long end = in.indexOf(ENDSTREAM, dataStart);
        long length = (end < 0 ? in.size() : end) - dataStart;
        if (length <= 0 || length > MAX_RAW_STAGE_BYTES) {
            return null;
        }
        return in.bytes(dataStart, (int) length);
    }

    /** @return {@code false}, wenn PDFBox den Filter nicht kennt oder die Daten nicht lesen kann */
    private static boolean decodeWithPdfBox(String filter, byte[] input, Sink sink) throws IOException {
        try {
            FilterFactory.INSTANCE.getFilter(COSName.getPDFName(filter))
                    .decode(new ByteArrayInputStream(input), sink, new COSDictionary(), 0);
            return true;
        } catch (PdfExtractor.OversizedStreamException e) {
            throw e;
        } catch (UncheckedIOException e) {
            if (e.getCause() instanceof PdfExtractor.OversizedStreamException oversized) {
                throw oversized;
            }
            return false;
        } catch (IOException | RuntimeException e) {
            if (sink.exceeded != null) {
                throw sink.exceeded;
            }
            return false;
        }
    }

    private static boolean isRegular(int b) {
        return b >= 0 && switch (b) {
            case 0, 9, 10, 12, 13, 32, '(', ')', '<', '>', '[', ']', '{', '}', '/', '%' -> false;
            default -> true;
        };
    }

    /** Nimmt entpackte Bytes entgegen: zählt sie, bricht an den Grenzen ab und hebt sie nur für eine Folgestufe auf. */
    private final class Sink extends OutputStream {
        private final long limit;
        private final java.io.ByteArrayOutputStream kept;
        private long count;
        PdfExtractor.OversizedStreamException exceeded;

        Sink(long limit, boolean keep) {
            this.limit = limit;
            this.kept = keep ? new java.io.ByteArrayOutputStream() : null;
        }

        long count() {
            return count;
        }

        byte[] bytes() {
            return kept == null ? null : kept.toByteArray();
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            count += len;
            total += len;
            if (count > limit) {
                throw fail("Datenstrom entpackt größer als " + limit + " Bytes");
            }
            if (total > totalLimit) {
                throw fail("PDF entpackt größer als " + totalLimit + " Bytes");
            }
            if (kept != null) {
                kept.write(b, off, len);
            }
        }

        private PdfExtractor.OversizedStreamException fail(String message) {
            exceeded = new PdfExtractor.OversizedStreamException(message);
            return exceeded;
        }
    }

    private interface Source {
        void rewind();

        int read(byte[] buffer) throws IOException;
    }

    private final class FileSource implements Source {
        private final long start;
        private long pos;

        FileSource(long start) {
            this.start = start;
            this.pos = start;
        }

        @Override
        public void rewind() {
            pos = start;
        }

        @Override
        public int read(byte[] buffer) throws IOException {
            int n = in.read(pos, buffer);
            if (n > 0) {
                pos += n;
            }
            return n;
        }
    }

    private static final class ArraySource implements Source {
        private final byte[] data;
        private int pos;

        ArraySource(byte[] data) {
            this.data = data;
        }

        @Override
        public void rewind() {
            pos = 0;
        }

        @Override
        public int read(byte[] buffer) {
            int n = Math.min(buffer.length, data.length - pos);
            System.arraycopy(data, pos, buffer, 0, n);
            pos += n;
            return n == 0 ? -1 : n;
        }
    }

    /** Liest die Datei über ein Fenster von 1 MB – ohne sie ganz in den Speicher zu holen. */
    private static final class Window {
        private final FileChannel channel;
        private final long size;
        private final byte[] buffer = new byte[1024 * 1024];
        private long start;
        private int length;

        Window(FileChannel channel, long size) {
            this.channel = channel;
            this.size = size;
        }

        long size() {
            return size;
        }

        /** Das Byte an der Stelle; -1 hinter dem Ende. */
        int byteAt(long pos) throws IOException {
            if (pos < 0 || pos >= size) {
                return -1;
            }
            if (pos < start || pos >= start + length) {
                load(pos);
            }
            return buffer[(int) (pos - start)] & 0xff;
        }

        int read(long pos, byte[] target) throws IOException {
            if (pos >= size) {
                return -1;
            }
            if (pos < start || pos >= start + length) {
                load(pos);
            }
            int n = (int) Math.min(target.length, start + length - pos);
            System.arraycopy(buffer, (int) (pos - start), target, 0, n);
            return n;
        }

        byte[] bytes(long from, int count) throws IOException {
            byte[] result = new byte[count];
            int done = 0;
            while (done < count) {
                byte[] part = new byte[Math.min(count - done, buffer.length)];
                int n = read(from + done, part);
                if (n <= 0) {
                    break;
                }
                System.arraycopy(part, 0, result, done, n);
                done += n;
            }
            return done == count ? result : java.util.Arrays.copyOf(result, done);
        }

        long indexOf(byte[] pattern, long from) throws IOException {
            for (long pos = from; pos + pattern.length <= size; pos++) {
                if (pos < start || pos + pattern.length > start + length) {
                    load(pos);
                }
                int offset = (int) (pos - start);
                int limit = length - pattern.length;
                for (int i = offset; i <= limit; i++) {
                    if (buffer[i] == pattern[0] && matches(i, pattern)) {
                        return start + i;
                    }
                }
                pos = start + limit; // Fenster durchsucht; weiter mit dem nächsten (überlappend)
            }
            return -1;
        }

        private boolean matches(int offset, byte[] pattern) {
            for (int j = 1; j < pattern.length; j++) {
                if (buffer[offset + j] != pattern[j]) {
                    return false;
                }
            }
            return true;
        }

        private void load(long pos) throws IOException {
            start = pos;
            ByteBuffer target = ByteBuffer.wrap(buffer);
            while (target.hasRemaining()) {
                int n = channel.read(target, start + target.position());
                if (n < 0) {
                    break;
                }
            }
            length = target.position();
        }
    }
}
