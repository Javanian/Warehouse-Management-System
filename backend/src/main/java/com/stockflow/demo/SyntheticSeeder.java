package com.stockflow.demo;

import com.stockflow.common.Json;
import com.stockflow.identity.Actor;
import com.stockflow.identity.Role;
import com.stockflow.identity.UserRepository;
import com.stockflow.inventory.InventoryPostingService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SyntheticSeeder {

    public static final String SEED_NAME = "synthetic-v1";
    private static final Logger log = LoggerFactory.getLogger(SyntheticSeeder.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final UserRepository users;
    private final InventoryPostingService posting;

    public SyntheticSeeder(JdbcClient jdbc, PlatformTransactionManager tm, UserRepository users,
            InventoryPostingService posting) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
        this.users = users;
        this.posting = posting;
    }

    public boolean seed() {
        Boolean created = tx.execute(status -> {
            jdbc.sql("select pg_advisory_xact_lock(727002)").query(Object.class).optional();
            if (jdbc.sql("select count(*) from demo_seed_runs where seed_name = :n").param("n", SEED_NAME)
                    .query(Long.class).single() > 0) {
                return false;
            }
            Map<String, Object> summary = executeSeed();
            jdbc.sql("insert into demo_seed_runs (seed_name, summary) values (:n, cast(:s as jsonb))")
                    .param("n", SEED_NAME).param("s", new Json().write(summary)).update();
            log.info("synthetic seed applied: {}", summary);
            return true;
        });
        return Boolean.TRUE.equals(created);
    }

    private Map<String, Object> executeSeed() {
        Actor admin = users.findByLogin("admin")
                .map(u -> new Actor(u.id(), u.username(), u.role()))
                .orElseGet(() -> {
                    long id = users.insert("admin", "admin@demo.stockflow.invalid", "Demo Admin",
                            "$2a$10$7EqJtq98hPqEX7fNZaFWoO.83fC6zF.1/pG5wV/4c44X5e.M08eOm", Role.ADMIN, true);
                    return new Actor(id, "admin", Role.ADMIN);
                });

        Map<String, Long> suppliers = new HashMap<>();
        String[][] supData = {
            {"SYN-SUP-01", "PT Sinar Presisi Logam"},
            {"SYN-SUP-02", "PT Delta Mandiri Teknik"},
            {"SYN-SUP-03", "PT Nusantara Distribusi Mandiri"},
            {"SYN-SUP-04", "PT Petro Kimia Energi Sintesis"}
        };
        for (String[] s : supData) {
            Optional<Long> opt = jdbc.sql("select id from suppliers where upper(code) = upper(:c)")
                    .param("c", s[0]).query(Long.class).optional();
            long id = opt.orElseGet(() -> jdbc.sql("insert into suppliers (code, name, demo) values (:c, :n, false) returning id")
                    .param("c", s[0]).param("n", s[1]).query(Long.class).single());
            suppliers.put(s[0], id);
        }

        Map<String, Long> warehouses = new HashMap<>();
        String[][] whData = {
            {"WH-SYN-01", "Gudang Utama Raw Material"},
            {"WH-SYN-02", "Gudang Penyangga Komponen"}
        };
        for (String[] w : whData) {
            Optional<Long> opt = jdbc.sql("select id from warehouses where upper(code) = upper(:c)")
                    .param("c", w[0]).query(Long.class).optional();
            long id = opt.orElseGet(() -> jdbc.sql("insert into warehouses (code, name, description, demo) values (:c, :n, :d, false) returning id")
                    .param("c", w[0]).param("n", w[1]).param("d", "Synthetic warehouse").query(Long.class).single());
            warehouses.put(w[0], id);
        }

        Map<String, Long> locations = new HashMap<>();
        String[][] locData = {
            {"WH-SYN-01", "RACK-A1-01", "RACK"},
            {"WH-SYN-01", "RACK-A1-02", "RACK"},
            {"WH-SYN-01", "RACK-B1-01", "RACK"},
            {"WH-SYN-01", "FLOOR-BLK-01", "FLOOR"},
            {"WH-SYN-01", "STAGE-IN-01", "STAGING"},
            {"WH-SYN-02", "BIN-C1-01", "BIN"},
            {"WH-SYN-02", "BIN-C1-02", "BIN"},
            {"WH-SYN-02", "RACK-D1-01", "RACK"},
            {"WH-SYN-02", "STAGE-IN-02", "STAGING"}
        };
        for (String[] l : locData) {
            long wid = warehouses.get(l[0]);
            Optional<Long> opt = jdbc.sql("select id from storage_locations where warehouse_id = :w and upper(code) = upper(:c)")
                    .param("w", wid).param("c", l[1]).query(Long.class).optional();
            long id = opt.orElseGet(() -> jdbc.sql("insert into storage_locations (warehouse_id, code, name, location_type, demo)"
                            + " values (:w, :c, :n, :t, false) returning id")
                    .param("w", wid).param("c", l[1]).param("n", "Synthetic location " + l[1]).param("t", l[2])
                    .query(Long.class).single());
            locations.put(l[1], id);
        }

        Map<String, Long> materials = new HashMap<>();
        // Ensure industrial UoMs exist
        String[][] extraUoms = {{"DRUM", "Drum", "0"}, {"PAIL", "Pail", "0"}};
        for (String[] u : extraUoms) {
            jdbc.sql("insert into uoms (code, name, scale) values (:c, :n, :s) on conflict (code) do nothing")
                    .param("c", u[0]).param("n", u[1]).param("s", Integer.parseInt(u[2])).update();
        }

        Object[][] matData = {
            {"SYN-MAT-001", "Hexagonal Bolt M12 x 50mm Gr 8.8", "FASTENER", "PC", 200, 500, "RACK-A1-01"},
            {"SYN-MAT-002", "Spiral Wound Gasket 2 inch 150 ANSI", "PIPING", "PC", 50, 120, "RACK-A1-02"},
            {"SYN-MAT-003", "Hydraulic Oil Tellus S2 M 46 (Drum 209L)", "CHEMICAL", "DRUM", 10, 24, "FLOOR-BLK-01"},
            {"SYN-MAT-004", "Deep Groove Ball Bearing 6205-2RS", "SPARE_PART", "PC", 40, 90, "BIN-C1-01"},
            {"SYN-MAT-005", "Nylon Conveyor Belt 650mm EP-400 3-Ply", "RAW_MATERIAL", "M", 100, 250, "RACK-D1-01"},
            {"SYN-MAT-006", "Centrifugal Pump Impeller Cast Iron DN50", "SPARE_PART", "PC", 15, 30, "BIN-C1-02"},
            {"SYN-MAT-007", "Stainless Steel Seamless Pipe 2 inch Sch 40", "PIPING", "M", 60, 150, "RACK-B1-01"},
            {"SYN-MAT-008", "High Temp Grease Complex Lithium NLGI 2 (Pail 15kg)", "CHEMICAL", "PAIL", 20, 45, "FLOOR-BLK-01"},
            {"SYN-MAT-009", "Mechanical Seal Type 21 Shaft 35mm", "SPARE_PART", "SET", 25, 60, "BIN-C1-01"},
            {"SYN-MAT-010", "Flexible Coupling Rubber Insert HRC 150", "SPARE_PART", "PC", 30, 75, "BIN-C1-02"}
        };
        for (Object[] m : matData) {
            String code = (String) m[0];
            Optional<Long> opt = jdbc.sql("select id from materials where upper(code) = upper(:c)")
                    .param("c", code).query(Long.class).optional();
            long id = opt.orElseGet(() -> jdbc.sql("insert into materials (code, name, description, category, uom_code, minimum_stock, demo)"
                            + " values (:c, :n, :d, :cat, :u, :min, false) returning id")
                    .param("c", m[0]).param("n", m[1]).param("d", "Synthetic material")
                    .param("cat", m[2]).param("u", m[3]).param("min", BigDecimal.valueOf((Integer) m[4]))
                    .query(Long.class).single());
            materials.put(code, id);
        }

        // Post Initial Stock Balances through Ledger (strictly idempotent)
        boolean openingBalanceExists = jdbc.sql("select count(*) from stock_movements where document_type = 'OPENING_BALANCE' and document_number = 'SYN-INIT-2026-001'")
                .query(Long.class).single() > 0;
        if (!openingBalanceExists) {
            List<InventoryPostingService.Line> initLines = new ArrayList<>();
            for (Object[] m : matData) {
                String matCode = (String) m[0];
                int initQty = (Integer) m[5];
                String locCode = (String) m[6];
                long matId = materials.get(matCode);
                long locId = locations.get(locCode);
                initLines.add(new InventoryPostingService.Line(matId, locId, BigDecimal.valueOf(initQty)));
            }
            posting.post(admin, new InventoryPostingService.Request(
                    "OPENING_BALANCE", "OPENING_BALANCE", 0L, null, "SYN-INIT-2026-001",
                    "Deterministic synthetic initial balance", initLines));
        }

        // Seed Purchase Orders
        LocalDate refDate = LocalDate.parse("2026-10-01");
        Object[][] poHeaders = {
            {"SYN-PO-2026-001", "SYN-SUP-01", "OPEN"},
            {"SYN-PO-2026-002", "SYN-SUP-02", "OPEN"},
            {"SYN-PO-2026-003", "SYN-SUP-01", "OPEN"},
            {"SYN-PO-2026-004", "SYN-SUP-03", "PARTIALLY_RECEIVED"},
            {"SYN-PO-2026-005", "SYN-SUP-02", "OPEN"},
            {"SYN-PO-2026-006", "SYN-SUP-04", "CANCELLED"},
            {"SYN-PO-2026-007", "SYN-SUP-01", "OPEN"},
            {"SYN-PO-2026-008", "SYN-SUP-03", "OPEN"},
            {"SYN-PO-2026-009", "SYN-SUP-01", "OPEN"},
            {"SYN-PO-2026-010A", "SYN-SUP-02", "OPEN"},
            {"SYN-PO-2026-010B", "SYN-SUP-02", "OPEN"},
            {"SYN-PO-2026-011", "SYN-SUP-01", "OPEN"},
            {"SYN-PO-2026-012", "SYN-SUP-04", "OPEN"}
        };

        Map<String, Long> poMap = new HashMap<>();
        for (Object[] h : poHeaders) {
            String poNum = (String) h[0];
            long supId = suppliers.get((String) h[1]);
            String st = (String) h[2];
            Optional<Long> opt = jdbc.sql("select id from purchase_orders where upper(po_number) = upper(:num)")
                    .param("num", poNum).query(Long.class).optional();
            long poid = opt.orElseGet(() -> jdbc.sql("insert into purchase_orders (po_number, supplier_id, po_date, expected_date, status, demo, created_by, opened_at, cancelled_at)"
                            + " values (:num, :sup, :dt, :edt, :st, false, :usr, now(), case when :st = 'CANCELLED' then now() else null end)"
                            + " returning id")
                    .param("num", poNum).param("sup", supId).param("dt", refDate).param("edt", refDate.plusDays(14))
                    .param("st", st).param("usr", admin.id()).query(Long.class).single());
            poMap.put(poNum, poid);
        }

        // Seed PO Items: {poNum, lineNo, matCode, orderedQty, receivedQty, uom}
        Object[][] poItems = {
            {"SYN-PO-2026-001", 1, "SYN-MAT-001", 100.0, 0.0, "PC"},
            {"SYN-PO-2026-002", 1, "SYN-MAT-002", 40.0, 0.0, "PC"},
            {"SYN-PO-2026-002", 2, "SYN-MAT-004", 30.0, 0.0, "PC"},
            {"SYN-PO-2026-002", 3, "SYN-MAT-009", 20.0, 0.0, "SET"},
            {"SYN-PO-2026-003", 1, "SYN-MAT-005", 100.0, 0.0, "M"},
            {"SYN-PO-2026-004", 1, "SYN-MAT-006", 20.0, 10.0, "PC"},
            {"SYN-PO-2026-005", 1, "SYN-MAT-007", 50.0, 0.0, "M"},
            {"SYN-PO-2026-006", 1, "SYN-MAT-003", 8.0, 0.0, "DRUM"},
            {"SYN-PO-2026-007", 1, "SYN-MAT-001", 200.0, 0.0, "PC"},
            {"SYN-PO-2026-008", 1, "SYN-MAT-004", 25.0, 0.0, "PC"},
            {"SYN-PO-2026-009", 1, "SYN-MAT-008", 15.0, 0.0, "PAIL"},
            {"SYN-PO-2026-010A", 1, "SYN-MAT-010", 20.0, 0.0, "PC"},
            {"SYN-PO-2026-010B", 1, "SYN-MAT-010", 20.0, 0.0, "PC"},
            {"SYN-PO-2026-011", 1, "SYN-MAT-001", 50.0, 0.0, "PC"},
            {"SYN-PO-2026-012", 1, "SYN-MAT-003", 12.0, 0.0, "DRUM"}
        };
        for (Object[] itm : poItems) {
            String poNum = (String) itm[0];
            long poId = poMap.get(poNum);
            int lineNo = (Integer) itm[1];
            long matId = materials.get((String) itm[2]);
            BigDecimal ord = BigDecimal.valueOf((Double) itm[3]);
            BigDecimal rec = BigDecimal.valueOf((Double) itm[4]);
            String uom = (String) itm[5];
            Optional<Long> opt = jdbc.sql("select id from purchase_order_items where purchase_order_id = :p and line_no = :l")
                    .param("p", poId).param("l", lineNo).query(Long.class).optional();
            if (opt.isEmpty()) {
                jdbc.sql("insert into purchase_order_items (purchase_order_id, line_no, material_id, uom_code, ordered_quantity, received_quantity)"
                                + " values (:p, :l, :m, :u, :ord, :rec)")
                        .param("p", poId).param("l", lineNo).param("m", matId).param("u", uom).param("ord", ord).param("rec", rec)
                        .update();
            }
        }

        // Post past receipt movement for PO-2026-004 (partially received)
        long mat6Id = materials.get("SYN-MAT-006");
        long locBinC102 = locations.get("BIN-C1-02");
        posting.post(admin, new InventoryPostingService.Request(
                "GOODS_RECEIPT", "GOODS_RECEIPT", poMap.get("SYN-PO-2026-004"), "GR", "SYN-GR-HIST-001",
                "Historical partial goods receipt for PO SYN-PO-2026-004",
                List.of(new InventoryPostingService.Line(mat6Id, locBinC102, BigDecimal.valueOf(10.0)))));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("suppliers", suppliers.size());
        summary.put("warehouses", warehouses.size());
        summary.put("locations", locations.size());
        summary.put("materials", materials.size());
        summary.put("purchase_orders", poMap.size());
        summary.put("po_items", poItems.length);
        return summary;
    }
}
