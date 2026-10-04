package com.stockflow.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stockflow.common.ApiException;
import com.stockflow.demo.SyntheticSeeder;
import com.stockflow.identity.Actor;
import com.stockflow.identity.Role;
import com.stockflow.inventory.InventoryPostingService;
import com.stockflow.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

class InvestigationIT extends IntegrationTest {

    @Autowired
    private InvestigationService service;

    @Autowired
    private SyntheticSeeder seeder;

    @Autowired
    private InventoryPostingService posting;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager txManager;

    private Actor adminActor;
    private Actor agentActor;

    @BeforeEach
    void setUp() {
        seeder.seed();
        adminActor = new Actor(1L, "admin", Role.ADMIN);
        agentActor = new Actor(2L, "agent_inbound", Role.AGENT);
    }

    @Test
    void listCandidatesReturnsSeededMaterials() {
        var candidates = service.listCandidates();
        assertThat(candidates).isNotEmpty();
        assertThat(candidates.stream().anyMatch(c -> "SYN-MAT-006".equalsIgnoreCase(c.materialCode()))).isTrue();
    }

    @Test
    void investigateMaterialAssemblesTimelineAndSeparatesFactsHypothesesUnknowns() {
        var report = service.investigateMaterial("SYN-MAT-006");

        assertThat(report).isNotNull();
        assertThat(report.materialCode()).isEqualTo("SYN-MAT-006");
        assertThat(report.isReconciled()).isTrue();
        assertThat(report.allLocationsReconciled()).isTrue();
        assertThat(report.analysisMode()).isEqualTo("DETERMINISTIC_OFFLINE_RULE_BASED");
        assertThat(report.agentGuardrailNotice()).contains("strictly read-only");

        // Timeline has historical receipt from SyntheticSeeder
        assertThat(report.timeline()).isNotEmpty();
        var first = report.timeline().get(0);
        assertThat(first.movementType()).isIn("OPENING_BALANCE", "GOODS_RECEIPT");
        assertThat(first.quantityDelta()).isGreaterThan(BigDecimal.ZERO);

        // Facts, Hypotheses, Unknowns are cleanly categorized
        assertThat(report.facts()).isNotEmpty();
        assertThat(report.facts().stream().allMatch(f -> "FACT".equals(f.category()))).isTrue();
        assertThat(report.facts().stream().anyMatch(f -> f.evidenceReference() != null && !f.evidenceReference().isBlank())).isTrue();

        assertThat(report.hypotheses()).isNotEmpty();
        assertThat(report.hypotheses().stream().allMatch(h -> "HYPOTHESIS".equals(h.category()))).isTrue();

        assertThat(report.unknowns()).isNotEmpty();
        assertThat(report.unknowns().stream().allMatch(u -> "UNKNOWN".equals(u.category()))).isTrue();
    }

