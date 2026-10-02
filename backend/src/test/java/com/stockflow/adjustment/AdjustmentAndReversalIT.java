package com.stockflow.adjustment;

import static com.stockflow.inventory.InventoryCoreIT.race;
import static org.assertj.core.api.Assertions.assertThat;

import com.stockflow.identity.Actor;
import com.stockflow.identity.Role;
import com.stockflow.inventory.InventoryPostingService;
import com.stockflow.support.ApiClient;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.TestData;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class AdjustmentAndReversalIT extends IntegrationTest {

    @Autowired
    InventoryPostingService posting;
    @Autowired
    PlatformTransactionManager tm;

    TestData data;
    long bin;
    long bin2;
    long adminId;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        data.standardUsers();
        adminId = data.user("t_admin", "ADMIN");
        String s = Long.toString(System.nanoTime(), 36).toUpperCase();
        long wh = data.warehouse("W" + s);
        bin = data.bin(wh, "B" + s);
        bin2 = data.bin(wh, "C" + s);
    }

    long matWith(String qty) {
        long m = data.material("M" + Long.toString(System.nanoTime(), 36).toUpperCase(), "PC");
        if (qty != null) {
            new TransactionTemplate(tm).executeWithoutResult(st -> posting.post(new Actor(adminId, "t_admin", Role.ADMIN),
                    new InventoryPostingService.Request("OPENING_BALANCE", "OPENING_BALANCE", null, "OPN", null, "t",
                            List.of(new InventoryPostingService.Line(m, bin, new BigDecimal(qty))))));
        }
        return m;
    }

    ApiClient.Res request(ApiClient c, long m, String physical) {
        ApiClient.Res snap = c.get("/api/stock-adjustments/count-snapshot?materialId=" + m + "&locationId=" + bin);
        return c.command("/api/stock-adjustments", Map.of("materialId", m, "locationId", bin, "physicalQuantity",
                new BigDecimal(physical), "observedQuantity", snap.body().get("quantity").decimalValue(),
                "observedVersion", snap.body().get("version").asLong(), "reason", "cycle count"));
    }

    @Test
    void approveRejectSelfApprovalAndStale() {
        long m = matWith("20");
        ApiClient op = new ApiClient(port).as("t_oper");
        ApiClient sv = new ApiClient(port).as("t_super");
        ApiClient admin = new ApiClient(port).as("t_admin");

        assertThat(request(op, m, "20").code()).isEqualTo("ZERO_DELTA");
        ApiClient.Res req = request(op, m, "17");
        assertThat(req.status()).isEqualTo(201);
        assertThat(req.body().get("status").asString()).isEqualTo("PENDING");
        assertThat(data.balance(m, bin)).as("request does not touch stock").isEqualByComparingTo("20");
        assertThat(op.command("/api/stock-adjustments/" + req.id() + "/approve", Map.of()).status()).isEqualTo(403);
        ApiClient.Res ok = sv.command("/api/stock-adjustments/" + req.id() + "/approve", Map.of("note", "ok"));
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().get("status").asString()).isEqualTo("APPROVED");
        assertThat(data.balance(m, bin)).isEqualByComparingTo("17");
        assertThat(sv.command("/api/stock-adjustments/" + req.id() + "/approve", Map.of()).code()).isEqualTo("INVALID_STATE");

        ApiClient.Res own = request(admin, m, "18");
        ApiClient.Res self = admin.command("/api/stock-adjustments/" + own.id() + "/approve", Map.of());
        assertThat(self.status()).isEqualTo(403);
        assertThat(self.code()).isEqualTo("SELF_APPROVAL_FORBIDDEN");
        assertThat(admin.command("/api/stock-adjustments/" + own.id() + "/reject", Map.of("note", "x")).code())
                .isEqualTo("SELF_APPROVAL_FORBIDDEN");

        assertThat(sv.command("/api/stock-adjustments/" + own.id() + "/reject", Map.of()).status()).isEqualTo(400);
        ApiClient.Res rej = sv.command("/api/stock-adjustments/" + own.id() + "/reject", Map.of("note", "recount matched"));
        assertThat(rej.body().get("status").asString()).isEqualTo("REJECTED");
        assertThat(data.balance(m, bin)).isEqualByComparingTo("17");

        ApiClient.Res stale = request(op, m, "10");
        op.command("/api/stock-issues", Map.of("reasonCode", "PRODUCTION", "reference", "WO",
                "lines", List.of(Map.of("materialId", m, "locationId", bin, "quantity", 2))));
        ApiClient.Res st = sv.command("/api/stock-adjustments/" + stale.id() + "/approve", Map.of());
        assertThat(st.status()).isEqualTo(409);
        assertThat(st.code()).isEqualTo("STALE_COUNT");
        assertThat(sv.get("/api/stock-adjustments/" + stale.id()).body().get("status").asString()).isEqualTo("PENDING");
        assertThat(sv.get("/api/stock-adjustments/" + stale.id()).body().get("stale").asBoolean()).isTrue();
        assertThat(data.balance(m, bin)).isEqualByComparingTo("15");

        ApiClient.Res outdated = op.command("/api/stock-adjustments", Map.of("materialId", m, "locationId", bin,
                "physicalQuantity", 1, "observedQuantity", 20, "observedVersion", 1, "reason", "old screen"));
        assertThat(outdated.code()).isEqualTo("STALE_COUNT");

        try {
            jdbc.update("update stock_adjustments set status = 'REJECTED', decided_by = requested_by where id = ?", stale.id());
            throw new AssertionError("DB accepted self decision");
        } catch (org.springframework.dao.DataAccessException expected) {
            assertThat(expected.getMessage()).contains("ck_adj_no_self_approval");
        }
        assertThat(data.mismatches()).isZero();
    }

    @Test
    void concurrentDoubleApprovalPostsOnce() throws Exception {
        long m = matWith("50");
        ApiClient op = new ApiClient(port).as("t_oper");
        ApiClient.Res req = request(op, m, "45");
        ApiClient a = new ApiClient(port).as("t_super");
        ApiClient b = new ApiClient(port).as("t_super2");
        List<ApiClient.Res> rs = race(2, i -> (i == 0 ? a : b).command("/api/stock-adjustments/" + req.id() + "/approve",
                Map.of()));
        assertThat(rs.stream().filter(r -> r.status() == 200).count()).isEqualTo(1);
        assertThat(rs.stream().filter(r -> "INVALID_STATE".equals(r.code())).count()).isEqualTo(1);
        assertThat(data.balance(m, bin)).isEqualByComparingTo("45");
        Long movements = jdbc.queryForObject("select count(*) from stock_movements where document_type = 'STOCK_ADJUSTMENT'"
                + " and document_id = ?", Long.class, req.id());
        assertThat(movements).isEqualTo(1);
    }

    @Test
    void approveVersusRejectRaceHasOneWinner() throws Exception {
        long m = matWith("50");
        ApiClient.Res req = request(new ApiClient(port).as("t_oper"), m, "49");
        ApiClient a = new ApiClient(port).as("t_super");
        ApiClient b = new ApiClient(port).as("t_super2");
        List<ApiClient.Res> rs = race(2, i -> i == 0 ? a.command("/api/stock-adjustments/" + req.id() + "/approve", Map.of())
                : b.command("/api/stock-adjustments/" + req.id() + "/reject", Map.of("note", "no")));
        assertThat(rs.stream().filter(r -> r.status() == 200).count()).isEqualTo(1);
        String status = jdbc.queryForObject("select status from stock_adjustments where id = ?", String.class, req.id());
        assertThat(data.balance(m, bin)).isEqualByComparingTo(status.equals("APPROVED") ? "49" : "50");
    }

    @Test
    void reversalOfIssueAndTransferWithDuplicateProtection() throws Exception {
        long m = matWith("30");
        ApiClient op = new ApiClient(port).as("t_oper");
        ApiClient sv = new ApiClient(port).as("t_super");
        ApiClient sv2 = new ApiClient(port).as("t_super2");
        ApiClient.Res iss = op.command("/api/stock-issues", Map.of("reasonCode", "MAINTENANCE", "reference", "WO-9",
                "lines", List.of(Map.of("materialId", m, "locationId", bin, "quantity", 10))));
        assertThat(op.command("/api/stock-issues/" + iss.id() + "/reverse", Map.of("reason", "x")).status()).isEqualTo(403);
        assertThat(sv.command("/api/stock-issues/" + iss.id() + "/reverse", Map.of()).status()).isEqualTo(400);

        List<ApiClient.Res> rs = race(2, i -> (i == 0 ? sv : sv2).command("/api/stock-issues/" + iss.id() + "/reverse",
                Map.of("reason", "wrong WO")));
        assertThat(rs.stream().filter(r -> r.status() == 200).count()).isEqualTo(1);
        assertThat(rs.stream().filter(r -> "ALREADY_REVERSED".equals(r.code())).count()).isEqualTo(1);
        assertThat(data.balance(m, bin)).isEqualByComparingTo("30");
        ApiClient.Res doc = sv.get("/api/stock-issues/" + iss.id());
        assertThat(doc.body().get("status").asString()).isEqualTo("REVERSED");
        assertThat(doc.body().get("reversalMovementNumber").asString()).startsWith("MOV-");
        Long linked = jdbc.queryForObject("select count(*) from stock_movements r join stock_issues i on"
                + " r.reversal_of_movement_id = i.movement_id where i.id = ?", Long.class, iss.id());
        assertThat(linked).isEqualTo(1);

        long origMv = jdbc.queryForObject("select movement_id from stock_issues where id = ?", Long.class, iss.id());
        try {
            jdbc.update("insert into stock_movements (movement_number, movement_type, document_type, document_number,"
                    + " reversal_of_movement_id, posted_by, business_date) values ('X-DUP', 'REVERSAL', 'STOCK_ISSUE', 'X', ?, ?,"
                    + " current_date)", origMv, adminId);
            throw new AssertionError("duplicate reversal accepted by DB");
        } catch (org.springframework.dao.DataAccessException expected) {
            assertThat(expected.getMessage()).contains("reversal_of_movement_id");
        }

        try {
            jdbc.update("update stock_issues set reference = 'changed' where id = ?", iss.id());
            throw new AssertionError("posted document edited");
        } catch (org.springframework.dao.DataAccessException expected) {
            assertThat(expected.getMessage()).contains("immutable");
        }

        ApiClient.Res trf = op.command("/api/stock-transfers", Map.of("lines", List.of(
                Map.of("materialId", m, "fromLocationId", bin, "toLocationId", bin2, "quantity", 12))));
        op.command("/api/stock-issues", Map.of("reasonCode", "PRODUCTION", "reference", "WO-10",
                "lines", List.of(Map.of("materialId", m, "locationId", bin2, "quantity", 5))));
        ApiClient.Res blocked = sv.command("/api/stock-transfers/" + trf.id() + "/reverse", Map.of("reason", "undo"));
        assertThat(blocked.status()).isEqualTo(409);
        assertThat(blocked.code()).isEqualTo("REVERSAL_INSUFFICIENT_STOCK");
        assertThat(sv.get("/api/stock-transfers/" + trf.id()).body().get("status").asString()).isEqualTo("POSTED");
        assertThat(data.mismatches()).isZero();
    }

    @Test
    void reversalReplayIsIdempotent() {
        long m = matWith("10");
        ApiClient op = new ApiClient(port).as("t_oper");
        ApiClient sv = new ApiClient(port).as("t_super");
        ApiClient.Res iss = op.command("/api/stock-issues", Map.of("reasonCode", "SAMPLE", "reference", "S-1",
                "lines", List.of(Map.of("materialId", m, "locationId", bin, "quantity", 4))));
        String key = "rev-" + System.nanoTime();
        ApiClient.Res r1 = sv.command("/api/stock-issues/" + iss.id() + "/reverse", Map.of("reason", "oops"), key);
        ApiClient.Res r2 = sv.command("/api/stock-issues/" + iss.id() + "/reverse", Map.of("reason", "oops"), key);
        assertThat(r1.status()).isEqualTo(200);
        assertThat(r2.status()).isEqualTo(200);
        assertThat(r2.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(data.balance(m, bin)).isEqualByComparingTo("10");
    }
}
