package com.stockflow.procurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stockflow.common.ApiException;
import com.stockflow.demo.SyntheticSeeder;
import com.stockflow.inventory.InventoryController;
import com.stockflow.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

class StatusSelfServiceIT extends IntegrationTest {

    @Autowired
    private PurchaseOrderService poService;
    @Autowired
    private InventoryController inventoryController;
    @Autowired
    private SyntheticSeeder synthetic;

    @BeforeEach
    void setUp() {
        synthetic.seed();
    }

    @Test
    void poLookupReturnsAccurateDeterministicStatus() {
        // FAM-01: SYN-PO-2026-001 (100 ordered, 0 received -> OPEN, receivable)
        var rep1 = poService.lookupByPoNumber("SYN-PO-2026-001");
        assertThat(rep1.poNumber()).isEqualTo("SYN-PO-2026-001");
        assertThat(rep1.status()).isEqualTo("OPEN");
        assertThat(rep1.receivable()).isTrue();
        assertThat(rep1.supplierCode()).isEqualTo("SYN-SUP-01");
        assertThat(rep1.lines()).hasSize(1);
        assertThat(rep1.lines().get(0).orderedQuantity()).isEqualByComparingTo("100");
        assertThat(rep1.lines().get(0).receivedQuantity()).isEqualByComparingTo("0");
        assertThat(rep1.lines().get(0).outstandingQuantity()).isEqualByComparingTo("100");
        assertThat(rep1.lines().get(0).lineStatus()).isEqualTo("OPEN");
        assertThat(rep1.lines().get(0).canReceive()).isTrue();

        // FAM-04: SYN-PO-2026-004 (20 ordered, 10 received -> PARTIALLY_RECEIVED)
        var rep4 = poService.lookupByPoNumber("SYN-PO-2026-004");
        assertThat(rep4.poNumber()).isEqualTo("SYN-PO-2026-004");
        assertThat(rep4.status()).isEqualTo("PARTIALLY_RECEIVED");
        assertThat(rep4.receivable()).isTrue();
        assertThat(rep4.lines().get(0).orderedQuantity()).isEqualByComparingTo("20");
        assertThat(rep4.lines().get(0).receivedQuantity()).isEqualByComparingTo("10");
        assertThat(rep4.lines().get(0).outstandingQuantity()).isEqualByComparingTo("10");
        assertThat(rep4.lines().get(0).lineStatus()).isEqualTo("PARTIAL");
        assertThat(rep4.lines().get(0).canReceive()).isTrue();

        // FAM-06: SYN-PO-2026-006 (CANCELLED -> receivable is false)
        var rep6 = poService.lookupByPoNumber("SYN-PO-2026-006");
        assertThat(rep6.status()).isEqualTo("CANCELLED");
        assertThat(rep6.receivable()).isFalse();
        assertThat(rep6.lines().get(0).canReceive()).isFalse();
    }

    @Test
    void poLookupThrowsNotFoundOnInvalidPo() {
        assertThatThrownBy(() -> poService.lookupByPoNumber("NON_EXISTENT_PO"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(404));
    }

    @Test
    void materialStockSummaryReturnsAccurateLocationBreakdown() {
        // SYN-MAT-001 has initial stock 500.0 in RACK-A1-01
        var sum = inventoryController.stockSummary("SYN-MAT-001");
        assertThat(sum.materialCode()).isEqualTo("SYN-MAT-001");
        assertThat(sum.totalQuantity()).isEqualByComparingTo("500");
        assertThat(sum.stockStatus()).isEqualTo("HEALTHY");
        assertThat(sum.balances()).isNotEmpty();
        assertThat(sum.recentMovements()).isNotEmpty();
    }
}
