package com.stockflow.demo;

import com.stockflow.adjustment.AdjustmentService;
import com.stockflow.common.TimeSource;
import com.stockflow.identity.Actor;
import com.stockflow.identity.Role;
import com.stockflow.identity.UserRepository;
import com.stockflow.inventory.InventoryPostingService;
import com.stockflow.procurement.PurchaseOrderService;
import com.stockflow.stock.StockDocumentService;
import com.stockflow.stock.StockDocumentService.DocType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DemoSeeder {

    public static final String SEED_NAME = "demo-v1";
    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final InventoryPostingService posting;
    private final PurchaseOrderService purchaseOrders;
    private final StockDocumentService docs;
    private final AdjustmentService adjustments;
    private final TimeSource time;
    private final String demoPassword;

    public DemoSeeder(JdbcClient jdbc, PlatformTransactionManager tm, UserRepository users, PasswordEncoder encoder,
            InventoryPostingService posting, PurchaseOrderService purchaseOrders, StockDocumentService docs,
            AdjustmentService adjustments, TimeSource time,
            @Value("${stockflow.demo.password:Demo#2026}") String demoPassword) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
        this.users = users;
        this.encoder = encoder;
        this.posting = posting;
        this.purchaseOrders = purchaseOrders;
        this.docs = docs;
        this.adjustments = adjustments;
        this.time = time;
        this.demoPassword = demoPassword;
    }

    public boolean seed() {
        Boolean created = tx.execute(status -> {
            jdbc.sql("select pg_advisory_xact_lock(727001)").query(Object.class).optional();
            if (jdbc.sql("select count(*) from demo_seed_runs where seed_name = :n").param("n", SEED_NAME)
                    .query(Long.class).single() > 0) {
                return false;
            }
            Map<String, Object> summary = new Builder().build();
            jdbc.sql("insert into demo_seed_runs (seed_name, summary) values (:n, cast(:s as jsonb))")
                    .param("n", SEED_NAME).param("s", new com.stockflow.common.Json().write(summary)).update();
            log.info("demo seed applied: {}", summary);
            return true;
        });
        return Boolean.TRUE.equals(created);
    }

    private final class Builder {
        final Random rnd = new Random(20261002L);
        final LocalDate today = time.today();
        final Map<String, Actor> actor = new LinkedHashMap<>();
        final List<Long> materials = new ArrayList<>();
        final Map<Long, String> uom = new LinkedHashMap<>();
        final List<Long> binsA = new ArrayList<>();
        final List<Long> binsB = new ArrayList<>();
        final List<Long> suppliers = new ArrayList<>();
        int docCount;

        Map<String, Object> build() {
            users();
            master();
            Map<String, Object> s = new LinkedHashMap<>();
            history();
            s.put("users", actor.keySet());
            s.put("materials", materials.size());
            s.put("bins", binsA.size() + binsB.size());
            s.put("documents", docCount);
            s.put("entries", jdbc.sql("select count(*) from stock_movement_entries e join materials m on m.id = e.material_id where m.demo").query(Long.class).single());
            return s;
        }

        void users() {
            Object[][] u = {
                {"admin", "Demo Admin", Role.ADMIN}, {"supervisor", "Demo Supervisor Sari", Role.SUPERVISOR},
                {"supervisor2", "Demo Supervisor Budi", Role.SUPERVISOR}, {"operator", "Demo Operator Andi", Role.OPERATOR},
                {"operator2", "Demo Operator Rina", Role.OPERATOR}, {"viewer", "Demo Viewer", Role.VIEWER}};
            String hash = encoder.encode(demoPassword);
            for (Object[] r : u) {
                String name = (String) r[0];
                long id = users.findByLogin(name).map(x -> x.id()).orElseGet(() ->
                        users.insert(name, name + "@demo.stockflow.invalid", (String) r[1], hash, (Role) r[2], true));
                actor.put(name, new Actor(id, name, (Role) r[2]));
            }
        }

        void master() {
            String[][] sup = {{"DEMO-SUP-01", "Demo Fastener Supply"}, {"DEMO-SUP-02", "Demo Electric Parts"},
                {"DEMO-SUP-03", "Demo Pipe & Fitting"}, {"DEMO-SUP-04", "Demo Chemical Trading"},
                {"DEMO-SUP-05", "Demo Packaging Works"}, {"DEMO-SUP-06", "Demo Safety Gear"}};
            for (String[] s : sup) {
                suppliers.add(jdbc.sql("insert into suppliers (code, name, demo) values (:c, :n, true) returning id")
                        .param("c", s[0]).param("n", s[1]).query(Long.class).single());
            }
            for (String[] w : new String[][] {{"WH-A", "Demo Main Warehouse"}, {"WH-B", "Demo Spare-Part Store"}}) {
                long wid = jdbc.sql("insert into warehouses (code, name, description, demo) values (:c, :n, :d, true) returning id")
                        .param("c", w[0]).param("n", w[1]).param("d", "Synthetic demo warehouse").query(Long.class).single();
                List<Long> target = w[0].equals("WH-A") ? binsA : binsB;
                String[] codes = w[0].equals("WH-A")
                        ? new String[] {"RCV-01", "A-01-01", "A-01-02", "A-02-01", "A-02-02", "A-03-01", "FLR-01", "QRT-01"}
                        : new String[] {"S-01-01", "S-01-02", "S-02-01", "S-02-02", "S-03-01", "STG-01"};
                for (String c : codes) {
                    String type = c.startsWith("RCV") || c.startsWith("STG") ? "STAGING" : c.startsWith("FLR") ? "FLOOR"
                            : c.startsWith("QRT") ? "QUARANTINE" : "BIN";
                    target.add(jdbc.sql("insert into storage_locations (warehouse_id, code, name, location_type, demo)"
                                    + " values (:w, :c, :n, :t, true) returning id")
                            .param("w", wid).param("c", c).param("n", "Demo bin " + c).param("t", type)
                            .query(Long.class).single());
                }
            }
            Object[][] mats = {
                {"FASTENER", "PC", "Hex bolt M8x30", 200}, {"FASTENER", "PC", "Hex nut M8", 300}, {"FASTENER", "BOX", "Flat washer M10 (box 100)", 5},
                {"FASTENER", "PC", "Anchor bolt M12x120", 50}, {"FASTENER", "BOX", "Self-tapping screw 4x25 (box 500)", 4},
                {"ELECTRICAL", "M", "Cable NYY 3x2.5", 150}, {"ELECTRICAL", "PC", "MCB 1P 16A", 20}, {"ELECTRICAL", "ROLL", "Insulation tape 19mm", 30},
                {"ELECTRICAL", "PC", "LED tube 18W", 40}, {"ELECTRICAL", "SET", "Terminal block kit", 6}, {"ELECTRICAL", "M", "Cable tray 100mm", 0},
                {"PIPING", "M", "PVC pipe 1/2 in", 60}, {"PIPING", "PC", "Elbow 90 deg 1/2 in", 80}, {"PIPING", "PC", "Ball valve 1 in", 10},
                {"PIPING", "M", "Galvanised pipe 2 in", 30}, {"PIPING", "PC", "Pipe clamp 2 in", 0},
                {"CHEMICAL", "L", "Hydraulic oil ISO 46", 100}, {"CHEMICAL", "KG", "Grease EP2", 25}, {"CHEMICAL", "L", "Degreaser solvent", 40},
                {"CHEMICAL", "KG", "Epoxy filler", 0}, {"PACKAGING", "ROLL", "Stretch film 500mm", 12}, {"PACKAGING", "PC", "Carton box 40x30x30", 150},
                {"PACKAGING", "M2", "Bubble wrap", 50}, {"PACKAGING", "PC", "Wooden pallet 120x100", 10},
                {"SPARE_PART", "PC", "Bearing 6204-2RS", 20}, {"SPARE_PART", "PC", "V-belt A-42", 8}, {"SPARE_PART", "SET", "Pump seal kit", 3},
                {"SPARE_PART", "PC", "Contactor 25A coil 220V", 4}, {"SPARE_PART", "PC", "Proximity sensor M18", 0},
                {"CONSUMABLE", "BOX", "Cotton rag (box 10kg)", 6}, {"CONSUMABLE", "PC", "Cutting disc 4 in", 100}, {"CONSUMABLE", "KG", "Welding rod E6013", 20},
                {"CONSUMABLE", "PC", "Marker pen black", 0}, {"SAFETY", "PAIR", "Safety gloves nitrile", 60}, {"SAFETY", "PC", "Safety helmet white", 10},
                {"SAFETY", "PAIR", "Safety shoes size 42", 4}, {"SAFETY", "PC", "Ear plug (pair pack)", 0},
                {"RAW_MATERIAL", "KG", "Steel plate 3mm", 500}, {"RAW_MATERIAL", "M", "Angle bar 40x40", 60}, {"RAW_MATERIAL", "KG", "Aluminium ingot", 0}};
            int i = 0;
            for (Object[] m : mats) {
                String code = String.format("DEMO-%s-%03d", ((String) m[0]).substring(0, 3), ++i);
                long id = jdbc.sql("insert into materials (code, name, description, category, uom_code, minimum_stock, demo)"
                                + " values (:c, :n, :d, :cat, :u, :min, true) returning id")
                        .param("c", code).param("n", "[DEMO] " + m[2]).param("d", "Synthetic demo material")
                        .param("cat", m[0]).param("u", m[1]).param("min", BigDecimal.valueOf((Integer) m[3]))
                        .query(Long.class).single();
                materials.add(id);
                uom.put(id, (String) m[1]);
            }
        }

        BigDecimal qty(long material, int lo, int hi) {
            int v = lo + rnd.nextInt(hi - lo + 1);
            String u = uom.get(material);
            if (u.equals("KG") || u.equals("L")) {
                return new BigDecimal(v).add(new BigDecimal(rnd.nextInt(4) * 250).movePointLeft(3));
            }
            if (u.equals("M") || u.equals("M2")) {
                return new BigDecimal(v).add(new BigDecimal(rnd.nextInt(2) * 50).movePointLeft(2));
            }
            return new BigDecimal(v);
        }

        void at(int daysAgo, int hour, int minute) {
            TimeSource.pin(today.minusDays(daysAgo).atTime(LocalTime.of(hour, minute)).atZone(time.zone()).toInstant());
        }

        void history() {
            try {

                at(14, 7, 30);
                List<InventoryPostingService.Line> open = new ArrayList<>();
                for (int i = 0; i < 30; i++) {
                    long m = materials.get(i);
                    List<Long> bins = i % 3 == 2 ? binsB : binsA;
                    long bin = bins.get(1 + (i % (bins.size() - 2)));
                    open.add(new InventoryPostingService.Line(m, bin, qty(m, 20, 400)));
                    if (i % 4 == 0) {
                        open.add(new InventoryPostingService.Line(m, (i % 3 == 2 ? binsA : binsB).get(2), qty(m, 5, 60)));
                    }
                }

                posting.post(actor.get("admin"), new InventoryPostingService.Request("OPENING_BALANCE", "OPENING_BALANCE",
                        null, "OPN", null, "Demo opening balance part 1", open.subList(0, open.size() / 2)));
                posting.post(actor.get("admin"), new InventoryPostingService.Request("OPENING_BALANCE", "OPENING_BALANCE",
                        null, "OPN", null, "Demo opening balance part 2", open.subList(open.size() / 2, open.size())));
                docCount += 2;

                List<Long> pos = new ArrayList<>();
                for (int p = 0; p < 8; p++) {
                    at(13 - p, 9, 10 + p);
                    List<PurchaseOrderService.ItemInput> items = new ArrayList<>();
                    int n = 2 + (p % 3);
                    for (int k = 0; k < n; k++) {
                        long m = materials.get((p * 5 + k * 3) % materials.size());
                        if (items.stream().anyMatch(it -> it.materialId() == m)) {
                            continue;
                        }
                        items.add(new PurchaseOrderService.ItemInput(m, qty(m, 40, 200)));
                    }
                    var po = purchaseOrders.create(actor.get(p % 2 == 0 ? "supervisor" : "admin"),
                            new PurchaseOrderService.PoInput(suppliers.get(p % suppliers.size()), time.today(),
                                    time.today().plusDays(7 + p), "Demo purchase order " + (p + 1), items, null), true);
                    pos.add(po.id());
                    if (p != 0) {
                        purchaseOrders.open(actor.get("supervisor"), po.id());
                    }
                }
                at(10, 15, 0);
                purchaseOrders.cancel(actor.get("supervisor"), pos.get(1), "Demo: supplier could not deliver");

                int[] partialPos = {3, 4, 5, 6};
                int day = 9;
                for (int idx : partialPos) {
                    at(day--, 10, 15);
                    var po = purchaseOrders.get(pos.get(idx));
                    List<StockDocumentService.ReceiptLineInput> lines = new ArrayList<>();
                    for (var it : po.items()) {
                        BigDecimal half = it.orderedQuantity().divide(BigDecimal.valueOf(2)).setScale(it.uomScale(),
                                java.math.RoundingMode.DOWN);
                        if (half.signum() > 0) {
                            lines.add(new StockDocumentService.ReceiptLineInput(it.id(), binsA.get(0), half));
                        }
                    }
                    docs.receive(actor.get(idx % 2 == 0 ? "operator" : "operator2"),
                            new StockDocumentService.ReceiptInput(po.id(), "DEMO-DN-" + (1000 + idx), null, lines));
                    docCount++;
                }
                var po7 = purchaseOrders.get(pos.get(7));
                for (int r = 0; r < 2; r++) {
                    at(5 - r * 3, 11, 0);
                    List<StockDocumentService.ReceiptLineInput> lines = new ArrayList<>();
                    for (var it : purchaseOrders.get(po7.id()).items()) {
                        BigDecimal q = r == 0 ? it.orderedQuantity().divide(BigDecimal.valueOf(2)).setScale(it.uomScale(),
                                java.math.RoundingMode.DOWN) : it.outstandingQuantity();
                        if (q.signum() > 0) {
                            lines.add(new StockDocumentService.ReceiptLineInput(it.id(), binsB.get(5), q));
                        }
                    }
                    docs.receive(actor.get("operator"), new StockDocumentService.ReceiptInput(po7.id(),
                            "DEMO-DN-20" + r, "Demo delivery " + (r + 1), lines));
                    docCount++;
                }

                at(4, 14, 30);
                long grToReverse = jdbc.sql("select id from goods_receipts where purchase_order_id = :po")
                        .param("po", pos.get(6)).query(Long.class).single();
                docs.reverse(actor.get("supervisor"), DocType.GOODS_RECEIPT, grToReverse,
                        new StockDocumentService.ReversalInput("Demo: receipt posted against wrong PO"));
                docCount++;

                String[] reasons = {"PRODUCTION", "MAINTENANCE", "INTERNAL_USE", "SAMPLE", "SCRAP"};
                List<Long> issueIds = new ArrayList<>();
                List<Long> transferIds = new ArrayList<>();
                for (int d = 8; d >= 0; d--) {
                    for (int k = 0; k < 3; k++) {
                        at(d, 8 + k * 3, 5 + k);
                        var avail = available(2 + rnd.nextInt(2));
                        if (avail.isEmpty()) {
                            continue;
                        }
                        List<StockDocumentService.IssueLineInput> lines = new ArrayList<>();
                        for (var b : avail) {
                            BigDecimal q = part(b.quantity(), b.materialId());
                            if (q.signum() > 0) {
                                lines.add(new StockDocumentService.IssueLineInput(b.materialId(), b.locationId(), q));
                            }
                        }
                        if (lines.isEmpty()) {
                            continue;
                        }
                        var doc = docs.issue(actor.get(k % 2 == 0 ? "operator" : "operator2"),
                                new StockDocumentService.IssueInput(reasons[(d + k) % reasons.length],
                                        String.format("DEMO-WO-%04d", 300 + d * 3 + k), null, lines));
                        issueIds.add(doc.id());
                        docCount++;
                    }
                    if (d % 2 == 0) {
                        at(d, 16, 20);
                        var avail = available(2);
                        List<StockDocumentService.TransferLineInput> lines = new ArrayList<>();
                        for (var b : avail) {
                            BigDecimal q = part(b.quantity(), b.materialId());
                            long dest = binsA.contains(b.locationId()) ? binsB.get(1 + rnd.nextInt(4)) : binsA.get(1 + rnd.nextInt(5));
                            if (q.signum() > 0) {
                                lines.add(new StockDocumentService.TransferLineInput(b.materialId(), b.locationId(), dest, q));
                            }
                        }
                        if (!lines.isEmpty()) {
                            var doc = docs.transfer(actor.get("operator"),
                                    new StockDocumentService.TransferInput("DEMO-MOVE-" + d, "Demo replenishment", lines));
                            transferIds.add(doc.id());
                            docCount++;
                        }
                    }
                }

                at(0, 17, 30);
                docs.reverse(actor.get("supervisor2"), DocType.STOCK_ISSUE, issueIds.get(issueIds.size() - 2),
                        new StockDocumentService.ReversalInput("Demo: issued to wrong work order"));
                if (!transferIds.isEmpty()) {
                    docs.reverse(actor.get("supervisor2"), DocType.STOCK_TRANSFER, transferIds.get(transferIds.size() - 1),
                            new StockDocumentService.ReversalInput("Demo: transfer not physically executed"));
                    docCount++;
                }
                docCount++;

                at(0, 18, 0);
                adjustDemo("operator", "supervisor", true, 0);
                adjustDemo("operator2", "supervisor", false, 1);
                at(0, 18, 30);
                adjustDemo("operator", null, false, 2);
                adjustDemo("supervisor", null, false, 3);
            } finally {
                TimeSource.unpin();
            }
        }

        record Avail(long materialId, long locationId, BigDecimal quantity) {}

        List<Avail> available(int n) {
            List<Avail> all = jdbc.sql("select material_id, location_id, quantity from inventory_balances where quantity >= 4"
                    + " and material_id in (select id from materials where demo)"
                    + " order by material_id, location_id").query(Avail.class).list();
            List<Avail> pick = new ArrayList<>();
            java.util.Set<Long> mats = new java.util.HashSet<>();
            for (int i = 0; i < 20 && pick.size() < n && !all.isEmpty(); i++) {
                Avail a = all.get(rnd.nextInt(all.size()));
                if (mats.add(a.materialId())) {
                    pick.add(a);
                }
            }
            return pick;
        }

        BigDecimal part(BigDecimal onHand, long material) {
            int pct = 5 + rnd.nextInt(16);
            int scale = switch (uom.get(material)) {
                case "KG", "L" -> 3;
                case "M", "M2" -> 2;
                default -> 0;
            };
            BigDecimal q = onHand.multiply(BigDecimal.valueOf(pct)).divide(BigDecimal.valueOf(100), scale,
                    java.math.RoundingMode.DOWN);
            return q.signum() > 0 ? q : BigDecimal.ONE.min(onHand);
        }

        void adjustDemo(String requester, String decider, boolean approve, int pickIndex) {
            List<Avail> all = jdbc.sql("select material_id, location_id, quantity from inventory_balances where quantity >= 4"
                    + " and material_id in (select id from materials where demo)"
                    + " order by material_id desc, location_id").query(Avail.class).list();
            Avail a = all.get(pickIndex * 3 % all.size());
            var snap = adjustments.snapshot(a.materialId(), a.locationId());
            BigDecimal physical = pickIndex % 2 == 0 ? snap.quantity().subtract(BigDecimal.ONE) : snap.quantity().add(BigDecimal.valueOf(2));
            var view = adjustments.request(actor.get(requester), new AdjustmentService.RequestInput(a.materialId(),
                    a.locationId(), physical, snap.quantity(), snap.version(), "Demo cycle count difference"));
            docCount++;
            if (decider != null) {
                if (approve) {
                    adjustments.approve(actor.get(decider), view.id(), new AdjustmentService.DecisionInput("Recount confirmed"));
                } else {
                    adjustments.reject(actor.get(decider), view.id(), new AdjustmentService.DecisionInput("Recount matched system"));
                }
            }
        }
    }
}
