package de.aidocuments.document;

import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class DocumentDtos {

    private DocumentDtos() {
    }

    public record DocumentDto(
            String id,
            String title,
            String originalFilename,
            DocumentStatus status,
            String errorMessage,
            String category,
            String correspondent,
            LocalDate documentDate,
            String summary,
            String language,
            List<String> tags,
            List<KeyFact> keyFacts,
            Integer pageCount,
            long sizeBytes,
            boolean ocrUsed,
            boolean hasThumbnail,
            DocumentSource source,
            String mailSubject,
            String mailFrom,
            Instant mailReceivedAt,
            Instant createdAt,
            Instant processedAt,
            /* Trefferausschnitt aus dem Volltext; U+E000/U+E001 markieren Anfang und Ende eines Treffers. */
            String snippet) {

        static DocumentDto of(Document d, String snippet) {
            return new DocumentDto(
                    d.getId(),
                    d.getTitle() != null ? d.getTitle() : d.getOriginalFilename(),
                    d.getOriginalFilename(),
                    d.getStatus(),
                    d.getErrorMessage(),
                    d.getCategory(),
                    d.getCorrespondent(),
                    d.getDocumentDate(),
                    d.getSummary(),
                    d.getLanguage(),
                    d.getTags().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList(),
                    List.copyOf(d.getKeyFacts()),
                    d.getPageCount(),
                    d.getSizeBytes(),
                    d.isOcrUsed(),
                    d.isHasThumbnail(),
                    d.getSource(),
                    d.getMailSubject(),
                    d.getMailFrom(),
                    d.getMailReceivedAt(),
                    d.getCreatedAt(),
                    d.getProcessedAt(),
                    snippet);
        }
    }

    public record PageDto<T>(List<T> items, long total, int page, int size) {
    }

    public record UpdateDocumentRequest(
            @Size(max = 500) String title,
            @Size(max = 4000) String summary,
            @Size(max = 100) String category,
            @Size(max = 255) String correspondent,
            LocalDate documentDate,
            @Size(max = 30) List<@Size(min = 1, max = 100) String> tags) {
    }

    public record FacetsDto(
            List<FacetCount> categories,
            List<FacetCount> correspondents,
            List<FacetCount> tags) {
    }

    public record UploadResultDto(String filename, String outcome, String documentId, String message) {
    }

    public record TextDto(String content) {
    }
}