    @Test
    void investigateUnknownMaterialThrowsNotFound() {
        assertThatThrownBy(() -> service.investigateMaterial("NON-EXISTENT-MAT-999"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("NOT_FOUND"));
    }

    @Test
    void investigateDetectsAdjustmentsInTimeline() {
        Long matId = jdbc.queryForObject("select id from materials where code = 'SYN-MAT-001'", Long.class);
        Long locId = jdbc.queryForObject("select location_id from inventory_balances where material_id = ? and quantity > 0 limit 1", Long.class, matId);

        new org.springframework.transaction.support.TransactionTemplate(txManager).executeWithoutResult(status -> {
            posting.post(adminActor, new InventoryPostingService.Request(
                    "ADJUSTMENT_OUT", "STOCK_ADJUSTMENT", 999L, "ADJ", "SYN-ADJ-TEST-001",
                    "Damaged inventory identified during cycle count",
                    List.of(new InventoryPostingService.Line(matId, locId, BigDecimal.valueOf(-2.0)))
            ));

            var report = service.investigateMaterial("SYN-MAT-001");
            assertThat(report.timeline().stream().anyMatch(t -> t.notes() != null && t.notes().contains("cycle count"))).isTrue();
            assertThat(report.facts().stream().anyMatch(f -> f.description().contains("cycle count"))).isTrue();
            assertThat(report.hypotheses().stream().anyMatch(h -> h.title().contains("Physical Shrinkage, Scrap, or Handling Loss"))).isTrue();

            status.setRollbackOnly();
        });
    }

    @Test
    void investigateMultiBinReceiptAndPerLocationReconciliation() {
        Long matId = jdbc.queryForObject("select id from materials where code = 'SYN-MAT-002'", Long.class);
        List<Long> locIds = jdbc.queryForList("select id from storage_locations order by id limit 2", Long.class);

        new org.springframework.transaction.support.TransactionTemplate(txManager).executeWithoutResult(status -> {
            // Single transaction goods receipt with 2 distinct bin lines
            posting.post(adminActor, new InventoryPostingService.Request(
                    "GOODS_RECEIPT", "GOODS_RECEIPT", 888L, "GR", "GR-MULTI-BIN-01",
                    "Multi-bin receipt arrival test",
                    List.of(
                            new InventoryPostingService.Line(matId, locIds.get(0), BigDecimal.valueOf(10.0)),
                            new InventoryPostingService.Line(matId, locIds.get(1), BigDecimal.valueOf(15.0))
                    )
            ));

            var report = service.investigateMaterial("SYN-MAT-002");
            assertThat(report.isReconciled()).isTrue();
            assertThat(report.allLocationsReconciled()).isTrue();

            // Verify facts report transaction count vs line count
            assertThat(report.facts().stream().anyMatch(f -> f.title().contains("Total Inbound Receipts")
                    && f.description().contains("ledger line entries"))).isTrue();

            // Verify both locations are represented in location reconciliations
            assertThat(report.locationReconciliations().stream().anyMatch(l -> l.locationId() == locIds.get(0) && l.isReconciled())).isTrue();
            assertThat(report.locationReconciliations().stream().anyMatch(l -> l.locationId() == locIds.get(1) && l.isReconciled())).isTrue();

            status.setRollbackOnly();
        });
    }

    @Test
    void investigatePositiveAdjustmentSuggestsSurplusNotShrinkage() {
        Long matId = jdbc.queryForObject("select id from materials where code = 'SYN-MAT-003'", Long.class);
        Long locId = jdbc.queryForObject("select id from storage_locations order by id limit 1", Long.class);

        new org.springframework.transaction.support.TransactionTemplate(txManager).executeWithoutResult(status -> {
            posting.post(adminActor, new InventoryPostingService.Request(
                    "ADJUSTMENT_IN", "STOCK_ADJUSTMENT", 777L, "ADJ", "SYN-ADJ-POS-01",
                    "Surplus found during annual audit",
                    List.of(new InventoryPostingService.Line(matId, locId, BigDecimal.valueOf(5.0)))
            ));

            var report = service.investigateMaterial("SYN-MAT-003");
            assertThat(report.hypotheses().stream().anyMatch(h -> h.title().contains("Possible Inventory Surplus"))).isTrue();
            assertThat(report.hypotheses().stream().noneMatch(h -> h.title().contains("Physical Shrinkage"))).isTrue();

            status.setRollbackOnly();
        });
    }

    @Test
    void investigateTransferRecordedWithoutConflatingWithIssues() {
        Long matId = jdbc.queryForObject("select id from materials where code = 'SYN-MAT-001'", Long.class);
        Long fromLocId = jdbc.queryForObject("select location_id from inventory_balances where material_id = ? and quantity >= 5 limit 1", Long.class, matId);
        Long toLocId = jdbc.queryForObject("select id from storage_locations where id != ? limit 1", Long.class, fromLocId);

        new org.springframework.transaction.support.TransactionTemplate(txManager).executeWithoutResult(status -> {
            // Post transfer: -5 from fromLocId, +5 to toLocId
            posting.post(adminActor, new InventoryPostingService.Request(
                    "TRANSFER", "STOCK_TRANSFER", 666L, "TRF", "TRF-TEST-001",
                    "Inter-bin warehouse re-slotting",
                    List.of(
                            new InventoryPostingService.Line(matId, fromLocId, BigDecimal.valueOf(-5.0)),
                            new InventoryPostingService.Line(matId, toLocId, BigDecimal.valueOf(5.0))
                    )
            ));

            var report = service.investigateMaterial("SYN-MAT-001");
            assertThat(report.facts().stream().anyMatch(f -> f.title().contains("Internal Stock Transfers"))).isTrue();

            status.setRollbackOnly();
        });
    }
}
