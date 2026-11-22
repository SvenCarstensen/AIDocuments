package de.aidocuments.document;

import static de.aidocuments.i18n.ValidationMessages.SIZE;

import de.aidocuments.i18n.Messages;
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
            /* wann das Vorschaubild zuletzt geschrieben wurde – die Oberfläche hängt es an die Bildadresse */
            Instant thumbnailAt,
            DocumentSource source,
            String mailSubject,
            String mailFrom,
            Instant mailReceivedAt,
            Instant createdAt,
            Instant processedAt,
            /* wann das PDF zuletzt geändert wurde; null = es ist das Original */
            Instant modifiedAt,
            /* ob eine Kopie des Originals aufbewahrt ist (nur nach einer Änderung) */
            boolean hasOriginal,
            /* das frühere Dokument, dem dieses inhaltlich gleicht; null = kein Duplikat bekannt */
            String duplicateOf,
            /* Trefferausschnitt aus dem Volltext; U+E000/U+E001 markieren Anfang und Ende eines Treffers. */
            String snippet,
            String areaId,
            /* bis wann das Dokument aufzubewahren ist; null = keine Frist */
            java.time.LocalDate retainUntil,
            /* ob die Frist das Löschen heute noch verbietet */
            boolean retentionLocked,
            /* was der Anfragende mit dem Dokument darf (Recht in seinem Bereich) */
            de.aidocuments.area.Permission access) {

        /** Die am Dokument gespeicherte Meldung wird hier in die Sprache des Lesers übersetzt. */
        static DocumentDto of(Document d, String snippet, Messages messages, de.aidocuments.area.Permission access) {
            return new DocumentDto(
                    d.getId(),
                    d.getTitle() != null ? d.getTitle() : d.getOriginalFilename(),
                    d.getOriginalFilename(),
                    d.getStatus(),
                    messages.localize(d.getErrorMessage()),
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
                    d.getThumbnailAt(),
                    d.getSource(),
                    d.getMailSubject(),
                    d.getMailFrom(),
                    d.getMailReceivedAt(),
                    d.getCreatedAt(),
                    d.getProcessedAt(),
                    d.getModifiedAt(),
                    d.getOriginalStoragePath() != null,
                    d.getDuplicateOf(),
                    snippet,
                    d.getAreaId(),
                    d.getRetainUntil(),
                    d.isRetentionLocked(),
                    access);
        }
    }

    public record PageDto<T>(List<T> items, long total, int page, int size) {
    }

    public record UpdateDocumentRequest(
            @Size(max = 500, message = SIZE) String title,
            @Size(max = 4000, message = SIZE) String summary,
            @Size(max = 100, message = SIZE) String category,
            @Size(max = 255, message = SIZE) String correspondent,
            LocalDate documentDate,
            @Size(max = 30, message = SIZE) List<@Size(min = 1, max = 100, message = SIZE) String> tags,
            /* null = Bereich unverändert */
            @Size(max = 64, message = SIZE) String areaId) {
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
