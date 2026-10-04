package com.stockflow.dataquality;

import com.stockflow.common.PageQuery;
import com.stockflow.common.PageResponse;
import com.stockflow.identity.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/data-quality")
@Tag(name = "Data Quality", description = "Rule-based master catalog data quality review queue")
public class DataQualityController {

    private final DataQualityService service;

    public DataQualityController(DataQualityService service) {
        this.service = service;
    }

    @PostMapping("/scan")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    @Operation(summary = "Run deterministic catalog quality rules scan")
    public int scan() {
        return service.scanCatalog();
    }

    @GetMapping("/summary")
    @Operation(summary = "Get counts of open quality issues by severity and rule")
    public DataQualityService.QualitySummary summary() {
        return service.getSummary();
    }

    @GetMapping("/issues")
    @Operation(summary = "List master data quality issues")
    public PageResponse<DataQualityService.QualityIssue> list(
            @RequestParam(required = false, defaultValue = "OPEN") String status,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String ruleCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "created_at desc") String sort) {
        return service.listIssues(status, severity, ruleCode, new PageQuery(page, size, "created_at desc"));
    }

    @PostMapping("/issues/{issueKey}/resolve")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    @Operation(summary = "Resolve or ignore a data quality issue")
    public DataQualityService.QualityIssue resolve(
            CurrentUser user,
            @PathVariable String issueKey,
            @RequestBody DataQualityService.ResolveRequest req) {
        return service.resolveIssue(user.actor(), issueKey, req);
    }
}
