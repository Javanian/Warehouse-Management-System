package com.stockflow.procurement;

import com.stockflow.common.PageResponse;
import com.stockflow.common.idempotency.CommandExecutor;
import com.stockflow.identity.Actor;
import com.stockflow.identity.CurrentUser;
import com.stockflow.procurement.PurchaseOrderService.PoDetail;
import com.stockflow.procurement.PurchaseOrderService.PoInput;
import com.stockflow.procurement.PurchaseOrderService.PoSummary;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/purchase-orders")
@Tag(name = "Purchase orders", description = "PO header/items and state transitions DRAFT -> OPEN -> PARTIALLY_RECEIVED -> COMPLETED, CANCELLED")
public class PurchaseOrderController {

    public record CancelRequest(String reason) {}

    private final PurchaseOrderService service;
    private final CommandExecutor executor;
    private final CurrentUser currentUser;

    public PurchaseOrderController(PurchaseOrderService service, CommandExecutor executor, CurrentUser currentUser) {
        this.service = service;
        this.executor = executor;
        this.currentUser = currentUser;
    }

    @GetMapping
    public PageResponse<PoSummary> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) String status, @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) Boolean receivable, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        return service.list(q, status, supplierId, receivable, page, size, sort);
    }

    @GetMapping("/{id}")
    public PoDetail get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public ResponseEntity<PoDetail> create(@RequestHeader(value = CommandExecutor.HEADER, required = false) String key,
            @RequestBody PoInput body) {
        Actor a = currentUser.actor();
        return CommandExecutor.created(executor.run(a, "PO_CREATE", key, body, PoDetail.class,
                () -> service.create(a, body, false)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public PoDetail update(@PathVariable long id, @RequestBody PoInput body) {
        Actor a = currentUser.actor();
        return executor.runPlain(() -> service.update(a, id, body));
    }

    @PostMapping("/{id}/open")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public ResponseEntity<PoDetail> open(@PathVariable long id,
            @RequestHeader(value = CommandExecutor.HEADER, required = false) String key) {
        Actor a = currentUser.actor();
        return CommandExecutor.ok(executor.run(a, "PO_OPEN", key, Map.of("id", id), PoDetail.class,
                () -> service.open(a, id)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public ResponseEntity<PoDetail> cancel(@PathVariable long id,
            @RequestHeader(value = CommandExecutor.HEADER, required = false) String key,
            @RequestBody CancelRequest body) {
        Actor a = currentUser.actor();
        return CommandExecutor.ok(executor.run(a, "PO_CANCEL", key, Map.of("id", id, "reason", String.valueOf(body.reason())),
                PoDetail.class, () -> service.cancel(a, id, body.reason())));
    }
}
