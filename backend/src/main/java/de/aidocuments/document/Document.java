package de.aidocuments.document;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.mongodb.core.index.Indexed;

@org.springframework.data.mongodb.core.mapping.Document(collection = "documents")
public class Document {

    @Id
    private String id;
    private String originalFilename;
    private String storagePath;
    @Indexed(unique = true)
    private String sha256;
    private long sizeBytes;
    private Integer pageCount;
    private DocumentSource source;
    @Indexed
    private DocumentStatus status;
    /**
     * Fehlermeldung oder Hinweis der Verarbeitung – sprachneutral abgelegt (siehe MsgCodec) und beim Ausliefern
     * übersetzt. Dokumente aus älteren Versionen enthalten hier deutschen Klartext; der bleibt, wie er ist.
     */
    private String errorMessage;
    private String title;
    private String summary;
    private String category;
    private String correspondent;
    /** ISO-Datum (JJJJ-MM-TT) als Text – zeitzonenunabhängig und trotzdem sortierbar. */
    private String documentDate;
    private String language;
    private boolean ocrUsed;
    private boolean hasThumbnail;
    private String mailAccountId;
    private String mailSubject;
    private String mailFrom;
    private Instant mailReceivedAt;
    @Indexed
    private Instant createdAt;
    private Instant processedAt;
    /** Kopie der Datei, wie sie ankam – angelegt, sobald jemand das PDF ändert; null = nie geändert. */
    private String originalStoragePath;
    /** Wann das PDF zuletzt geändert wurde; null = es ist noch das Original. */
    private Instant modifiedAt;
    /** Fingerabdruck des Inhalts für die Duplikaterkennung; null = (noch) keiner oder zu wenig Text. */
    private ContentFingerprint fingerprint;
    /** Das frühere Dokument, dem dieses inhaltlich gleicht; null = kein Duplikat bekannt. */
    @Indexed(sparse = true)
    private String duplicateOf;
    /** Dokumente, von denen der Anwender bestätigt hat, dass dieses kein Duplikat ist. */
    private Set<String> notDuplicateOf = new LinkedHashSet<>();
    private Set<String> tags = new LinkedHashSet<>();
    private List<KeyFact> keyFacts = new ArrayList<>();

    @PersistenceCreator
    protected Document() {
    }

    public Document(String id, String originalFilename, String storagePath, String sha256, long sizeBytes,
                    DocumentSource source) {
        this.id = id;
        this.originalFilename = originalFilename;
        this.storagePath = storagePath;
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
        this.source = source;
        this.status = DocumentStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public String getSha256() {
        return sha256;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public Integer getPageCount() {
        return pageCount;
    }

    public void setPageCount(Integer pageCount) {
        this.pageCount = pageCount;
    }

    public DocumentSource getSource() {
        return source;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public void setStatus(DocumentStatus status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getCorrespondent() {
        return correspondent;
    }

    public void setCorrespondent(String correspondent) {
        this.correspondent = correspondent;
    }

    public LocalDate getDocumentDate() {
        return documentDate == null ? null : LocalDate.parse(documentDate);
    }

    public void setDocumentDate(LocalDate date) {
        this.documentDate = date == null ? null : date.toString();
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public boolean isOcrUsed() {
        return ocrUsed;
    }

    public void setOcrUsed(boolean ocrUsed) {
        this.ocrUsed = ocrUsed;
    }

    public boolean isHasThumbnail() {
        return hasThumbnail;
    }

    public void setHasThumbnail(boolean hasThumbnail) {
        this.hasThumbnail = hasThumbnail;
    }

    public String getMailAccountId() {
        return mailAccountId;
    }

    public String getMailSubject() {
        return mailSubject;
    }

    public String getMailFrom() {
        return mailFrom;
    }

    public Instant getMailReceivedAt() {
        return mailReceivedAt;
    }

    public void setMailOrigin(String mailAccountId, String subject, String from, Instant receivedAt) {
        this.mailAccountId = mailAccountId;
        this.mailSubject = subject;
        this.mailFrom = from;
        this.mailReceivedAt = receivedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }

    public String getOriginalStoragePath() {
        return originalStoragePath;
    }

    public void setOriginalStoragePath(String originalStoragePath) {
        this.originalStoragePath = originalStoragePath;
    }

    public Instant getModifiedAt() {
        return modifiedAt;
    }

    public void setModifiedAt(Instant modifiedAt) {
        this.modifiedAt = modifiedAt;
    }

    public ContentFingerprint getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(ContentFingerprint fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getDuplicateOf() {
        return duplicateOf;
    }

    public void setDuplicateOf(String duplicateOf) {
        this.duplicateOf = duplicateOf;
    }

    public Set<String> getNotDuplicateOf() {
        if (notDuplicateOf == null) {
            notDuplicateOf = new LinkedHashSet<>();
        }
        return notDuplicateOf;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public Set<String> getTags() {
        return tags;
    }

    public void replaceTags(Iterable<String> newTags) {
        tags = new LinkedHashSet<>();
        newTags.forEach(tags::add);
    }

    public List<KeyFact> getKeyFacts() {
        return keyFacts;
    }

    public void replaceKeyFacts(List<KeyFact> newFacts) {
        keyFacts = new ArrayList<>(newFacts);
    }
}
