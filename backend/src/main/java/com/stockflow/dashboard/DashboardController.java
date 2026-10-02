package com.stockflow.dashboard;

import com.stockflow.common.TimeSource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@Tag(name = "Dashboard")
public class DashboardController {

    public record Counts(long activeMaterials, long lowStockMaterials, long receiptsToday, long issuesToday,
            long transfersToday, long pendingAdjustments, long openPurchaseOrders) {}

    public record LowStock(long materialId, String materialCode, String materialName, String uomCode, int uomScale,
            BigDecimal onHand, BigDecimal minimumStock, BigDecimal shortage) {}

    public record Activity(LocalDate businessDate, long receipts, long issues, long transfers) {}

    public record RecentMovement(long movementId, String movementNumber, String movementType, String documentType,
            Long documentId, String documentNumber, Instant postedAt, String postedBy, int entryCount) {}

    public record Dashboard(LocalDate businessDate, String timezone, Instant generatedAt, Counts counts,
            List<LowStock> lowStock, List<Activity> activity, List<RecentMovement> recent) {}

    private final JdbcClient jdbc;
    private final TimeSource time;

    public DashboardController(JdbcClient jdbc, TimeSource time) {
        this.jdbc = jdbc;
        this.time = time;
    }

    @Operation(summary = "Dashboard counts, low-stock top 10, 7-day document activity, latest movements")
    @GetMapping
    public Dashboard dashboard() {
        LocalDate today = time.today();
        Counts c = jdbc.sql("select"
                        + " (select count(*) from materials where active) as active_materials,"
                        + " (select count(*) from materials m where m.active and m.minimum_stock > 0 and"
                        + "   coalesce((select sum(quantity) from inventory_balances b where b.material_id = m.id), 0) < m.minimum_stock)"
                        + "   as low_stock_materials,"
                        + " (select count(*) from goods_receipts where status = 'POSTED' and business_date = :d) as receipts_today,"
                        + " (select count(*) from stock_issues where status = 'POSTED' and business_date = :d) as issues_today,"
                        + " (select count(*) from stock_transfers where status = 'POSTED' and business_date = :d) as transfers_today,"
                        + " (select count(*) from stock_adjustments where status = 'PENDING') as pending_adjustments,"
                        + " (select count(*) from purchase_orders where status in ('OPEN','PARTIALLY_RECEIVED')) as open_purchase_orders")
                .param("d", today).query(Counts.class).single();
        List<LowStock> low = jdbc.sql("select m.id as material_id, m.code as material_code, m.name as material_name,"
                        + " m.uom_code, u.scale as uom_scale, coalesce(t.total, 0) as on_hand, m.minimum_stock,"
                        + " m.minimum_stock - coalesce(t.total, 0) as shortage from materials m join uoms u on u.code = m.uom_code"
                        + " left join (select material_id, sum(quantity) as total from inventory_balances group by material_id) t"
                        + " on t.material_id = m.id where m.active and m.minimum_stock > 0 and coalesce(t.total, 0) < m.minimum_stock"
                        + " order by (m.minimum_stock - coalesce(t.total, 0)) / m.minimum_stock desc, m.code limit 10")
                .query(LowStock.class).list();
        List<Activity> act = jdbc.sql("select d::date as business_date,"
                        + " (select count(*) from goods_receipts g where g.status = 'POSTED' and g.business_date = d::date) as receipts,"
                        + " (select count(*) from stock_issues i where i.status = 'POSTED' and i.business_date = d::date) as issues,"
                        + " (select count(*) from stock_transfers t where t.status = 'POSTED' and t.business_date = d::date) as transfers"
                        + " from generate_series(cast(:d as date) - 6, cast(:d as date), interval '1 day') d order by 1")
                .param("d", today).query(Activity.class).list();
        List<RecentMovement> recent = jdbc.sql("select mv.id as movement_id, mv.movement_number, mv.movement_type,"
                        + " mv.document_type, mv.document_id, mv.document_number, mv.posted_at, u.username as posted_by,"
                        + " (select count(*) from stock_movement_entries e where e.movement_id = mv.id) as entry_count"
                        + " from stock_movements mv join users u on u.id = mv.posted_by order by mv.posted_at desc, mv.id desc limit 8")
                .query(RecentMovement.class).list();
        return new Dashboard(today, time.zone().getId(), Instant.now(), c, low, act, recent);
    }
}
