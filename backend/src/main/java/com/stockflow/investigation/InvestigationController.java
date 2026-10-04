package com.stockflow.investigation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/investigations")
@Tag(name = "Investigations", description = "Stock discrepancy evidence package and deterministic explanation drafting")
public class InvestigationController {

    private final InvestigationService service;

    public InvestigationController(InvestigationService service) {
        this.service = service;
    }

    @GetMapping("/candidates")
    @Operation(summary = "List material discrepancy investigation candidates with activity metrics")
    public List<InvestigationService.DiscrepancyCandidate> listCandidates() {
        return service.listCandidates();
    }

    @GetMapping("/{materialCode}")
    @Operation(summary = "Investigate stock movements, assemble ledger evidence timeline, and draft findings")
    public InvestigationService.DiscrepancyReport investigate(@PathVariable String materialCode) {
        return service.investigateMaterial(materialCode);
    }
}
