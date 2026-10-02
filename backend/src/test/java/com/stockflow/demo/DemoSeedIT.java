package com.stockflow.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockflow.support.ApiClient;
import com.stockflow.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DemoSeedIT extends IntegrationTest {

    @Autowired
    DemoSeeder seeder;

    long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    @Test
    void seedIsDeterministicReconciledAndRerunSafe() {
        jdbc.execute("delete from demo_seed_runs");
        long before = count("select count(*) from materials where demo");
        if (before == 0) {
            assertThat(seeder.seed()).isTrue();
        }
        long materials = count("select count(*) from materials where demo");
        long entries = count("select count(*) from stock_movement_entries e join materials m on m.id = e.material_id where m.demo");
        assertThat(materials).isBetween(30L, 50L);
        assertThat(count("select count(*) from warehouses where demo")).isEqualTo(2);
        assertThat(count("select count(*) from storage_locations where demo")).isBetween(10L, 20L);
        assertThat(count("select count(*) from purchase_orders where demo")).isBetween(5L, 10L);
        assertThat(entries).isBetween(100L, 200L);
        for (String st : new String[] {"DRAFT", "OPEN", "PARTIALLY_RECEIVED", "COMPLETED", "CANCELLED"}) {
            assertThat(count("select count(*) from purchase_orders where demo and status = '" + st + "'")).as(st).isPositive();
        }
        assertThat(count("select count(*) from stock_movements where movement_type = 'REVERSAL'")).isGreaterThanOrEqualTo(3);
        assertThat(count("select count(*) from stock_adjustments where status = 'PENDING'")).isGreaterThanOrEqualTo(1);
        assertThat(count("select count(*) from stock_adjustments where status = 'APPROVED'")).isGreaterThanOrEqualTo(1);
        assertThat(count("select count(*) from stock_adjustments where status = 'REJECTED'")).isGreaterThanOrEqualTo(1);
        assertThat(count("select count(*) from materials m where m.minimum_stock > 0 and coalesce((select sum(quantity)"
                + " from inventory_balances b where b.material_id = m.id), 0) < m.minimum_stock")).isPositive();
        assertThat(count("select count(*) from inventory_balances b full join (select material_id, location_id,"
                + " sum(quantity_delta) t from stock_movement_entries group by 1, 2) s on s.material_id = b.material_id"
                + " and s.location_id = b.location_id where coalesce(b.quantity, 0) <> coalesce(s.t, 0)")).isZero();

        assertThat(seeder.seed()).isFalse();
        assertThat(count("select count(*) from materials where demo")).isEqualTo(materials);
        assertThat(count("select count(*) from stock_movement_entries e join materials m on m.id = e.material_id where m.demo")).isEqualTo(entries);

        ApiClient c = new ApiClient(port);
        assertThat(c.login("viewer", "Demo#2026").status()).isEqualTo(200);
        ApiClient.Res dash = c.get("/api/dashboard");
        assertThat(dash.status()).isEqualTo(200);
        assertThat(dash.body().get("counts").get("pendingAdjustments").asLong()).isPositive();
        assertThat(c.get("/api/inventory/reconciliation").body().size()).isZero();
    }
}
