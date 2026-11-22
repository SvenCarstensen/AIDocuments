package de.aidocuments.retention;

import static de.aidocuments.i18n.ValidationMessages.NOT_BLANK;
import static de.aidocuments.i18n.ValidationMessages.SIZE;

import de.aidocuments.document.DocumentRepository;
import de.aidocuments.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Aufbewahrungsregeln je Kategorie – nur für Administratoren (unter /api/settings, siehe SecurityConfig), Änderungen
 * mit frischer Bestätigung.
 *
 * <p>Eine Regel sperrt die Dokumente ihrer Kategorie bis zum Ende der Frist gegen das Löschen. Wird sie verkürzt
 * oder gelöscht, bleiben bereits gesetzte Fristen bestehen: Die Sperre lässt sich nicht nachträglich aufheben.
 */
@RestController
@RequestMapping("/api/settings/retention")
public class RetentionController {

    private static final Logger log = LoggerFactory.getLogger(RetentionController.class);

    public record RuleDto(String id, String category, int years, long documentCount) {
    }

    public record RuleRequest(@NotBlank(message = NOT_BLANK) @Size(max = 100, message = SIZE) String category,
                              @Min(value = RetentionRule.MIN_YEARS, message = "{validation.retention.years}")
                              @Max(value = RetentionRule.MAX_YEARS, message = "{validation.retention.years}")
                              int years) {
    }

    private final RetentionRules rules;
    private final DocumentRepository documents;

    public RetentionController(RetentionRules rules, DocumentRepository documents) {
        this.rules = rules;
        this.documents = documents;
    }

    @GetMapping
    public List<RuleDto> list() {
        return rules.findAll().stream().map(this::toDto).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RuleDto create(@Valid @RequestBody RuleRequest request) {
        String category = request.category().strip();
        if (rules.findByCategory(category).isPresent()) {
            throw ApiException.conflict("error.retention.categoryTaken");
        }
        RetentionRule rule = new RetentionRule(UUID.randomUUID().toString(), category, request.years(), Instant.now());
        return saveAndApply(rule);
    }

    @PutMapping("/{id}")
    public RuleDto update(@PathVariable String id, @Valid @RequestBody RuleRequest request) {
        RetentionRule existing = require(id);
        String category = request.category().strip();
        rules.findByCategory(category).filter(other -> !other.id().equals(id)).ifPresent(other -> {
            throw ApiException.conflict("error.retention.categoryTaken");
        });
        return saveAndApply(new RetentionRule(id, category, request.years(), existing.createdAt()));
    }

    /** Die Regel entfällt; bereits gesperrte Dokumente bleiben bis zum Ende ihrer Frist gesperrt. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        RetentionRule rule = require(id);
        rules.delete(id);
        log.info("Aufbewahrungsregel für „{}“ ({} Jahre) entfernt", rule.category(), rule.years());
    }

    private RuleDto saveAndApply(RetentionRule rule) {
        rules.save(rule);
        int affected = documents.applyRetentionRule(rule.category());
        log.info("Aufbewahrungsregel: „{}“ {} Jahre ({} Dokumente)", rule.category(), rule.years(), affected);
        return toDto(rule);
    }

    private RetentionRule require(String id) {
        return rules.findById(id).orElseThrow(() -> ApiException.notFound("error.retention.notFound"));
    }

    private RuleDto toDto(RetentionRule rule) {
        return new RuleDto(rule.id(), rule.category(), rule.years(), documents.countByCategory(rule.category()));
    }
}
