package com.stockflow.agent;

import com.stockflow.audit.AuditService;
import com.stockflow.common.ApiException;
import com.stockflow.common.Json;
import com.stockflow.common.PageQuery;
import com.stockflow.common.PageResponse;
import com.stockflow.identity.Actor;
import com.stockflow.identity.Role;
import com.stockflow.stock.StockDocumentService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
public class AgentService {

    public record AgentRunRecord(
            long id,
            String runId,
            long agentId,
            String taskType,
            String status,
            Long triggeredBy,
            Instant startedAt,
            Instant finishedAt,
            Long latencyMs,
            String modelName,
            String promptVersion,
            String inputHash,
            String errorReason,
            String summary
    ) {}

    public record ToolCallRecord(
            long id,
            String runId,
            int callSequence,
            String toolName,
            String parameters,
            String resultStatus,
            String resultSummary,
            Instant executedAt
    ) {}

    public record DocumentDraftRecord(
            long id,
            String draftId,
            String runId,
            String sourceDocumentPath,
            String sourceDocumentSha256,
            String detectedPoNumber,
            String detectedDeliveryNote,
            String detectedSupplierCode,
            LocalDate detectedDeliveryDate,
            String status,
            BigDecimal confidenceScore,
            String validationOutcome,
            String validationReason,
            String provenance,
            String lineItems,
            Instant createdAt,
            Long reviewedBy,
            Instant reviewedAt,
            String humanDecision,
            String postedGrDocumentNumber,
            Long goodsReceiptId,
            String reviewedPayload,
            String reviewNotes,
            String payloadHash
    ) {}

    public record CreateDraftRequest(
            String runId,
            String sourceDocumentPath,
            String sourceDocumentSha256,
            String detectedPoNumber,
            String detectedDeliveryNote,
            String detectedSupplierCode,
            LocalDate detectedDeliveryDate,
            BigDecimal confidenceScore,
            String validationOutcome,
            String validationReason,
            Map<String, Object> provenance,
            List<Map<String, Object>> lineItems
    ) {}

    public record ReviewedReceiptLine(
            long purchaseOrderItemId,
            String materialCode,
            String uomCode,
            BigDecimal quantity
    ) {}

    public record ReviewedReceiptPayload(
            long purchaseOrderId,
            String poNumber,
            String deliveryNoteNumber,
            String supplierCode,
            LocalDate deliveryDate,
            List<ReviewedReceiptLine> lines
    ) {}

    public record ReviewDraftRequest(
            String decision,
            String notes,
            ReviewedReceiptPayload modifications
    ) {
        public ReviewDraftRequest(String decision, String notes) {
            this(decision, notes, null);
        }
    }

    public record StartRunRequest(
            String runId,
            String taskType,
            String modelName,
            String promptVersion,
            String inputHash
    ) {}

    public record RecordToolCallRequest(
            String runId,
            int callSequence,
            String toolName,
            Map<String, Object> parameters,
            String resultStatus,
            Map<String, Object> resultSummary
    ) {}

    public record AgentPurchaseOrderView(
            long id,
            String poNumber,
            String supplierCode,
            String supplierName,
            LocalDate poDate,
            LocalDate expectedDate,
            String status
    ) {}

    public record AgentMaterialView(
            long id,
            String code,
            String name,
            String description,
            String baseUom,
            boolean active
    ) {}

    public record AgentInventoryBalanceView(
            long id,
            String materialCode,
            String warehouseCode,
            String locationCode,
            BigDecimal quantity,
            Instant updatedAt
    ) {}

    public record TraceabilityReport(
            String draftId,
            String runId,
            String agentUsername,
            Instant runStartedAt,
            Instant runFinishedAt,
            String modelName,
            String promptVersion,
            String inputHash,
            int toolCallCount,
            String sourceDocumentPath,
            String sourceDocumentSha256,
            String detectedPoNumber,
            String detectedDeliveryNote,
            String detectedSupplierCode,
            BigDecimal confidenceScore,
            String validationOutcome,
            String status,
            String humanDecision,
            String reviewedByUsername,
            Instant reviewedAt,
            String postedGrDocumentNumber,
            Long goodsReceiptId,
            String reviewedPayload,
            String reviewNotes,
            String payloadHash
    ) {}

    private final JdbcClient adminJdbc;
    private final JdbcClient agentJdbc;
    private final Json json;
    private final AuditService audit;
    private final AgentToolDispatcher dispatcher;
    private final StockDocumentService stockDocuments;

    public AgentService(
            @Qualifier("jdbcClient") JdbcClient adminJdbc,
            @Qualifier("agentJdbcClient") JdbcClient agentJdbc,
            Json json,
            AuditService audit,
            AgentToolDispatcher dispatcher,
            StockDocumentService stockDocuments) {
        this.adminJdbc = adminJdbc;
        this.agentJdbc = agentJdbc;
        this.json = json;
        this.audit = audit;
        this.dispatcher = dispatcher;
        this.stockDocuments = stockDocuments;
    }

