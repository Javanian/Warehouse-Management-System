package com.stockflow.adjustment;

import com.stockflow.common.PageResponse;
import com.stockflow.common.idempotency.CommandExecutor;
import com.stockflow.identity.Actor;
import com.stockflow.identity.CurrentUser;
import com.stockflow.adjustment.AdjustmentService.AdjustmentView;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stock-adjustments")
@Tag(name = "Adjustments", description = "Physical-count adjustment request / approve / reject (no self-approval, stale-count protection)")
public class AdjustmentController {

    private final AdjustmentService service;
    private final CommandExecutor executor;
    private final CurrentUser currentUser;

    public AdjustmentController(AdjustmentService service, CommandExecutor executor, CurrentUser currentUser) {
        this.service = service;
        this.executor = executor;
        this.currentUser = currentUser;
    }

    @GetMapping
    public PageResponse<AdjustmentView> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) String status, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        return service.list(q, status, page, size, sort);
    }

    @GetMapping("/{id}")
    public AdjustmentView get(@PathVariable long id) {
        return service.get(id);
    }

    @GetMapping("/count-snapshot")
    public AdjustmentService.CountSnapshot snapshot(@RequestParam long materialId, @RequestParam long locationId) {
        return service.snapshot(materialId, locationId);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','OPERATOR')")
    public ResponseEntity<AdjustmentView> request(@RequestHeader(value = CommandExecutor.HEADER, required = false) String key,
            @RequestBody AdjustmentService.RequestInput body) {
        Actor a = currentUser.actor();
        return CommandExecutor.created(executor.run(a, "ADJ_REQUEST", key, body, AdjustmentView.class,
                () -> service.request(a, body)));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public ResponseEntity<AdjustmentView> approve(@PathVariable long id,
            @RequestHeader(value = CommandExecutor.HEADER, required = false) String key,
            @RequestBody(required = false) AdjustmentService.DecisionInput body) {
        Actor a = currentUser.actor();
        return CommandExecutor.ok(executor.run(a, "ADJ_APPROVE", key,
                Map.of("id", id, "note", String.valueOf(body == null ? null : body.note())), AdjustmentView.class,
                () -> service.approve(a, id, body)));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public ResponseEntity<AdjustmentView> reject(@PathVariable long id,
            @RequestHeader(value = CommandExecutor.HEADER, required = false) String key,
            @RequestBody AdjustmentService.DecisionInput body) {
        Actor a = currentUser.actor();
        return CommandExecutor.ok(executor.run(a, "ADJ_REJECT", key,
                Map.of("id", id, "note", String.valueOf(body == null ? null : body.note())), AdjustmentView.class,
                () -> service.reject(a, id, body)));
    }
}
