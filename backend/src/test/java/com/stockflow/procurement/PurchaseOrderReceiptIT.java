package com.stockflow.procurement;

import static com.stockflow.inventory.InventoryCoreIT.race;
import static org.assertj.core.api.Assertions.assertThat;

import com.stockflow.support.ApiClient;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.TestData;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PurchaseOrderReceiptIT extends IntegrationTest {

    TestData data;
    long sup;
    long wh;
    long bin1;
    long bin2;
    ApiClient sv;
    ApiClient op;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        data.standardUsers();
        String s = Long.toString(System.nanoTime(), 36).toUpperCase();
        sup = data.supplier("S" + s);
        wh = data.warehouse("W" + s);
        bin1 = data.bin(wh, "B1" + s);
        bin2 = data.bin(wh, "B2" + s);
        sv = new ApiClient(port).as("t_super");
        op = new ApiClient(port).as("t_oper");
    }

    long mat(String uom) {
        return data.material("M" + Long.toString(System.nanoTime(), 36).toUpperCase(), uom);
    }

    ApiClient.Res createPo(Object... matQty) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < matQty.length; i += 2) {
            items.add(Map.of("materialId", matQty[i], "orderedQuantity", new BigDecimal(matQty[i + 1].toString())));
        }
        return sv.command("/api/purchase-orders", Map.of("supplierId", sup, "poDate", LocalDate.now().toString(),
                "items", items));
    }

    long itemId(ApiClient.Res po, int idx) {
        return po.body().get("items").get(idx).get("id").asLong();
    }

    @Test
    void stateMachineAndPartialReceiptLifecycle() {
        long m1 = mat("PC");
        long m2 = mat("KG");
        ApiClient.Res po = createPo(m1, "10", m2, "5.5");
        assertThat(po.status()).isEqualTo(201);
        assertThat(po.body().get("status").asString()).isEqualTo("DRAFT");
        assertThat(po.body().get("poNumber").asString()).matches("PO-\\d{4}-\\d{6}");
        long poId = po.id();

        ApiClient.Res early = op.command("/api/goods-receipts", Map.of("purchaseOrderId", poId, "lines",
                List.of(Map.of("purchaseOrderItemId", itemId(po, 0), "locationId", bin1, "quantity", 1))));
        assertThat(early.code()).isEqualTo("INVALID_STATE");

        assertThat(op.command("/api/purchase-orders/" + poId + "/open", Map.of()).status()).isEqualTo(403);
        ApiClient.Res opened = sv.command("/api/purchase-orders/" + poId + "/open", Map.of());
        assertThat(opened.body().get("status").asString()).isEqualTo("OPEN");
        ApiClient.Res edit = sv.put("/api/purchase-orders/" + poId, Map.of("supplierId", sup, "poDate",
                LocalDate.now().toString(), "items", List.of(Map.of("materialId", m1, "orderedQuantity", 1)), "version", 1));
        assertThat(edit.code()).isEqualTo("INVALID_STATE");

        ApiClient.Res gr1 = op.command("/api/goods-receipts", Map.of("purchaseOrderId", poId, "reference", "DN-1",
                "lines", List.of(Map.of("purchaseOrderItemId", itemId(po, 0), "locationId", bin1, "quantity", 4),
                        Map.of("purchaseOrderItemId", itemId(po, 0), "locationId", bin2, "quantity", 2),
                        Map.of("purchaseOrderItemId", itemId(po, 1), "locationId", bin1, "quantity", "2.25"))));
        assertThat(gr1.status()).as(String.valueOf(gr1.body())).isEqualTo(201);
        assertThat(gr1.body().get("documentNumber").asString()).matches("GR-\\d{4}-\\d{6}");
        ApiClient.Res after1 = sv.get("/api/purchase-orders/" + poId);
        assertThat(after1.body().get("status").asString()).isEqualTo("PARTIALLY_RECEIVED");
        assertThat(after1.body().get("items").get(0).get("outstandingQuantity").decimalValue()).isEqualByComparingTo("4");
        assertThat(after1.body().get("items").get(1).get("outstandingQuantity").decimalValue()).isEqualByComparingTo("3.25");

        ApiClient.Res over = op.command("/api/goods-receipts", Map.of("purchaseOrderId", poId, "lines", List.of(
                Map.of("purchaseOrderItemId", itemId(po, 0), "locationId", bin1, "quantity", 3),
                Map.of("purchaseOrderItemId", itemId(po, 0), "locationId", bin2, "quantity", 2))));
        assertThat(over.code()).isEqualTo("PO_OUTSTANDING_EXCEEDED");

        ApiClient.Res gr2 = op.command("/api/goods-receipts", Map.of("purchaseOrderId", poId, "lines", List.of(
                Map.of("purchaseOrderItemId", itemId(po, 0), "locationId", bin1, "quantity", 4),
                Map.of("purchaseOrderItemId", itemId(po, 1), "locationId", bin2, "quantity", "3.25"))));
        assertThat(gr2.status()).isEqualTo(201);
        assertThat(sv.get("/api/purchase-orders/" + poId).body().get("status").asString()).isEqualTo("COMPLETED");
        assertThat(data.balance(m1, bin1)).isEqualByComparingTo("8");
        assertThat(data.balance(m1, bin2)).isEqualByComparingTo("2");
        assertThat(data.balance(m2, bin1)).isEqualByComparingTo("2.25");

        assertThat(sv.command("/api/purchase-orders/" + poId + "/cancel", Map.of("reason", "x")).code()).isEqualTo("INVALID_STATE");

        ApiClient.Res rev = sv.command("/api/goods-receipts/" + gr2.id() + "/reverse", Map.of("reason", "wrong DN"));
        assertThat(rev.status()).as(String.valueOf(rev.body())).isEqualTo(200);
        assertThat(rev.body().get("status").asString()).isEqualTo("REVERSED");
        assertThat(rev.body().get("reversalDocumentNumber").asString()).matches("REV-\\d{4}-\\d{6}");
        assertThat(sv.get("/api/purchase-orders/" + poId).body().get("status").asString()).isEqualTo("PARTIALLY_RECEIVED");
        assertThat(data.balance(m1, bin1)).isEqualByComparingTo("4");
        assertThat(data.mismatches()).isZero();
        Long audit = jdbc.queryForObject("select count(*) from audit_logs where entity_type = 'GOODS_RECEIPT' and entity_id = ?",
                Long.class, Long.toString(gr2.id()));
        assertThat(audit).isEqualTo(2);
    }

    @Test
    void cancelKeepsReceiptsAndClosesOutstanding() {
        long m = mat("PC");
        ApiClient.Res po = createPo(m, "10");
        sv.command("/api/purchase-orders/" + po.id() + "/open", Map.of());
        op.command("/api/goods-receipts", Map.of("purchaseOrderId", po.id(), "lines",
                List.of(Map.of("purchaseOrderItemId", itemId(po, 0), "locationId", bin1, "quantity", 3))));
        assertThat(sv.command("/api/purchase-orders/" + po.id() + "/cancel", Map.of()).status()).isEqualTo(400);
        ApiClient.Res c = sv.command("/api/purchase-orders/" + po.id() + "/cancel", Map.of("reason", "supplier closed"));
        assertThat(c.body().get("status").asString()).isEqualTo("CANCELLED");
        assertThat(c.body().get("items").get(0).get("receivedQuantity").decimalValue()).isEqualByComparingTo("3");
        assertThat(c.body().get("items").get(0).get("outstandingQuantity").decimalValue()).isEqualByComparingTo("0");
        assertThat(op.command("/api/goods-receipts", Map.of("purchaseOrderId", po.id(), "lines",
                List.of(Map.of("purchaseOrderItemId", itemId(po, 0), "locationId", bin1, "quantity", 1)))).code())
                .isEqualTo("INVALID_STATE");
        assertThat(data.balance(m, bin1)).isEqualByComparingTo("3");
    }

    @Test
    void poValidation() {
        long m = mat("PC");
        ApiClient.Res empty = sv.command("/api/purchase-orders", Map.of("supplierId", sup, "poDate", LocalDate.now().toString(),
                "items", List.of()));
        assertThat(empty.status()).isEqualTo(400);
        ApiClient.Res scale = createPo(m, "1.5");
        assertThat(scale.code()).isEqualTo("QUANTITY_SCALE_INVALID");
        ApiClient.Res dup = createPo(m, "1", m, "2");
        assertThat(dup.code()).isEqualTo("DUPLICATE_LINE");
    }

    @Test
    void concurrentReceiptsCannotExceedOutstanding() throws Exception {
        long m = mat("PC");
        ApiClient.Res po = createPo(m, "10");
        sv.command("/api/purchase-orders/" + po.id() + "/open", Map.of());
        long item = itemId(po, 0);
        List<ApiClient> cs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            cs.add(new ApiClient(port).as(i % 2 == 0 ? "t_oper" : "t_oper2"));
        }

        List<ApiClient.Res> rs = race(6, i -> cs.get(i).command("/api/goods-receipts", Map.of("purchaseOrderId", po.id(),
                "lines", List.of(Map.of("purchaseOrderItemId", item, "locationId", bin2, "quantity", 4)))));
        long ok = rs.stream().filter(r -> r.status() == 201).count();
        assertThat(ok).isEqualTo(2);
        assertThat(rs.stream().filter(r -> "PO_OUTSTANDING_EXCEEDED".equals(r.code())).count()).isEqualTo(4);
        BigDecimal received = jdbc.queryForObject("select received_quantity from purchase_order_items where id = ?",
                BigDecimal.class, item);
        assertThat(received).isEqualByComparingTo("8");
        assertThat(data.balance(m, bin2)).isEqualByComparingTo("8");
        Long rows = jdbc.queryForObject("select count(*) from inventory_balances where material_id = ? and location_id = ?",
                Long.class, m, bin2);
        assertThat(rows).isEqualTo(1);
        assertThat(data.mismatches()).isZero();
    }

    @Test
    void inactiveBinCannotReceive() {
        long m = mat("PC");
        ApiClient.Res po = createPo(m, "10");
        sv.command("/api/purchase-orders/" + po.id() + "/open", Map.of());
        jdbc.update("update storage_locations set active = false where id = ?", bin2);
        ApiClient.Res r = op.command("/api/goods-receipts", Map.of("purchaseOrderId", po.id(), "lines",
                List.of(Map.of("purchaseOrderItemId", itemId(po, 0), "locationId", bin2, "quantity", 1))));
        assertThat(r.code()).isEqualTo("LOCATION_INACTIVE");
    }
}