    public AgentToolDispatcher getDispatcher() {
        return dispatcher;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public AgentRunRecord startRun(Actor actor, StartRunRequest req) {
        if (actor.role() != Role.ADMIN && actor.role() != Role.SUPERVISOR && actor.role() != Role.OPERATOR && actor.role() != Role.AGENT) {
            throw ApiException.forbidden("FORBIDDEN", "Role " + actor.role() + " is not authorized to start agent runs");
        }

        if (req.runId() == null || req.runId().isBlank()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "runId is required");
        }
        if (req.taskType() == null || req.taskType().isBlank()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "taskType is required");
        }

        long existingCount = adminJdbc.sql("select count(*) from agent_runs where run_id = :runId")
                .param("runId", req.runId())
                .query(Long.class)
                .single();
        if (existingCount > 0) {
            throw ApiException.conflict("DUPLICATE_RUN_ID", "Agent run with id " + req.runId() + " already exists");
        }

        long agentUserId = actor.id();
        Long humanTriggerId = actor.role() == Role.AGENT ? null : actor.id();

        agentJdbc.sql("insert into agent_runs (run_id, agent_id, task_type, status, triggered_by, started_at, model_name, prompt_version, input_hash) "
                + "values (:runId, :agentId, :taskType, 'RUNNING', :triggerId, now(), :model, :ver, :hash)")
                .param("runId", req.runId())
                .param("agentId", agentUserId)
                .param("taskType", req.taskType())
                .param("triggerId", humanTriggerId)
                .param("model", req.modelName())
                .param("ver", req.promptVersion())
                .param("hash", req.inputHash())
                .update();

        audit.record(actor, "AGENT_RUN_START", "AGENT_RUN", req.runId(), Map.of(
                "taskType", req.taskType(),
                "modelName", req.modelName() != null ? req.modelName() : "unknown",
                "promptVersion", req.promptVersion() != null ? req.promptVersion() : "unknown"
        ));

        return getRun(req.runId());
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public AgentRunRecord finishRun(String runId, String status, Long latencyMs, String errorReason, Map<String, Object> summary) {
        String normalizedStatus = status != null ? status.toUpperCase() : "COMPLETED";
        if (!List.of("COMPLETED", "FAILED", "ABSTAINED").contains(normalizedStatus)) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Invalid terminal status for run: " + status);
        }

        int updated = agentJdbc.sql("update agent_runs set status = :st, finished_at = now(), latency_ms = :lat, "
                + "error_reason = :err, summary = :summary::jsonb where run_id = :runId and status = 'RUNNING'")
                .param("st", normalizedStatus)
                .param("lat", latencyMs)
                .param("err", errorReason)
                .param("summary", summary != null ? json.write(summary) : null)
                .param("runId", runId)
                .update();

        if (updated == 0) {
            String currentStatus = agentJdbc.sql("select status from agent_runs where run_id = :runId")
                    .param("runId", runId)
                    .query(String.class)
                    .optional()
                    .orElseThrow(() -> ApiException.notFound("Agent run " + runId));
            throw ApiException.conflict("INVALID_STATE_TRANSITION",
                    "Run " + runId + " is in status " + currentStatus + " and cannot be transitioned to " + normalizedStatus);
        }

        var runMeta = adminJdbc.sql("select r.agent_id, u_agent.username as agent_username, r.triggered_by, u_trig.username as trigger_username "
                        + "from agent_runs r "
                        + "join users u_agent on u_agent.id = r.agent_id "
                        + "left join users u_trig on u_trig.id = r.triggered_by "
                        + "where r.run_id = :runId")
                .param("runId", runId)
                .query((rs, rowNum) -> Map.of(
                        "agent_id", rs.getLong("agent_id"),
                        "agent_username", rs.getString("agent_username"),
                        "triggered_by", rs.getObject("triggered_by") != null ? rs.getLong("triggered_by") : 0L,
                        "trigger_username", rs.getString("trigger_username") != null ? rs.getString("trigger_username") : "SYSTEM"
                ))
                .optional()
                .orElse(null);

        Long agentId = runMeta != null ? (Long) runMeta.get("agent_id") : 0L;
        String agentUsername = runMeta != null ? (String) runMeta.get("agent_username") : "agent_system";
        Actor runActor = new Actor(agentId, agentUsername, Role.AGENT);

        Map<String, Object> auditDetails = new HashMap<>();
        auditDetails.put("status", normalizedStatus);
        auditDetails.put("latencyMs", latencyMs != null ? latencyMs : 0);
        auditDetails.put("errorReason", errorReason != null ? errorReason : "");
        if (runMeta != null) {
            auditDetails.put("agent_id", runMeta.get("agent_id"));
            auditDetails.put("agent_username", runMeta.get("agent_username"));
            auditDetails.put("triggered_by", runMeta.get("triggered_by"));
            auditDetails.put("trigger_username", runMeta.get("trigger_username"));
        }

        audit.record(runActor, "AGENT_RUN_FINISH", "AGENT_RUN", runId, auditDetails);

