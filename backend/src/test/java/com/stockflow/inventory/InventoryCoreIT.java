package com.stockflow.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockflow.support.ApiClient;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.TestData;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public class InventoryCoreIT extends IntegrationTest {

    @Autowired
    InventoryPostingService posting;
    @Autowired
    PlatformTransactionManager tm;

    TestData data;
    long adminId;
    long wh;
    long binA;
    long binB;
    long binC;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        data.standardUsers();
        adminId = data.user("t_admin", "ADMIN");
        String sfx = Long.toString(System.nanoTime(), 36).toUpperCase();
        wh = data.warehouse("W" + sfx);
        binA = data.bin(wh, "A" + sfx);
        binB = data.bin(wh, "B" + sfx);
        binC = data.bin(wh, "C" + sfx);
    }

    private long material(String uom) {
        return data.material("M" + Long.toString(System.nanoTime(), 36).toUpperCase(), uom);
    }

    private void opening(long material, long location, String qty) {
        new TransactionTemplate(tm).executeWithoutResult(s -> posting.post(
                new com.stockflow.identity.Actor(adminId, "t_admin", com.stockflow.identity.Role.ADMIN),
                new InventoryPostingService.Request("OPENING_BALANCE", "OPENING_BALANCE", null, "OPN", null, "test",
                        List.of(new InventoryPostingService.Line(material, location, new BigDecimal(qty))))));
    }

    private Map<String, Object> issueBody(long m, long loc, String qty) {
        return Map.of("reasonCode", "PRODUCTION", "reference", "WO-1",
                "lines", List.of(Map.of("materialId", m, "locationId", loc, "quantity", new BigDecimal(qty))));
    }

    @Test
    void databaseRejectsNegativeBalanceAndLedgerMutation() {
        long m = material("PC");
        opening(m, binA, "5");
        try {
            jdbc.update("update inventory_balances set quantity = -1 where material_id = ?", m);
            throw new AssertionError("negative balance accepted");
        } catch (DataAccessException expected) {
            assertThat(expected.getMessage()).contains("ck_balance_nonnegative");
        }
        try {
            jdbc.update("update stock_movement_entries set quantity_delta = 99 where material_id = ?", m);
            throw new AssertionError("ledger update accepted");
        } catch (DataAccessException expected) {
            assertThat(expected.getMessage()).contains("append-only");
        }
        try {
            jdbc.update("delete from stock_movement_entries where material_id = ?", m);
            throw new AssertionError("ledger delete accepted");
        } catch (DataAccessException expected) {
            assertThat(expected.getMessage()).contains("append-only");
        }
        try {
            jdbc.update("insert into inventory_balances (material_id, location_id, quantity) values (?, ?, 1)", m, binA);
            throw new AssertionError("duplicate balance accepted");
        } catch (DataAccessException expected) {
            assertThat(expected.getMessage()).contains("ux_balance_key");
        }
    }

    @Test
    void issueValidationsAndErrorCodes() {
        long m = material("KG");
        opening(m, binA, "10.500");
        ApiClient op = new ApiClient(port).as("t_oper");
        ApiClient.Res scale = op.command("/api/stock-issues", issueBody(m, binA, "1.2345"));
        assertThat(scale.status()).isEqualTo(400);
        assertThat(scale.code()).isEqualTo("QUANTITY_SCALE_INVALID");
        ApiClient.Res zero = op.command("/api/stock-issues", issueBody(m, binA, "0"));
        assertThat(zero.status()).isEqualTo(400);
        ApiClient.Res tooMuch = op.command("/api/stock-issues", issueBody(m, binA, "10.501"));
        assertThat(tooMuch.status()).isEqualTo(409);
        assertThat(tooMuch.code()).isEqualTo("INSUFFICIENT_STOCK");
        ApiClient.Res noKey = op.post("/api/stock-issues", issueBody(m, binA, "1"));
        assertThat(noKey.status()).isEqualTo(400);
        ApiClient.Res noRef = op.command("/api/stock-issues", Map.of("reasonCode", "PRODUCTION",
                "lines", List.of(Map.of("materialId", m, "locationId", binA, "quantity", 1))));
        assertThat(noRef.status()).isEqualTo(400);
        assertThat(noRef.body().get("fieldErrors").get(0).get("field").asString()).isEqualTo("reference");
        ApiClient.Res dup = op.command("/api/stock-issues", Map.of("reasonCode", "PRODUCTION", "reference", "X",
                "lines", List.of(Map.of("materialId", m, "locationId", binA, "quantity", 1),
                        Map.of("materialId", m, "locationId", binA, "quantity", 2))));
        assertThat(dup.code()).isEqualTo("DUPLICATE_LINE");
        ApiClient.Res ok = op.command("/api/stock-issues", issueBody(m, binA, "10.5"));
        assertThat(ok.status()).isEqualTo(201);
        assertThat(ok.body().get("documentNumber").asString()).matches("ISS-\\d{4}-\\d{6}");
        assertThat(data.balance(m, binA)).isEqualByComparingTo("0");
        assertThat(data.mismatches()).isZero();
    }

    @Test
    void replayAfterCommitHasNoSecondEffectAndPayloadMismatchConflicts() {
        long m = material("PC");
        opening(m, binA, "100");
        ApiClient op = new ApiClient(port).as("t_oper");
        String key = "replay-" + System.nanoTime();
        ApiClient.Res first = op.command("/api/stock-issues", issueBody(m, binA, "10"), key);
        ApiClient.Res second = op.command("/api/stock-issues", issueBody(m, binA, "10"), key);
        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(data.balance(m, binA)).isEqualByComparingTo("90");
        ApiClient.Res conflict = op.command("/api/stock-issues", issueBody(m, binA, "11"), key);
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.code()).isEqualTo("IDEMPOTENCY_PAYLOAD_CONFLICT");

        String key2 = "fail-then-ok-" + System.nanoTime();
        assertThat(op.command("/api/stock-issues", issueBody(m, binA, "1000"), key2).code()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(op.command("/api/stock-issues", issueBody(m, binA, "1000"), key2).code()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(data.balance(m, binA)).isEqualByComparingTo("90");
        Long audits = jdbc.queryForObject("select count(*) from audit_logs where action = 'ISSUE_POST' and entity_id = ?",
                Long.class, Long.toString(first.id()));
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void concurrentDoubleSubmitSameKeyCreatesOneDocument() throws Exception {
        long m = material("PC");
        opening(m, binA, "100");
        ApiClient op = new ApiClient(port).as("t_oper");
        String key = "double-" + System.nanoTime();
        List<ApiClient.Res> rs = race(8, i -> op.command("/api/stock-issues", issueBody(m, binA, "7"), key));
        assertThat(rs).allSatisfy(r -> assertThat(r.status()).isEqualTo(201));
        assertThat(rs.stream().map(ApiClient.Res::id).distinct()).hasSize(1);
        assertThat(data.balance(m, binA)).isEqualByComparingTo("93");
    }

    @Test
    void concurrentIssues80And50FromBalance100() throws Exception {
        for (int round = 0; round < 5; round++) {
            long m = material("PC");
            opening(m, binA, "100");
            ApiClient a = new ApiClient(port).as("t_oper");
            ApiClient b = new ApiClient(port).as("t_oper2");
            List<ApiClient.Res> rs = race(2, i -> (i == 0 ? a : b).command("/api/stock-issues",
                    issueBody(m, binA, i == 0 ? "80" : "50")));
            long ok = rs.stream().filter(r -> r.status() == 201).count();
            long insufficient = rs.stream().filter(r -> "INSUFFICIENT_STOCK".equals(r.code())).count();
            assertThat(ok).as("round %d: %s", round, rs).isEqualTo(1);
            assertThat(insufficient).isEqualTo(1);
            BigDecimal bal = data.balance(m, binA);
            assertThat(bal).isIn(new BigDecimal("20.000"), new BigDecimal("50.000"));
            assertThat(bal).isEqualByComparingTo(data.ledger(m, binA));
        }
    }

    @Test
    void manyConcurrentIssuesNeverOversell() throws Exception {
        long m = material("PC");
        opening(m, binA, "100");
        List<ApiClient> clients = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            clients.add(new ApiClient(port).as(i % 2 == 0 ? "t_oper" : "t_oper2"));
        }
        List<ApiClient.Res> rs = race(12, i -> clients.get(i).command("/api/stock-issues", issueBody(m, binA, "15")));
        long ok = rs.stream().filter(r -> r.status() == 201).count();
        assertThat(ok).isEqualTo(6);
        assertThat(data.balance(m, binA)).isEqualByComparingTo("10");
        assertThat(data.mismatches()).isZero();
    }

    @Test
    void transferConservesQuantityAndRollsBackAtomically() {
        long m1 = material("PC");
        long m2 = material("PC");
        opening(m1, binA, "50");
        opening(m2, binA, "5");
        ApiClient op = new ApiClient(port).as("t_oper");

        ApiClient.Res fail = op.command("/api/stock-transfers", Map.of("lines", List.of(
                Map.of("materialId", m1, "fromLocationId", binA, "toLocationId", binB, "quantity", 20),
                Map.of("materialId", m2, "fromLocationId", binA, "toLocationId", binB, "quantity", 6))));
        assertThat(fail.code()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(data.balance(m1, binA)).isEqualByComparingTo("50");
        assertThat(data.balance(m1, binB)).isEqualByComparingTo("0");
        Long rows = jdbc.queryForObject("select count(*) from inventory_balances where material_id = ? and location_id = ?",
                Long.class, m1, binB);
        assertThat(rows).as("rolled-back create-if-absent must not leave a balance row").isZero();
        ApiClient.Res same = op.command("/api/stock-transfers", Map.of("lines", List.of(
                Map.of("materialId", m1, "fromLocationId", binA, "toLocationId", binA, "quantity", 1))));
        assertThat(same.status()).isEqualTo(400);
        ApiClient.Res ok = op.command("/api/stock-transfers", Map.of("reference", "T1", "lines", List.of(
                Map.of("materialId", m1, "fromLocationId", binA, "toLocationId", binB, "quantity", 20))));
        assertThat(ok.status()).isEqualTo(201);
        assertThat(data.balance(m1, binA)).isEqualByComparingTo("30");
        assertThat(data.balance(m1, binB)).isEqualByComparingTo("20");
        BigDecimal net = jdbc.queryForObject("select sum(e.quantity_delta) from stock_movement_entries e join stock_movements"
                + " mv on mv.id = e.movement_id where mv.document_type = 'STOCK_TRANSFER' and mv.document_id = ?",
                BigDecimal.class, ok.id());
        assertThat(net).isEqualByComparingTo("0");
        assertThat(data.mismatches()).isZero();
    }

    @Test
    void concurrentOppositeTransfersDoNotDeadlock() throws Exception {
        long m1 = material("PC");
        long m2 = material("PC");
        opening(m1, binA, "100");
        opening(m2, binB, "100");
        opening(m1, binB, "100");
        opening(m2, binA, "100");
        List<ApiClient> cs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            cs.add(new ApiClient(port).as(i % 2 == 0 ? "t_oper" : "t_oper2"));
        }
        List<ApiClient.Res> rs = race(10, i -> cs.get(i).command("/api/stock-transfers", Map.of("lines", i % 2 == 0
                ? List.of(Map.of("materialId", m1, "fromLocationId", binA, "toLocationId", binB, "quantity", 3),
                        Map.of("materialId", m2, "fromLocationId", binB, "toLocationId", binA, "quantity", 3))
                : List.of(Map.of("materialId", m2, "fromLocationId", binA, "toLocationId", binB, "quantity", 2),
                        Map.of("materialId", m1, "fromLocationId", binB, "toLocationId", binA, "quantity", 2)))));
        assertThat(rs).allSatisfy(r -> assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(201));
        BigDecimal m1Total = data.balance(m1, binA).add(data.balance(m1, binB));
        assertThat(m1Total).isEqualByComparingTo("200");
        assertThat(data.mismatches()).isZero();
    }

    @Test
    void concurrentFirstInboundToSameNewBalanceRow() throws Exception {
        long m = material("PC");
        opening(m, binA, "100");
        List<ApiClient> cs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            cs.add(new ApiClient(port).as(i % 2 == 0 ? "t_oper" : "t_oper2"));
        }

        List<ApiClient.Res> rs = race(8, i -> cs.get(i).command("/api/stock-transfers", Map.of("lines", List.of(
                Map.of("materialId", m, "fromLocationId", binA, "toLocationId", binC, "quantity", 5)))));
        assertThat(rs).allSatisfy(r -> assertThat(r.status()).isEqualTo(201));
        Long rows = jdbc.queryForObject("select count(*) from inventory_balances where material_id = ? and location_id = ?",
                Long.class, m, binC);
        assertThat(rows).isEqualTo(1);
        assertThat(data.balance(m, binC)).isEqualByComparingTo("40");
        assertThat(data.mismatches()).isZero();
    }

    @Test
    void documentNumbersAreUniqueUnderConcurrency() throws Exception {
        long m = material("PC");
        opening(m, binA, "1000");
        List<ApiClient> cs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            cs.add(new ApiClient(port).as(i % 2 == 0 ? "t_oper" : "t_oper2"));
        }
        List<ApiClient.Res> rs = race(10, i -> cs.get(i).command("/api/stock-issues", issueBody(m, binA, "1")));
        assertThat(rs.stream().map(r -> r.body().get("documentNumber").asString()).distinct()).hasSize(10);
    }

    public static List<ApiClient.Res> race(int n, java.util.function.IntFunction<ApiClient.Res> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CyclicBarrier barrier = new CyclicBarrier(n);
        try {
            List<Future<ApiClient.Res>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int idx = i;
                fs.add(pool.submit((Callable<ApiClient.Res>) () -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return task.apply(idx);
                }));
            }
            List<ApiClient.Res> out = new ArrayList<>();
            for (Future<ApiClient.Res> f : fs) {
                out.add(f.get(60, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
