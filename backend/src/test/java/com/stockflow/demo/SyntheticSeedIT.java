package com.stockflow.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockflow.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SyntheticSeedIT extends IntegrationTest {

    @Autowired
    SyntheticSeeder synthetic;

    long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    @Test
    void syntheticSeedIsRepeatableAndReconciled() {
        jdbc.execute("delete from demo_seed_runs where seed_name = 'synthetic-v1'");
        boolean firstRun = synthetic.seed();
        assertThat(firstRun).isTrue();

        // Check master data under SYN-*
        assertThat(count("select count(*) from suppliers where code like 'SYN-%'")).isEqualTo(4);
        assertThat(count("select count(*) from warehouses where code like 'WH-SYN-%'")).isEqualTo(2);
        assertThat(count("select count(*) from storage_locations where code in ('RACK-A1-01', 'BIN-C1-01')")).isGreaterThanOrEqualTo(2);
        assertThat(count("select count(*) from materials where code like 'SYN-%'")).isEqualTo(10);
        assertThat(count("select count(*) from purchase_orders where po_number like 'SYN-%'")).isEqualTo(13);

        // Check ledger reconciliation for SYN materials
        long unreconciled = count("select count(*) from inventory_balances b "
                + "join materials m on m.id = b.material_id "
                + "full join (select e.material_id, e.location_id, sum(e.quantity_delta) as t "
                + "           from stock_movement_entries e join materials m2 on m2.id = e.material_id "
                + "           where m2.code like 'SYN-%' group by 1, 2) s "
                + "on s.material_id = b.material_id and s.location_id = b.location_id "
                + "where m.code like 'SYN-%' and coalesce(b.quantity, 0) <> coalesce(s.t, 0)");
        assertThat(unreconciled).isZero();

        // Check that initial stock of SYN-MAT-001 is exactly 500
        java.math.BigDecimal mat1Qty = jdbc.queryForObject(
                "select coalesce(sum(quantity), 0) from inventory_balances b "
                + "join materials m on m.id = b.material_id where m.code = 'SYN-MAT-001'", java.math.BigDecimal.class);
        assertThat(mat1Qty).isEqualByComparingTo("500.000");

        // Rerun should be idempotent and return false
        boolean secondRun = synthetic.seed();
        assertThat(secondRun).isFalse();
    }
}
