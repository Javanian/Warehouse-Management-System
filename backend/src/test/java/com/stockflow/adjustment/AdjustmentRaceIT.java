package com.stockflow.adjustment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stockflow.common.ApiException;
import com.stockflow.identity.Actor;
import com.stockflow.identity.Role;
import com.stockflow.inventory.InventoryPostingService;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.TestData;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class AdjustmentRaceIT extends IntegrationTest {

    @Autowired
    InventoryPostingService posting;
    @Autowired
    AdjustmentService adjustments;
    @Autowired
    PlatformTransactionManager tm;

    TestData data;
    TransactionTemplate tx;
    ExecutorService pool;
    Actor admin;
    Actor requester;
    Actor approver;
    long bin;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        data.standardUsers();
        tx = new TransactionTemplate(tm);
        pool = Executors.newFixedThreadPool(2);
        admin = new Actor(data.user("t_admin", "ADMIN"), "t_admin", Role.ADMIN);
        requester = new Actor(data.user("t_oper", "OPERATOR"), "t_oper", Role.OPERATOR);
        approver = new Actor(data.user("t_super", "SUPERVISOR"), "t_super", Role.SUPERVISOR);
        String s = Long.toString(System.nanoTime(), 36).toUpperCase();
        bin = data.bin(data.warehouse("R" + s), "B" + s);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    long material() {
        return data.material("RM" + Long.toString(System.nanoTime(), 36).toUpperCase(), "PC");
    }

    void inbound(long m, String qty) {
        tx.executeWithoutResult(s -> posting.post(admin, opening(m, qty)));
    }

    InventoryPostingService.Request opening(long m, String qty) {
        return new InventoryPostingService.Request("OPENING_BALANCE", "OPENING_BALANCE", null, "OPN", null, "race",
                List.of(new InventoryPostingService.Line(m, bin, new BigDecimal(qty))));
    }

    AdjustmentService.AdjustmentView request(long m, String physical) {
        AdjustmentService.CountSnapshot snap = adjustments.snapshot(m, bin);
        return tx.execute(s -> adjustments.request(requester, new AdjustmentService.RequestInput(m, bin,
                new BigDecimal(physical), snap.quantity(), snap.version(), "cycle count")));
    }

    long adjustmentMovements(long adjustmentId) {
        return jdbc.queryForObject("select count(*) from stock_movements where document_type = 'STOCK_ADJUSTMENT'"
                + " and document_id = ?", Long.class, adjustmentId);
    }

    String status(long adjustmentId) {
        return jdbc.queryForObject("select status from stock_adjustments where id = ?", String.class, adjustmentId);
    }

    boolean waitForBlockedSession(String table) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer n = jdbc.queryForObject("select count(*) from pg_stat_activity where datname = current_database()"
                    + " and wait_event_type = 'Lock' and query like ?", Integer.class, "%" + table + "%");
            if (n != null && n > 0) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }

    Object approveWhileInboundHeld(long m, long adjustmentId, String inboundQty, String blockedTable)
            throws Exception {
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> inbound = pool.submit(() -> tx.executeWithoutResult(s -> {
            posting.post(admin, opening(m, inboundQty));
            written.countDown();
            try {
                assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }));
        assertThat(written.await(10, TimeUnit.SECONDS)).as("inbound wrote its balance row").isTrue();
        Future<Object> approval = pool.submit(() -> {
            try {
                return tx.execute(s -> adjustments.approve(approver, adjustmentId, new AdjustmentService.DecisionInput("ok")));
            } catch (ApiException e) {
                return e;
            }
        });
        boolean blocked = waitForBlockedSession(blockedTable);
        release.countDown();
        inbound.get(30, TimeUnit.SECONDS);
        Object result = approval.get(30, TimeUnit.SECONDS);
        assertThat(blocked).as("approval was blocked by the uncommitted inbound").isTrue();
        return result;
    }

    @Test
    void firstInboundRaceRejectsStaleZeroCount() throws Exception {
        long m = material();
        AdjustmentService.AdjustmentView req = request(m, "10");
        assertThat(req.observedQuantity()).isEqualByComparingTo("0");
        assertThat(req.observedVersion()).isZero();

        Object result = approveWhileInboundHeld(m, req.id(), "5", "inventory_balances");

        assertThat(result).isInstanceOf(ApiException.class);
        assertThat(((ApiException) result).getCode()).isEqualTo("STALE_COUNT");
        assertThat(data.balance(m, bin)).isEqualByComparingTo("5");
        assertThat(adjustmentMovements(req.id())).isZero();
        assertThat(status(req.id())).isEqualTo("PENDING");
        assertThat(data.ledger(m, bin)).isEqualByComparingTo("5");
        assertThat(data.mismatches()).isZero();
    }

    @Test
    void existingBalanceRaceRejectsStaleCount() throws Exception {
        long m = material();
        inbound(m, "20");
        AdjustmentService.AdjustmentView req = request(m, "18");

        Object result = approveWhileInboundHeld(m, req.id(), "5", "inventory_balances");

        assertThat(result).isInstanceOf(ApiException.class);
        assertThat(((ApiException) result).getCode()).isEqualTo("STALE_COUNT");
        assertThat(data.balance(m, bin)).isEqualByComparingTo("25");
        assertThat(adjustmentMovements(req.id())).isZero();
        assertThat(data.mismatches()).isZero();
    }

    @Test
    void zeroBinCountWithoutContentionIsApproved() {
        long m = material();
        AdjustmentService.AdjustmentView req = request(m, "10");
        AdjustmentService.AdjustmentView done = tx.execute(s -> adjustments.approve(approver, req.id(),
                new AdjustmentService.DecisionInput(null)));
        assertThat(done.status()).isEqualTo("APPROVED");
        assertThat(data.balance(m, bin)).isEqualByComparingTo("10");
        assertThat(adjustmentMovements(req.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select version from inventory_balances where material_id = ? and location_id = ?",
                Long.class, m, bin)).isEqualTo(1);
        assertThat(data.mismatches()).isZero();
    }

    @Test
    void approvalRollbackLeavesNoBalanceRowOrMovement() {
        long m = material();
        AdjustmentService.AdjustmentView req = request(m, "7");
        tx.executeWithoutResult(s -> {
            adjustments.approve(approver, req.id(), new AdjustmentService.DecisionInput(null));
            s.setRollbackOnly();
        });
        assertThat(status(req.id())).isEqualTo("PENDING");
        assertThat(adjustmentMovements(req.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from inventory_balances where material_id = ? and location_id = ?",
                Long.class, m, bin)).isZero();
        assertThat(data.mismatches()).isZero();
    }

    @Test
    void concurrentApprovalsOfTwoZeroBinRequestsPostExactlyOne() throws Exception {
        long m = material();
        AdjustmentService.AdjustmentView a = request(m, "10");
        AdjustmentService.AdjustmentView b = request(m, "4");
        Actor approver2 = new Actor(data.user("t_super2", "SUPERVISOR"), "t_super2", Role.SUPERVISOR);
        CountDownLatch start = new CountDownLatch(1);
        Future<Object> fa = pool.submit(() -> decide(start, approver, a.id()));
        Future<Object> fb = pool.submit(() -> decide(start, approver2, b.id()));
        start.countDown();
        Object ra = fa.get(30, TimeUnit.SECONDS);
        Object rb = fb.get(30, TimeUnit.SECONDS);
        long approved = List.of(ra, rb).stream().filter(r -> r instanceof AdjustmentService.AdjustmentView).count();
        long stale = List.of(ra, rb).stream()
                .filter(r -> r instanceof ApiException e && "STALE_COUNT".equals(e.getCode())).count();
        assertThat(approved).isEqualTo(1);
        assertThat(stale).isEqualTo(1);
        BigDecimal expected = ra instanceof AdjustmentService.AdjustmentView ? new BigDecimal("10") : new BigDecimal("4");
        assertThat(data.balance(m, bin)).isEqualByComparingTo(expected);
        assertThat(data.mismatches()).isZero();
    }

    Object decide(CountDownLatch start, Actor who, long id) throws InterruptedException {
        start.await(10, TimeUnit.SECONDS);
        try {
            return tx.execute(s -> adjustments.approve(who, id, new AdjustmentService.DecisionInput(null)));
        } catch (ApiException e) {
            return e;
        }
    }

    @Test
    void staleRequestIsRejectedAtCreation() {
        long m = material();
        inbound(m, "3");
        assertThatThrownBy(() -> tx.execute(s -> adjustments.request(requester, new AdjustmentService.RequestInput(m, bin,
                new BigDecimal("10"), BigDecimal.ZERO, 0L, "old screen"))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("STALE_COUNT"));
    }
}
