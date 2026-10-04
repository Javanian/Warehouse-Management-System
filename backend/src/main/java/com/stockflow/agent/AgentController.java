package com.stockflow.agent;

import com.stockflow.common.PageQuery;
import com.stockflow.common.PageResponse;
import com.stockflow.identity.Actor;
import com.stockflow.identity.CurrentUser;
import com.stockflow.stock.StockDocumentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping("/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentService.DocumentDraftRecord createDraft(
            CurrentUser user,
            @RequestBody AgentService.CreateDraftRequest req) {
        return agentService.createDraft(user.actor(), req);
    }

    @GetMapping("/drafts")
    public PageResponse<AgentService.DocumentDraftRecord> listDrafts(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return agentService.listDrafts(status, new PageQuery(page, size, "created_at desc"));
    }

    @GetMapping("/drafts/{draftId}")
    public AgentService.DocumentDraftRecord getDraft(@PathVariable String draftId) {
        return agentService.getDraft(draftId);
    }

    @GetMapping("/drafts/{draftId}/traceability")
    public AgentService.TraceabilityReport getTraceability(@PathVariable String draftId) {
        return agentService.getTraceabilityReport(draftId);
    }

    public record MarkPostedRequest(Long goodsReceiptId, String grDocumentNumber) {}

    @PostMapping("/drafts/{draftId}/mark-posted")
    public AgentService.DocumentDraftRecord markPosted(
            CurrentUser user,
            @PathVariable String draftId,
            @RequestBody MarkPostedRequest req) {
        return agentService.markPosted(user.actor(), draftId, req.goodsReceiptId(), req.grDocumentNumber());
    }

    @PostMapping("/drafts/{draftId}/post-and-link")
    public AgentService.DocumentDraftRecord postAndLink(
            CurrentUser user,
            @PathVariable String draftId,
            @RequestBody StockDocumentService.ReceiptInput req) {
        return agentService.postAndLinkDraft(user.actor(), draftId, req);
    }

    @PostMapping("/drafts/{draftId}/review")
    public AgentService.DocumentDraftRecord reviewDraft(
            CurrentUser user,
            @PathVariable String draftId,
            @RequestBody AgentService.ReviewDraftRequest req) {
        return agentService.reviewDraft(user.actor(), draftId, req);
    }

    @GetMapping("/runs/{runId}")
    public AgentService.AgentRunRecord getRun(@PathVariable String runId) {
        return agentService.getRun(runId);
    }

    @GetMapping("/runs/{runId}/tool-calls")
    public List<AgentService.ToolCallRecord> listToolCalls(@PathVariable String runId) {
        return agentService.listToolCalls(runId);
    }

    @GetMapping("/tools/purchase-orders/{poNumber}")
    public AgentService.AgentPurchaseOrderView readPurchaseOrder(
            CurrentUser user,
            @RequestParam String runId,
            @PathVariable String poNumber) {
        return agentService.readPurchaseOrder(user.actor(), runId, poNumber);
    }

    @GetMapping("/tools/materials/{materialCode}")
    public AgentService.AgentMaterialView readMaterialCatalog(
            CurrentUser user,
            @RequestParam String runId,
            @PathVariable String materialCode) {
        return agentService.readMaterialCatalog(user.actor(), runId, materialCode);
    }

    @GetMapping("/tools/inventory-balances")
    public List<AgentService.AgentInventoryBalanceView> readInventoryBalance(
            CurrentUser user,
            @RequestParam String runId,
            @RequestParam(required = false) String materialCode) {
        return agentService.readInventoryBalance(user.actor(), runId, materialCode);
    }
}
