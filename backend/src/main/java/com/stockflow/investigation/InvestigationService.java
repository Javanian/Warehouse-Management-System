package com.stockflow.investigation;

import com.stockflow.common.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class InvestigationService {

    private final JdbcClient jdbc;

    public InvestigationService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record DiscrepancyCandidate(
            long materialId,
            String materialCode,
            String materialName,
            String uomCode,
            BigDecimal currentBalance,
            long movementCount,
            long adjustmentCount
    ) {}

    public record TimelineEntry(
            long movementId,
            String movementNumber,
            String movementType,
            String documentType,
            String documentNumber,
            String reversalOfMovementNumber,
            Instant postedAt,
            LocalDate businessDate,
            String postedBy,
            int lineNo,
            String locationCode,
            String warehouseCode,
            BigDecimal quantityDelta,
            BigDecimal balanceAfter,
            String notes
    ) {}

    public record LocationReconciliation(
            long locationId,
            String locationCode,
            String warehouseCode,
            BigDecimal currentBalance,
            BigDecimal ledgerSum,
            BigDecimal discrepancyDelta,
            boolean isReconciled
    ) {}

    public record InvestigationFinding(
            String category, // "FACT", "HYPOTHESIS", "UNKNOWN"
            String title,
            String description,
            String evidenceReference
    ) {}

    public record DiscrepancyReport(
            long materialId,
            String materialCode,
            String materialName,
            String uomCode,
            BigDecimal currentBalance,
            BigDecimal ledgerSum,
            BigDecimal discrepancyDelta,
            boolean isReconciled,
            boolean allLocationsReconciled,
            String analysisMode,
            String agentGuardrailNotice,
            List<LocationReconciliation> locationReconciliations,
            List<TimelineEntry> timeline,
            List<InvestigationFinding> facts,
            List<InvestigationFinding> hypotheses,
            List<InvestigationFinding> unknowns
    ) {}

    public List<DiscrepancyCandidate> listCandidates() {
        return jdbc.sql("select m.id as material_id, m.code as material_code, m.name as material_name, m.uom_code, "
                + "coalesce(sum(b.quantity), 0) as current_balance, "
                + "(select count(*) from stock_movement_entries sme where sme.material_id = m.id) as movement_count, "
                + "(select count(*) from stock_movement_entries sme join stock_movements sm on sm.id = sme.movement_id "
                + " where sme.material_id = m.id and sm.movement_type like 'ADJUSTMENT%') as adjustment_count "
                + "from materials m "
                + "left join inventory_balances b on b.material_id = m.id "
                + "group by m.id, m.code, m.name, m.uom_code "
                + "order by adjustment_count desc, movement_count desc, m.code")
                .query(DiscrepancyCandidate.class)
                .list();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DiscrepancyReport investigateMaterial(String materialCode) {
        if (materialCode == null || materialCode.isBlank()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "materialCode is required");
        }

        record MatHead(long id, String code, String name, String uom) {}
        MatHead mat = jdbc.sql("select id, code, name, uom_code as uom from materials where upper(code) = upper(:c)")
                .param("c", materialCode.trim())
                .query(MatHead.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Material " + materialCode));

        BigDecimal currentBalance = jdbc.sql("select coalesce(sum(quantity), 0) from inventory_balances where material_id = :id")
                .param("id", mat.id())
                .query(BigDecimal.class)
                .single();

        BigDecimal ledgerSum = jdbc.sql("select coalesce(sum(quantity_delta), 0) from stock_movement_entries where material_id = :id")
                .param("id", mat.id())
                .query(BigDecimal.class)
                .single();

        BigDecimal discrepancy = currentBalance.subtract(ledgerSum);
        boolean isReconciled = discrepancy.compareTo(BigDecimal.ZERO) == 0;

        // Consistent snapshot read for timeline
        List<TimelineEntry> timeline = jdbc.sql("select sm.id as movement_id, sm.movement_number, sm.movement_type, "
                        + "sm.document_type, sm.document_number, "
                        + "(select r.movement_number from stock_movements r where r.id = sm.reversal_of_movement_id) as reversal_of_movement_number, "
                        + "sm.posted_at, sm.business_date, u.username as posted_by, sme.line_no, sl.code as location_code, "
                        + "w.code as warehouse_code, sme.quantity_delta, sme.balance_after, sm.notes "
                        + "from stock_movement_entries sme "
                        + "join stock_movements sm on sm.id = sme.movement_id "
                        + "join users u on u.id = sm.posted_by "
                        + "join storage_locations sl on sl.id = sme.location_id "
                        + "join warehouses w on w.id = sl.warehouse_id "
                        + "where sme.material_id = :id "
                        + "order by sm.posted_at asc, sme.id asc")
                .param("id", mat.id())
                .query(TimelineEntry.class)
                .list();

        // Consistent snapshot read for per-location reconciliation
        List<LocationReconciliation> locationReconciliations = jdbc.sql(
                "select loc.id as location_id, loc.code as location_code, w.code as warehouse_code, "
                        + "coalesce(b.quantity, 0) as current_balance, "
                        + "coalesce(led.sum_delta, 0) as ledger_sum, "
                        + "(coalesce(b.quantity, 0) - coalesce(led.sum_delta, 0)) as discrepancy_delta, "
                        + "(coalesce(b.quantity, 0) = coalesce(led.sum_delta, 0)) as is_reconciled "
                        + "from storage_locations loc "
                        + "join warehouses w on w.id = loc.warehouse_id "
                        + "left join inventory_balances b on b.location_id = loc.id and b.material_id = :matId "
                        + "left join (select location_id, sum(quantity_delta) as sum_delta from stock_movement_entries where material_id = :matId group by location_id) led on led.location_id = loc.id "
                        + "where b.id is not null or led.location_id is not null "
                        + "order by loc.code")
                .param("matId", mat.id())
                .query(LocationReconciliation.class)
                .list();

        boolean allLocationsReconciled = locationReconciliations.stream().allMatch(LocationReconciliation::isReconciled);

        List<InvestigationFinding> facts = new ArrayList<>();
        List<InvestigationFinding> hypotheses = new ArrayList<>();
        List<InvestigationFinding> unknowns = new ArrayList<>();

        // Generate factual findings from immutable ledger
        facts.add(new InvestigationFinding(
                "FACT",
                "Mathematical Ledger Reconciliation",
                isReconciled
                        ? "Current inventory balance (" + currentBalance + " " + mat.uom() + ") matches the immutable ledger transaction sum (" + ledgerSum + " " + mat.uom() + ")."
                        : "Ledger-to-balance mismatch detected: Current balance (" + currentBalance + " " + mat.uom() + ") differs from immutable ledger sum (" + ledgerSum + " " + mat.uom() + ") by " + discrepancy + " " + mat.uom() + ".",
                "table:inventory_balances; table:stock_movement_entries"
        ));

        facts.add(new InvestigationFinding(
                "FACT",
                "Per-Location Reconciliation Status",
                allLocationsReconciled
                        ? "All " + locationReconciliations.size() + " storage locations holding or having history for this material are individually reconciled."
                        : "Location-level discrepancy detected! One or more bin balances do not match the location ledger sum.",
                "locations_analyzed:count=" + locationReconciliations.size() + "; all_reconciled=" + allLocationsReconciled
        ));

        // Distinct transactions vs lines
        long grTxCount = timeline.stream()
                .filter(t -> "GOODS_RECEIPT".equalsIgnoreCase(t.movementType()))
                .map(TimelineEntry::movementId).distinct().count();
        long grLineCount = timeline.stream()
                .filter(t -> "GOODS_RECEIPT".equalsIgnoreCase(t.movementType()))
                .count();
        BigDecimal totalReceived = timeline.stream()
                .filter(t -> "GOODS_RECEIPT".equalsIgnoreCase(t.movementType()))
                .map(TimelineEntry::quantityDelta)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (grTxCount > 0) {
            facts.add(new InvestigationFinding(
                    "FACT",
                    "Total Inbound Receipts",
                    "Recorded " + grTxCount + " goods receipt transactions (" + grLineCount + " ledger line entries) totaling +" + totalReceived + " " + mat.uom() + ".",
                    "type:GOODS_RECEIPT; material:" + mat.code()
            ));
        }

        long issueTxCount = timeline.stream()
                .filter(t -> "STOCK_ISSUE".equalsIgnoreCase(t.movementType()) || "ISSUE".equalsIgnoreCase(t.movementType()))
                .map(TimelineEntry::movementId).distinct().count();
        long issueLineCount = timeline.stream()
                .filter(t -> "STOCK_ISSUE".equalsIgnoreCase(t.movementType()) || "ISSUE".equalsIgnoreCase(t.movementType()))
                .count();
        BigDecimal totalIssued = timeline.stream()
                .filter(t -> "STOCK_ISSUE".equalsIgnoreCase(t.movementType()) || "ISSUE".equalsIgnoreCase(t.movementType()))
                .map(TimelineEntry::quantityDelta)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (issueTxCount > 0) {
            facts.add(new InvestigationFinding(
                    "FACT",
                    "Total Outbound Issues",
                    "Recorded " + issueTxCount + " outbound stock issue transactions (" + issueLineCount + " ledger line entries) totaling " + totalIssued + " " + mat.uom() + ".",
                    "type:STOCK_ISSUE; material:" + mat.code()
            ));
        }

        long transferTxCount = timeline.stream()
                .filter(t -> "TRANSFER".equalsIgnoreCase(t.movementType()) || "STOCK_TRANSFER".equalsIgnoreCase(t.documentType()))
                .map(TimelineEntry::movementId).distinct().count();
        long transferLineCount = timeline.stream()
                .filter(t -> "TRANSFER".equalsIgnoreCase(t.movementType()) || "STOCK_TRANSFER".equalsIgnoreCase(t.documentType()))
                .count();

        if (transferTxCount > 0) {
            facts.add(new InvestigationFinding(
                    "FACT",
                    "Internal Stock Transfers",
                    "Recorded " + transferTxCount + " internal transfer transactions (" + transferLineCount + " ledger lines).",
                    "type:TRANSFER; material:" + mat.code()
            ));
        }

        List<TimelineEntry> adjustments = timeline.stream()
                .filter(t -> t.movementType() != null && t.movementType().startsWith("ADJUSTMENT"))
                .toList();

        if (!adjustments.isEmpty()) {
            for (TimelineEntry adj : adjustments) {
                facts.add(new InvestigationFinding(
                        "FACT",
                        "Manual System Adjustment Recorded",
                        "Adjustment document " + adj.documentNumber() + " posted by user " + adj.postedBy()
                                + " with delta " + adj.quantityDelta() + " " + mat.uom()
                                + (adj.notes() != null && !adj.notes().isBlank() ? " (Notes: " + adj.notes() + ")" : "")
                                + ". Ledger confirms administrative system posting, not physical cause.",
                        "movement:" + adj.movementNumber() + "; doc:" + adj.documentNumber()
                ));
            }
        }

        List<TimelineEntry> reversals = timeline.stream()
                .filter(t -> t.reversalOfMovementNumber() != null && !t.reversalOfMovementNumber().isBlank())
                .toList();

        if (!reversals.isEmpty()) {
            for (TimelineEntry rev : reversals) {
                facts.add(new InvestigationFinding(
                        "FACT",
                        "Document Reversal Executed",
                        "Document reversal " + rev.documentNumber() + " reversed movement " + rev.reversalOfMovementNumber()
                                + " with delta " + rev.quantityDelta() + " " + mat.uom() + ".",
                        "movement:" + rev.movementNumber() + "; reversal_of:" + rev.reversalOfMovementNumber()
                ));
            }
        }

        // Generate hypotheses based on patterns
        if (adjustments.isEmpty() && isReconciled && allLocationsReconciled) {
            hypotheses.add(new InvestigationFinding(
                    "HYPOTHESIS",
                    "Nominal Inventory State",
                    "No physical adjustments or balance discrepancies detected; inventory balances match the recorded transaction history.",
                    "balance:nominal"
            ));
        }

        List<TimelineEntry> negAdjustments = adjustments.stream()
                .filter(t -> t.quantityDelta().compareTo(BigDecimal.ZERO) < 0)
                .toList();
        List<TimelineEntry> posAdjustments = adjustments.stream()
                .filter(t -> t.quantityDelta().compareTo(BigDecimal.ZERO) > 0)
                .toList();

        if (!negAdjustments.isEmpty()) {
            hypotheses.add(new InvestigationFinding(
                    "HYPOTHESIS",
                    "Possible Physical Shrinkage, Scrap, or Handling Loss",
                    "Negative adjustments recorded (" + negAdjustments.size() + " entries). Physical shrinkage, breakage, or bin leakage are possible operational causes, but system ledger strictly records administrative inventory write-down.",
                    "negative_adjustments:count=" + negAdjustments.size()
            ));
        }

        if (!posAdjustments.isEmpty()) {
            hypotheses.add(new InvestigationFinding(
                    "HYPOTHESIS",
                    "Possible Inventory Surplus or Unrecorded Inbound Return",
                    "Positive adjustments recorded (" + posAdjustments.size() + " entries). Found inventory, unrecorded returns, or earlier physical undercounting are possible operational causes, but system ledger strictly records administrative inventory write-up.",
                    "positive_adjustments:count=" + posAdjustments.size()
            ));
        }

        if (issueTxCount == 0 && transferTxCount == 0 && grTxCount > 0 && currentBalance.compareTo(totalReceived) == 0) {
            hypotheses.add(new InvestigationFinding(
                    "HYPOTHESIS",
                    "Static / Untouched Inbound Stock",
                    "All received inventory remains intact in original storage locations with zero dispatch issues and zero internal bin transfers registered in system.",
                    "movement_summary:gr=" + grTxCount + ";issues=" + issueTxCount + ";transfers=" + transferTxCount
            ));
        }

        if (!isReconciled || !allLocationsReconciled) {
            hypotheses.add(new InvestigationFinding(
                    "HYPOTHESIS",
                    "Concurrent Write Race or Data Mutation Outside Ledger",
                    "A non-zero discrepancy between inventory_balances and stock_movement_entries indicates an incomplete transaction, untracked direct table update, or unallocated bin transfer.",
                    "reconciliation_delta:" + discrepancy + "; all_bins_reconciled=" + allLocationsReconciled
            ));
        }

        // Generate unknowns
        unknowns.add(new InvestigationFinding(
                "UNKNOWN",
                "Physical Receiving Condition",
                "Visual packaging integrity and pallet batch condition upon dock arrival are not recorded in digital ledger metadata.",
                "dock_inspection_log:not_integrated"
        ));

        if (!adjustments.isEmpty()) {
            unknowns.add(new InvestigationFinding(
                    "UNKNOWN",
                    "Disposed Goods Traceability",
                    "Whether damaged or adjusted quantities were returned to supplier for credit, scrapped, or transferred to quarantine is unknown from warehouse records.",
                    "supplier_rma_records:future_work"
            ));
        }

        return new DiscrepancyReport(
                mat.id(),
                mat.code(),
                mat.name(),
                mat.uom(),
                currentBalance,
                ledgerSum,
                discrepancy,
                isReconciled,
                allLocationsReconciled,
                "DETERMINISTIC_OFFLINE_RULE_BASED",
                "Agent role is strictly read-only and cannot alter stock balances, create adjustments, or approve reversals.",
                locationReconciliations,
                timeline,
                facts,
                hypotheses,
                unknowns
        );
    }
}
