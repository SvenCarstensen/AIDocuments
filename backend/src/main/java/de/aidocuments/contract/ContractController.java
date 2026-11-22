package de.aidocuments.contract;

import de.aidocuments.contract.ContractService.ContractDto;
import de.aidocuments.contract.ContractService.ContractRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Vertragsübersicht und die Vertragsangaben eines Dokuments. Ansehen: wer das Dokument sieht; ändern: wer es bearbeiten darf. */
@RestController
public class ContractController {

    private final ContractService service;

    public ContractController(ContractService service) {
        this.service = service;
    }

    @GetMapping("/api/contracts")
    public List<ContractDto> list() {
        return service.list();
    }

    /** 204, wenn zum Dokument keine Vertragsangaben erfasst sind. */
    @GetMapping("/api/documents/{id}/contract")
    public ResponseEntity<ContractDto> get(@PathVariable String id) {
        ContractDto contract = service.forDocument(id);
        return contract == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(contract);
    }

    @PutMapping("/api/documents/{id}/contract")
    public ContractDto save(@PathVariable String id, @Valid @RequestBody ContractRequest request) {
        return service.save(id, request);
    }

    @DeleteMapping("/api/documents/{id}/contract")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        service.delete(id);
    }
}
