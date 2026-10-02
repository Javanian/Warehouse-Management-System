package com.stockflow.stock;

import com.stockflow.common.PageResponse;
import com.stockflow.common.idempotency.CommandExecutor;
import com.stockflow.identity.Actor;
import com.stockflow.identity.CurrentUser;
import com.stockflow.stock.StockDocumentService.DocSummary;
import com.stockflow.stock.StockDocumentService.DocType;
import com.stockflow.stock.StockDocumentService.DocView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
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
@RequestMapping("/api")
@Tag(name = "Stock documents", description = "Goods receipt (against PO), stock issue, stock transfer, full reversal."
        + " Commands require header Idempotency-Key.")
public class StockDocumentController {

    private final StockDocumentService service;
    private final CommandExecutor executor;
    private final CurrentUser currentUser;

    public StockDocumentController(StockDocumentService service, CommandExecutor executor, CurrentUser currentUser) {
        this.service = service;
        this.executor = executor;
        this.currentUser = currentUser;
    }

    private static final String K = CommandExecutor.HEADER;

    @Operation(summary = "Post a (partial) goods receipt against an OPEN/PARTIALLY_RECEIVED PO")
    @PostMapping("/goods-receipts")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','OPERATOR')")
    public ResponseEntity<DocView> receive(@RequestHeader(value = K, required = false) String key,
            @RequestBody StockDocumentService.ReceiptInput body) {
        Actor a = currentUser.actor();
        return CommandExecutor.created(executor.run(a, "GR_POST", key, body, DocView.class, () -> service.receive(a, body)));
    }

    @GetMapping("/goods-receipts")
    public PageResponse<DocSummary> receipts(@RequestParam(required = false) String q,
            @RequestParam(required = false) String status, @RequestParam(required = false) Long purchaseOrderId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return service.list(DocType.GOODS_RECEIPT, q, status, from, to, purchaseOrderId, page, size, sort);
    }

    @GetMapping("/goods-receipts/{id}")
    public DocView receipt(@PathVariable long id) {
        return service.get(DocType.GOODS_RECEIPT, id);
    }

    @PostMapping("/goods-receipts/{id}/reverse")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public ResponseEntity<DocView> reverseReceipt(@PathVariable long id, @RequestHeader(value = K, required = false) String key,
            @RequestBody StockDocumentService.ReversalInput body) {
        return reverse(DocType.GOODS_RECEIPT, id, key, body);
    }

    @PostMapping("/stock-issues")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','OPERATOR')")
    public ResponseEntity<DocView> issue(@RequestHeader(value = K, required = false) String key,
            @RequestBody StockDocumentService.IssueInput body) {
        Actor a = currentUser.actor();
        return CommandExecutor.created(executor.run(a, "ISSUE_POST", key, body, DocView.class, () -> service.issue(a, body)));
    }

    @GetMapping("/stock-issues")
    public PageResponse<DocSummary> issues(@RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return service.list(DocType.STOCK_ISSUE, q, status, from, to, null, page, size, sort);
    }

    @GetMapping("/stock-issues/{id}")
    public DocView issueDoc(@PathVariable long id) {
        return service.get(DocType.STOCK_ISSUE, id);
    }

    @PostMapping("/stock-issues/{id}/reverse")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public ResponseEntity<DocView> reverseIssue(@PathVariable long id, @RequestHeader(value = K, required = false) String key,
            @RequestBody StockDocumentService.ReversalInput body) {
        return reverse(DocType.STOCK_ISSUE, id, key, body);
    }

    @PostMapping("/stock-transfers")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','OPERATOR')")
    public ResponseEntity<DocView> transfer(@RequestHeader(value = K, required = false) String key,
            @RequestBody StockDocumentService.TransferInput body) {
        Actor a = currentUser.actor();
        return CommandExecutor.created(executor.run(a, "TRANSFER_POST", key, body, DocView.class,
                () -> service.transfer(a, body)));
    }

    @GetMapping("/stock-transfers")
    public PageResponse<DocSummary> transfers(@RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return service.list(DocType.STOCK_TRANSFER, q, status, from, to, null, page, size, sort);
    }

    @GetMapping("/stock-transfers/{id}")
    public DocView transferDoc(@PathVariable long id) {
        return service.get(DocType.STOCK_TRANSFER, id);
    }

    @PostMapping("/stock-transfers/{id}/reverse")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public ResponseEntity<DocView> reverseTransfer(@PathVariable long id, @RequestHeader(value = K, required = false) String key,
            @RequestBody StockDocumentService.ReversalInput body) {
        return reverse(DocType.STOCK_TRANSFER, id, key, body);
    }

    private ResponseEntity<DocView> reverse(DocType t, long id, String key, StockDocumentService.ReversalInput body) {
        Actor a = currentUser.actor();
        return CommandExecutor.ok(executor.run(a, "REVERSE_" + t.name(), key,
                Map.of("id", id, "reason", String.valueOf(body == null ? null : body.reason())), DocView.class,
                () -> service.reverse(a, t, id, body)));
    }
}