        return getRun(runId);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public DocumentDraftRecord createDraft(Actor actor, CreateDraftRequest req) {
        if (actor.role() != Role.ADMIN && actor.role() != Role.SUPERVISOR && actor.role() != Role.OPERATOR && actor.role() != Role.AGENT) {
            throw ApiException.forbidden("FORBIDDEN", "Role " + actor.role() + " is not authorized to create drafts");
        }

        if (actor.role() == Role.AGENT) {
            if (req.runId() == null || req.runId().isBlank()) {
                throw ApiException.badRequest("AGENT_RUN_REQUIRED", "Agent actor must provide a valid runId to create drafts");
            }
        }

        if (req.sourceDocumentPath() == null || req.sourceDocumentPath().isBlank()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "sourceDocumentPath is required");
        }
        String draftId = "DR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        if (actor.role() == Role.AGENT || (req.runId() != null && !req.runId().isBlank())) {
            return dispatcher.dispatch(actor, req.runId(), "CREATE_DOCUMENT_DRAFT",
                    Map.of("sourceDocumentPath", req.sourceDocumentPath(), "outcome", req.validationOutcome() != null ? req.validationOutcome() : "REVIEW"),
                    () -> insertDraftInternal(actor, draftId, req));
        } else {
            return insertDraftInternal(actor, draftId, req);
        }
    }

    private DocumentDraftRecord insertDraftInternal(Actor actor, String draftId, CreateDraftRequest req) {
        String outcome = req.validationOutcome() != null ? req.validationOutcome().toUpperCase() : "REVIEW";
        if (!List.of("VALID", "INVALID", "REVIEW").contains(outcome)) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Invalid validation outcome: " + outcome);
        }

        adminJdbc.sql("insert into document_drafts (draft_id, run_id, source_document_path, source_document_sha256, "
                + "detected_po_number, detected_delivery_note, detected_supplier_code, detected_delivery_date, "
                + "status, confidence_score, validation_outcome, validation_reason, provenance, line_items) "
                + "values (:draftId, :runId, :path, :sha, :po, :dn, :sup, :dt, 'DRAFT', :conf, :outcome, :reason, "
                + ":prov::jsonb, :lines::jsonb)")
                .param("draftId", draftId)
                .param("runId", req.runId())
                .param("path", req.sourceDocumentPath())
                .param("sha", req.sourceDocumentSha256() != null ? req.sourceDocumentSha256() : "UNKNOWN")
                .param("po", req.detectedPoNumber())
                .param("dn", req.detectedDeliveryNote())
                .param("sup", req.detectedSupplierCode())
                .param("dt", req.detectedDeliveryDate())
                .param("conf", req.confidenceScore())
                .param("outcome", outcome)
                .param("reason", req.validationReason())
                .param("prov", req.provenance() != null ? json.write(req.provenance()) : null)
                .param("lines", json.write(req.lineItems() != null ? req.lineItems() : List.of()))
                .update();

        audit.record(actor, "AGENT_CREATE_DRAFT", "DOCUMENT_DRAFT", draftId, Map.of(
                "runId", req.runId() != null ? req.runId() : "NONE",
                "sourceDocumentPath", req.sourceDocumentPath(),
                "confidenceScore", req.confidenceScore() != null ? req.confidenceScore() : BigDecimal.ZERO,
                "validationOutcome", outcome,
                "detectedPoNumber", req.detectedPoNumber() != null ? req.detectedPoNumber() : "NONE"
        ));

        return getDraft(draftId);
    }

    public DocumentDraftRecord getDraft(String draftId) {
        return getDraft(draftId, adminJdbc);
    }

    private DocumentDraftRecord getDraft(String draftId, JdbcClient jdbc) {
        return jdbc.sql("select id, draft_id, run_id, source_document_path, source_document_sha256, "
                + "detected_po_number, detected_delivery_note, detected_supplier_code, detected_delivery_date, "
                + "status, confidence_score, validation_outcome, validation_reason, provenance::text, line_items::text, "
                + "created_at, reviewed_by, reviewed_at, human_decision, posted_gr_document_number, goods_receipt_id, "
                + "reviewed_payload::text, review_notes, payload_hash "
                + "from document_drafts where draft_id = :draftId")
                .param("draftId", draftId)
                .query(DocumentDraftRecord.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Document draft " + draftId));
    }

    public PageResponse<DocumentDraftRecord> listDrafts(String status, PageQuery page) {
        String filter = status != null && !status.isBlank() ? "where status = :status" : "";
        List<DocumentDraftRecord> items = adminJdbc.sql("select id, draft_id, run_id, source_document_path, source_document_sha256, "
                + "detected_po_number, detected_delivery_note, detected_supplier_code, detected_delivery_date, "
                + "status, confidence_score, validation_outcome, validation_reason, provenance::text, line_items::text, "
                + "created_at, reviewed_by, reviewed_at, human_decision, posted_gr_document_number, goods_receipt_id, "
                + "reviewed_payload::text, review_notes, payload_hash "
                + "from document_drafts " + filter + " order by created_at desc limit :limit offset :offset")
                .param("status", status)
                .param("limit", page.size())
                .param("offset", page.offset())
                .query(DocumentDraftRecord.class)
                .list();

        long total = adminJdbc.sql("select count(*) from document_drafts " + filter)
                .param("status", status)
                .query(Long.class)
                .single();

        return PageResponse.of(items, page, total);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public DocumentDraftRecord reviewDraft(Actor humanReviewer, String draftId, ReviewDraftRequest req) {
        if (humanReviewer.role() == Role.AGENT) {
            throw ApiException.forbidden("AGENT_CANNOT_APPROVE_DRAFT", "Agent identity cannot approve drafts");
        }
        if (humanReviewer.role() != Role.ADMIN && humanReviewer.role() != Role.SUPERVISOR && humanReviewer.role() != Role.OPERATOR) {
            throw ApiException.forbidden("FORBIDDEN", "Role " + humanReviewer.role() + " is not authorized to review drafts");
        }

        String currentStatus = adminJdbc.sql("select status from document_drafts where draft_id = :draftId for update")
                .param("draftId", draftId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Document draft " + draftId));

        if (!List.of("DRAFT", "UNDER_REVIEW").contains(currentStatus)) {
            throw ApiException.conflict("INVALID_STATE_TRANSITION",
                    "Draft " + draftId + " is in status " + currentStatus + " and cannot be reviewed again");
        }

        String dec = req.decision() != null ? req.decision().toUpperCase() : "ACCEPTED";
        if (!List.of("ACCEPTED", "REJECTED", "MODIFIED").contains(dec)) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Invalid human decision: " + dec);
        }
        String newStatus = dec.equals("REJECTED") ? "REJECTED" : "CONFIRMED";

        DocumentDraftRecord cur = getDraft(draftId);
        ReviewedReceiptPayload approvedPayload;

        if ("REJECTED".equals(dec)) {
            approvedPayload = null;
        } else if ("MODIFIED".equals(dec)) {
            if (req.notes() == null || req.notes().isBlank()) {
                throw ApiException.badRequest("VALIDATION_ERROR", "Review notes explaining modification reason are required when decision is MODIFIED");
            }
            if (req.modifications() == null) {
                throw ApiException.badRequest("VALIDATION_ERROR", "Modifications payload is required when decision is MODIFIED");
            }
            ReviewedReceiptPayload mod = req.modifications();
            if (mod.lines() == null || mod.lines().isEmpty()) {
                throw ApiException.badRequest("VALIDATION_ERROR", "Modified payload lines cannot be empty");
            }

            var poInfo = adminJdbc.sql("select id, po_number, status from purchase_orders where id = :id")
                    .param("id", mod.purchaseOrderId())
                    .query((rs, rowNum) -> Map.of(
                            "id", rs.getLong("id"),
                            "po_number", rs.getString("po_number"),
                            "status", rs.getString("status")
                    ))
                    .optional()
                    .orElseThrow(() -> ApiException.badRequest("PO_NOT_FOUND", "Purchase Order " + mod.purchaseOrderId() + " not found"));

            if ("CANCELLED".equalsIgnoreCase((String) poInfo.get("status"))) {
                throw ApiException.badRequest("CANCELLED_PO", "Cannot approve receipt for CANCELLED purchase order");
            }
            if (!mod.poNumber().equalsIgnoreCase((String) poInfo.get("po_number"))) {
                throw ApiException.badRequest("PO_MISMATCH", "PO number " + mod.poNumber() + " does not match ID " + mod.purchaseOrderId());
            }

            Set<Long> seenItems = new HashSet<>();
            for (ReviewedReceiptLine l : mod.lines()) {
                if (l.quantity() == null || l.quantity().compareTo(BigDecimal.ZERO) <= 0) {
                    throw ApiException.badRequest("INVALID_QUANTITY", "Line quantity must be positive");
                }
                if (!seenItems.add(l.purchaseOrderItemId())) {
                    throw ApiException.badRequest("DUPLICATE_PO_ITEM", "Duplicate PO item " + l.purchaseOrderItemId() + " in review payload");
                }
                var itemRow = adminJdbc.sql("select poi.id, m.code as mat_code, poi.uom_code, "
                                + "(poi.ordered_quantity - poi.received_quantity) as outstanding "
                                + "from purchase_order_items poi join materials m on m.id = poi.material_id "
                                + "where poi.id = :id and poi.purchase_order_id = :poId")
                        .param("id", l.purchaseOrderItemId())
                        .param("poId", mod.purchaseOrderId())
                        .query((rs, rowNum) -> Map.of(
                                "mat_code", rs.getString("mat_code"),
                                "uom_code", rs.getString("uom_code"),
                                "outstanding", rs.getBigDecimal("outstanding")
                        ))
                        .optional()
                        .orElseThrow(() -> ApiException.badRequest("INVALID_PO_ITEM", "PO item " + l.purchaseOrderItemId() + " not found for PO " + mod.purchaseOrderId()));

                if (!l.materialCode().equalsIgnoreCase((String) itemRow.get("mat_code"))) {
                    throw ApiException.badRequest("MATERIAL_MISMATCH", "Material " + l.materialCode() + " does not match PO item " + itemRow.get("mat_code"));
                }
                if (!l.uomCode().equalsIgnoreCase((String) itemRow.get("uom_code"))) {
                    throw ApiException.badRequest("UOM_MISMATCH", "UoM " + l.uomCode() + " does not match PO item " + itemRow.get("uom_code"));
                }
                BigDecimal outstanding = (BigDecimal) itemRow.get("outstanding");
                if (l.quantity().compareTo(outstanding) > 0) {
                    throw ApiException.badRequest("OVER_RECEIPT", "Quantity " + l.quantity() + " exceeds outstanding " + outstanding + " for " + l.materialCode());
                }
            }
            approvedPayload = mod;
        } else {
            // ACCEPTED
            if (cur.detectedPoNumber() == null || cur.detectedPoNumber().isBlank()) {
                throw ApiException.badRequest("MISSING_PO", "Cannot accept draft with no detected PO number");
            }
            var poInfo = adminJdbc.sql("select po.id, po.po_number, po.status, s.code as supplier_code "
                            + "from purchase_orders po join suppliers s on s.id = po.supplier_id "
                            + "where upper(po.po_number) = upper(:po) limit 1")
                    .param("po", cur.detectedPoNumber())
                    .query((rs, rowNum) -> Map.of(
                            "id", rs.getLong("id"),
                            "po_number", rs.getString("po_number"),
                            "status", rs.getString("status"),
                            "supplier_code", rs.getString("supplier_code")
                    ))
                    .optional()
                    .orElseThrow(() -> ApiException.badRequest("PO_NOT_FOUND", "Purchase Order " + cur.detectedPoNumber() + " not found"));

            if ("CANCELLED".equalsIgnoreCase((String) poInfo.get("status"))) {
                throw ApiException.badRequest("CANCELLED_PO", "Cannot accept draft for CANCELLED purchase order");
            }
            Long poId = (Long) poInfo.get("id");
            String canonicalPoNum = (String) poInfo.get("po_number");
            String canonicalSupplier = (String) poInfo.get("supplier_code");

            var poItems = adminJdbc.sql("select poi.id, poi.line_no, m.code as mat_code, poi.uom_code, "
                            + "(poi.ordered_quantity - poi.received_quantity) as outstanding "
                            + "from purchase_order_items poi join materials m on m.id = poi.material_id "
                            + "where poi.purchase_order_id = :poId")
                    .param("poId", poId)
                    .query((rs, rowNum) -> Map.of(
                            "id", rs.getLong("id"),
                            "line_no", rs.getInt("line_no"),
                            "mat_code", rs.getString("mat_code"),
                            "uom_code", rs.getString("uom_code"),
                            "outstanding", rs.getBigDecimal("outstanding")
                    ))
                    .list();

            List<?> rawLines = cur.lineItems() != null ? json.read(cur.lineItems(), List.class) : List.of();
            List<ReviewedReceiptLine> acceptedLines = new ArrayList<>();
            for (Object obj : rawLines) {
                if (obj instanceof Map<?, ?> line) {
                    String mat = null;
                    BigDecimal qty = null;
                    if (line.get("material_code") instanceof Map<?, ?> m) {
                        mat = (String) m.get("normalized");
                    } else if (line.get("material_code") != null) {
                        mat = String.valueOf(line.get("material_code"));
                    } else if (line.get("materialCode") != null) {
                        mat = String.valueOf(line.get("materialCode"));
                    } else if (line.get("line_no") != null) {
                        int targetLineNo = Integer.parseInt(String.valueOf(line.get("line_no")));
                        mat = poItems.stream()
                                .filter(pi -> Objects.equals(pi.get("line_no"), targetLineNo))
                                .map(pi -> (String) pi.get("mat_code"))
                                .findFirst()
                                .orElse(null);
                    }
                    if (line.get("quantity") instanceof Map<?, ?> q) {
                        Object qv = q.get("normalized");
                        if (qv != null) qty = new BigDecimal(String.valueOf(qv));
                    } else if (line.get("quantity") != null) {
                        qty = new BigDecimal(String.valueOf(line.get("quantity")));
                    } else if (line.get("qty") != null) {
                        qty = new BigDecimal(String.valueOf(line.get("qty")));
                    }

                    String docUom = null;
                    if (line.get("uom") instanceof Map<?, ?> u) {
                        docUom = (String) u.get("normalized");
                    } else if (line.get("uom") != null) {
                        docUom = String.valueOf(line.get("uom"));
                    } else if (line.get("uom_code") != null) {
                        docUom = String.valueOf(line.get("uom_code"));
                    }

                    if (mat == null || qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
                        throw ApiException.badRequest("INVALID_LINE", "Unreadable or invalid line in draft; requires manual modification");
                    }

                    String finalMat = mat;
                    var match = poItems.stream()
                            .filter(pi -> String.valueOf(pi.get("mat_code")).equalsIgnoreCase(finalMat))
                            .findFirst()
                            .orElseThrow(() -> ApiException.badRequest("MATERIAL_MISMATCH", "Material " + finalMat + " not found on PO " + canonicalPoNum));

                    String poUom = (String) match.get("uom_code");
                    if (docUom == null || docUom.isBlank()) {
                        throw ApiException.badRequest("UOM_MISSING", "Document line is missing UoM for " + finalMat + "; requires explicit human modification");
                    }
                    if (!docUom.equalsIgnoreCase(poUom)) {
                        throw ApiException.badRequest("UOM_MISMATCH", "Document line UoM '" + docUom + "' does not match PO item UoM '" + poUom + "' for " + finalMat + "; requires explicit human modification");
                    }

                    BigDecimal outstanding = (BigDecimal) match.get("outstanding");
                    if (qty.compareTo(outstanding) > 0) {
                        throw ApiException.badRequest("OVER_RECEIPT", "Quantity " + qty + " exceeds outstanding " + outstanding + " for " + finalMat);
                    }

                    acceptedLines.add(new ReviewedReceiptLine(
                            (Long) match.get("id"),
                            (String) match.get("mat_code"),
                            poUom,
                            qty
                    ));
                }
            }

            approvedPayload = new ReviewedReceiptPayload(
                    poId,
                    canonicalPoNum,
                    cur.detectedDeliveryNote() != null ? cur.detectedDeliveryNote() : "UNKNOWN",
                    canonicalSupplier,
                    cur.detectedDeliveryDate(),
                    acceptedLines
            );
        }

        String payloadJson = approvedPayload != null ? json.write(approvedPayload) : null;
        String payloadHash = approvedPayload != null ? computeSha256String(payloadJson) : null;

        adminJdbc.sql("update document_drafts set status = :st, reviewed_by = :by, reviewed_at = now(), "
                + "human_decision = :dec, reviewed_payload = :payload::jsonb, review_notes = :notes, "
                + "payload_hash = :hash where draft_id = :draftId")
                .param("st", newStatus)
                .param("by", humanReviewer.id())
                .param("dec", dec)
                .param("payload", payloadJson)
                .param("notes", req.notes() != null ? req.notes() : "")
                .param("hash", payloadHash)
                .param("draftId", draftId)
                .update();

        Map<String, Object> reviewAuditDetails = new HashMap<>();
        reviewAuditDetails.put("decision", dec);
        reviewAuditDetails.put("status", newStatus);
        reviewAuditDetails.put("notes", req.notes() != null ? req.notes() : "");
        reviewAuditDetails.put("payloadHash", payloadHash != null ? payloadHash : "NONE");
        if ("MODIFIED".equalsIgnoreCase(dec)) {
            reviewAuditDetails.put("original_po_number", cur.detectedPoNumber());
            reviewAuditDetails.put("original_delivery_note", cur.detectedDeliveryNote());
            reviewAuditDetails.put("original_lines", cur.lineItems());
            reviewAuditDetails.put("modified_payload", approvedPayload);
            reviewAuditDetails.put("reviewer_id", humanReviewer.id());
            reviewAuditDetails.put("reviewer_username", humanReviewer.username());
        }

        audit.record(humanReviewer, "HUMAN_REVIEW_DRAFT", "DOCUMENT_DRAFT", draftId, reviewAuditDetails);

        return getDraft(draftId);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public DocumentDraftRecord postAndLinkDraft(Actor humanUser, String draftId, StockDocumentService.ReceiptInput receiptInput) {
        if (humanUser.role() == Role.AGENT) {
            throw ApiException.forbidden("AGENT_CANNOT_MUTATE_STOCK", "Agent identity cannot post Goods Receipts");
        }
        if (humanUser.role() != Role.ADMIN && humanUser.role() != Role.SUPERVISOR && humanUser.role() != Role.OPERATOR) {
            throw ApiException.forbidden("FORBIDDEN", "Role " + humanUser.role() + " is not authorized to post Goods Receipts");
        }

        DocumentDraftRecord d = adminJdbc.sql("select id, draft_id, run_id, source_document_path, source_document_sha256, "
                + "detected_po_number, detected_delivery_note, detected_supplier_code, detected_delivery_date, "
                + "status, confidence_score, validation_outcome, validation_reason, provenance::text, line_items::text, "
                + "created_at, reviewed_by, reviewed_at, human_decision, posted_gr_document_number, goods_receipt_id, "
                + "reviewed_payload::text, review_notes, payload_hash "
                + "from document_drafts where draft_id = :draftId for update")
                .param("draftId", draftId)
                .query(DocumentDraftRecord.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Document draft " + draftId));

        if (!"CONFIRMED".equals(d.status())) {
            throw ApiException.conflict("INVALID_STATE", "Only CONFIRMED drafts can be posted and linked");
        }
        if (d.goodsReceiptId() != null || d.postedGrDocumentNumber() != null) {
            throw ApiException.conflict("ALREADY_LINKED", "Draft " + draftId + " is already linked to a Goods Receipt");
        }
        if (d.reviewedPayload() == null || d.reviewedPayload().isBlank()) {
            throw ApiException.conflict("INVALID_STATE", "Draft has no reviewed and approved payload");
        }

        ReviewedReceiptPayload approved = json.read(d.reviewedPayload(), ReviewedReceiptPayload.class);
        if (approved.lines() == null || approved.lines().isEmpty()) {
            throw ApiException.badRequest("EMPTY_PAYLOAD", "Approved payload contains no lines");
        }

        if (receiptInput.purchaseOrderId() != approved.purchaseOrderId()) {
            throw ApiException.badRequest("PO_MISMATCH",
                    "Goods Receipt purchaseOrderId " + receiptInput.purchaseOrderId() + " does not match approved PO " + approved.purchaseOrderId());
        }

        if (receiptInput.reference() == null || !receiptInput.reference().equalsIgnoreCase(approved.deliveryNoteNumber())) {
            throw ApiException.badRequest("DELIVERY_NOTE_MISMATCH",
                    "Goods Receipt delivery note " + receiptInput.reference() + " does not match approved " + approved.deliveryNoteNumber());
        }

        if (receiptInput.lines() == null || receiptInput.lines().size() != approved.lines().size()) {
            throw ApiException.badRequest("PAYLOAD_MISMATCH",
                    "Goods Receipt line count does not match approved line count");
        }

        for (StockDocumentService.ReceiptLineInput rLine : receiptInput.lines()) {
            var matchedApproved = approved.lines().stream()
                    .filter(a -> a.purchaseOrderItemId() == rLine.purchaseOrderItemId())
                    .findFirst()
                    .orElseThrow(() -> ApiException.badRequest("PAYLOAD_MISMATCH",
                            "Receipt line for PO item " + rLine.purchaseOrderItemId() + " was not approved in draft"));

            if (rLine.quantity().compareTo(matchedApproved.quantity()) != 0) {
                throw ApiException.badRequest("PAYLOAD_MISMATCH",
                        "Receipt line quantity " + rLine.quantity() + " does not match approved quantity " + matchedApproved.quantity()
                        + " for item " + matchedApproved.materialCode());
            }
        }

        StockDocumentService.DocView gr = stockDocuments.receive(humanUser, receiptInput);

        adminJdbc.sql("update document_drafts set goods_receipt_id = :grid, posted_gr_document_number = :grnum where draft_id = :draftId")
                .param("grid", gr.id())
                .param("grnum", gr.documentNumber())
                .param("draftId", draftId)
                .update();

        audit.record(humanUser, "POST_AND_LINK_GR", "DOCUMENT_DRAFT", draftId, Map.of(
                "goodsReceiptId", gr.id(),
                "goodsReceiptDocumentNumber", gr.documentNumber(),
                "purchaseOrderNumber", approved.poNumber(),
                "payloadHash", d.payloadHash() != null ? d.payloadHash() : "NONE"
        ));

        return getDraft(draftId);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public DocumentDraftRecord markPosted(Actor humanUser, String draftId, Long goodsReceiptId, String grDocumentNumber) {
        if (humanUser.role() == Role.AGENT) {
            throw ApiException.forbidden("AGENT_CANNOT_MUTATE_STOCK", "Agent identity cannot mark drafts posted");
        }
        if (humanUser.role() != Role.ADMIN && humanUser.role() != Role.SUPERVISOR && humanUser.role() != Role.OPERATOR) {
            throw ApiException.forbidden("FORBIDDEN", "Role " + humanUser.role() + " is not authorized to link drafts to Goods Receipt");
        }

        DocumentDraftRecord d = getDraft(draftId);
        if (!"CONFIRMED".equals(d.status())) {
            throw ApiException.conflict("INVALID_STATE", "Only CONFIRMED drafts can be linked to a posted Goods Receipt");
        }
        if (d.goodsReceiptId() != null || d.postedGrDocumentNumber() != null) {
            throw ApiException.conflict("ALREADY_LINKED", "Draft " + draftId + " is already linked to a Goods Receipt");
        }

        record GrInfo(long id, String documentNumber, long purchaseOrderId, String status) {}
        GrInfo gr = null;
        if (goodsReceiptId != null) {
            gr = adminJdbc.sql("select id, document_number, purchase_order_id, status from goods_receipts where id = :id")
                    .param("id", goodsReceiptId).query(GrInfo.class).optional().orElse(null);
        } else if (grDocumentNumber != null && !grDocumentNumber.isBlank()) {
            gr = adminJdbc.sql("select id, document_number, purchase_order_id, status from goods_receipts where document_number = :doc")
                    .param("doc", grDocumentNumber).query(GrInfo.class).optional().orElse(null);
        }

        if (gr == null) {
            throw ApiException.badRequest("FICTITIOUS_GR", "Goods Receipt does not exist in database");
        }
        if ("REVERSED".equals(gr.status())) {
            throw ApiException.conflict("INVALID_GR_STATE", "Cannot link a reversed Goods Receipt");
        }

        long existingCount = adminJdbc.sql("select count(*) from document_drafts where goods_receipt_id = :grid")
                .param("grid", gr.id()).query(Long.class).single();
        if (existingCount > 0) {
            throw ApiException.conflict("DUPLICATE_LINKING", "Goods Receipt " + gr.documentNumber() + " is already linked to another draft");
        }

        adminJdbc.sql("update document_drafts set goods_receipt_id = :grid, posted_gr_document_number = :grnum where draft_id = :draftId")
                .param("grid", gr.id())
                .param("grnum", gr.documentNumber())
                .param("draftId", draftId)
                .update();

        audit.record(humanUser, "LINK_DRAFT_TO_GR", "DOCUMENT_DRAFT", draftId, Map.of(
                "goodsReceiptId", gr.id(),
                "goodsReceiptDocumentNumber", gr.documentNumber()
        ));

        return getDraft(draftId);
    }

    public TraceabilityReport getTraceabilityReport(String draftId) {
        DocumentDraftRecord d = getDraft(draftId);
        AgentRunRecord run = d.runId() != null ? getRun(d.runId()) : null;
        int toolCount = d.runId() != null ? listToolCalls(d.runId()).size() : 0;

        String agentUser = run != null ? adminJdbc.sql("select username from users where id = :id")
                .param("id", run.agentId()).query(String.class).optional().orElse("UNKNOWN") : "UNKNOWN";

        String reviewerUser = d.reviewedBy() != null ? adminJdbc.sql("select username from users where id = :id")
                .param("id", d.reviewedBy()).query(String.class).optional().orElse(null) : null;

        return new TraceabilityReport(
                d.draftId(),
                d.runId(),
                agentUser,
                run != null ? run.startedAt() : null,
                run != null ? run.finishedAt() : null,
                run != null ? run.modelName() : null,
                run != null ? run.promptVersion() : null,
                run != null ? run.inputHash() : null,
                toolCount,
                d.sourceDocumentPath(),
                d.sourceDocumentSha256(),
                d.detectedPoNumber(),
                d.detectedDeliveryNote(),
                d.detectedSupplierCode(),
                d.confidenceScore(),
                d.validationOutcome(),
                d.status(),
                d.humanDecision(),
                reviewerUser,
                d.reviewedAt(),
                d.postedGrDocumentNumber(),
                d.goodsReceiptId(),
                d.reviewedPayload(),
                d.reviewNotes(),
                d.payloadHash()
        );
    }

    private String computeSha256String(String input) {
        if (input == null) return null;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    public AgentRunRecord getRun(String runId) {
        return adminJdbc.sql("select id, run_id, agent_id, task_type, status, triggered_by, started_at, finished_at, "
                + "latency_ms, model_name, prompt_version, input_hash, error_reason, summary::text "
                + "from agent_runs where run_id = :runId")
                .param("runId", runId)
                .query(AgentRunRecord.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Agent run " + runId));
    }

    public List<ToolCallRecord> listToolCalls(String runId) {
        return adminJdbc.sql("select id, run_id, call_sequence, tool_name, parameters::text, result_status, "
                + "result_summary::text, executed_at from agent_tool_calls where run_id = :runId order by call_sequence")
                .param("runId", runId)
                .query(ToolCallRecord.class)
                .list();
    }

    public AgentPurchaseOrderView readPurchaseOrder(Actor actor, String runId, String poNumber) {
        return dispatcher.dispatch(actor, runId, "READ_PURCHASE_ORDER", Map.of("poNumber", poNumber), () -> {
            return agentJdbc.sql("select id, po_number, supplier_code, supplier_name, po_date, expected_date, status "
                    + "from v_agent_purchase_orders where upper(po_number) = upper(:po) order by po_date desc limit 1")
                    .param("po", poNumber)
                    .query(AgentPurchaseOrderView.class)
                    .optional()
                    .orElseThrow(() -> ApiException.notFound("Purchase Order " + poNumber));
        });
    }

    public AgentMaterialView readMaterialCatalog(Actor actor, String runId, String materialCode) {
        return dispatcher.dispatch(actor, runId, "READ_MATERIAL_CATALOG", Map.of("materialCode", materialCode), () -> {
            return agentJdbc.sql("select id, code, name, description, base_uom, active "
                    + "from v_agent_materials where upper(code) = upper(:code)")
                    .param("code", materialCode)
                    .query(AgentMaterialView.class)
                    .optional()
                    .orElseThrow(() -> ApiException.notFound("Material " + materialCode));
        });
    }

    public List<AgentInventoryBalanceView> readInventoryBalance(Actor actor, String runId, String materialCode) {
        return dispatcher.dispatch(actor, runId, "READ_INVENTORY_BALANCE",
                Map.of("materialCode", materialCode != null ? materialCode : ""), () -> {
            String filter = materialCode != null && !materialCode.isBlank() ? "where upper(material_code) = upper(:code) " : "";
            return agentJdbc.sql("select id, material_code, warehouse_code, location_code, quantity, updated_at "
                    + "from v_agent_inventory_balances " + filter + "order by material_code, warehouse_code")
                    .param("code", materialCode)
                    .query(AgentInventoryBalanceView.class)
                    .list();
        });
    }

    public List<AgentPurchaseOrderView> listAgentPurchaseOrders() {
        return agentJdbc.sql("select id, po_number, supplier_code, supplier_name, po_date, expected_date, status "
                + "from v_agent_purchase_orders order by po_date desc")
                .query(AgentPurchaseOrderView.class)
                .list();
    }

    public List<AgentMaterialView> listAgentMaterials() {
        return agentJdbc.sql("select id, code, name, description, base_uom, active "
                + "from v_agent_materials order by code")
                .query(AgentMaterialView.class)
                .list();
    }
}
