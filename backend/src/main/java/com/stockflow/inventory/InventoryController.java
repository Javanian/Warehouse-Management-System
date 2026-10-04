package com.stockflow.inventory;

import com.stockflow.common.PageQuery;
import com.stockflow.common.PageResponse;
import com.stockflow.common.SqlFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Tag(name = "Inventory", description = "Balances per material/bin and the immutable movement ledger")
public class InventoryController {

    public record BalanceRow(long id, long materialId, String materialCode, String materialName, String uomCode,
            int uomScale, long locationId, String locationCode, long warehouseId, String warehouseCode,
            BigDecimal quantity, long version, Instant updatedAt) {}

    public record MovementRow(long movementId, String movementNumber, String movementType, String documentType,
            Long documentId, String documentNumber, String reversalOfMovementNumber, Instant postedAt,
            LocalDate businessDate, String postedBy, int lineNo, long materialId, String materialCode, String uomCode,
            int uomScale, long locationId, String location, BigDecimal quantityDelta, BigDecimal balanceAfter) {}

    public record ReconciliationRow(long materialId, String materialCode, long locationId, String location,
            BigDecimal balance, BigDecimal ledgerSum) {}

    public record LocationBalance(long locationId, String locationCode, String warehouseCode, BigDecimal quantity) {}

    public record MaterialMovementSummary(String movementNumber, String movementType, String documentNumber,
                                          Instant postedAt, LocalDate businessDate, BigDecimal quantityDelta) {}

    public record MaterialStockSummary(
            long materialId,
            String materialCode,
            String materialName,
            String uomCode,
            int uomScale,
            boolean active,
            BigDecimal minStock,
            BigDecimal totalQuantity,
            String stockStatus,
            List<LocationBalance> balances,
            List<MaterialMovementSummary> recentMovements
    ) {}

    private static final Map<String, String> BAL_SORT = Map.of("material", "upper(m.code)", "location",
            "upper(w.code), upper(l.code)", "quantity", "b.quantity", "updatedAt", "b.updated_at");
    private static final Map<String, String> MOV_SORT = Map.of("postedAt", "mv.posted_at", "movementNumber",
            "mv.movement_number");

    private final JdbcClient jdbc;

    public InventoryController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/inventory/balances")
    public PageResponse<BalanceRow> balances(@RequestParam(required = false) String q,
            @RequestParam(required = false) Long materialId, @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long locationId,
            @RequestParam(required = false, defaultValue = "false") boolean includeZero,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        PageQuery pq = PageQuery.of(page, size, sort, BAL_SORT, "material", "b.id");
        SqlFilter f = new SqlFilter().search(q, "m.code", "m.name", "l.code")
                .add("b.material_id = :mat", "mat", materialId).add("l.warehouse_id = :wh", "wh", warehouseId)
                .add("b.location_id = :loc", "loc", locationId);
        if (!includeZero) {
            f.add("b.quantity > 0");
        }
        String from = " from inventory_balances b join materials m on m.id = b.material_id"
                + " join uoms u on u.code = m.uom_code join storage_locations l on l.id = b.location_id"
                + " join warehouses w on w.id = l.warehouse_id";
        long total = jdbc.sql("select count(*)" + from + f.where()).params(f.params()).query(Long.class).single();
        var rows = jdbc.sql("select b.id, b.material_id, m.code as material_code, m.name as material_name, m.uom_code,"
                        + " u.scale as uom_scale, b.location_id, l.code as location_code, l.warehouse_id,"
                        + " w.code as warehouse_code, b.quantity, b.version, b.updated_at" + from + f.where()
                        + " order by " + pq.orderBy() + " limit :limit offset :offset")
                .params(f.params()).param("limit", pq.size()).param("offset", pq.offset()).query(BalanceRow.class).list();
        return PageResponse.of(rows, pq, total);
    }

    @Operation(summary = "Ledger entries (one row per signed entry), newest first")
    @GetMapping("/inventory/movements")
    public PageResponse<MovementRow> movements(@RequestParam(required = false) String q,
            @RequestParam(required = false) Long materialId, @RequestParam(required = false) Long locationId,
            @RequestParam(required = false) Long warehouseId, @RequestParam(required = false) String movementType,
            @RequestParam(required = false) String documentType, @RequestParam(required = false) Long documentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        PageQuery pq = PageQuery.of(page, size, sort, MOV_SORT, "postedAt,desc", "mv.id desc, e.line_no");
        SqlFilter f = new SqlFilter().search(q, "mv.movement_number", "mv.document_number", "m.code")
                .add("e.material_id = :mat", "mat", materialId).add("e.location_id = :loc", "loc", locationId)
                .add("l.warehouse_id = :wh", "wh", warehouseId).add("mv.movement_type = :mt", "mt", movementType)
                .add("mv.document_type = :dt", "dt", documentType).add("mv.document_id = :did", "did", documentId)
                .add("mv.business_date >= :from", "from", from).add("mv.business_date <= :to", "to", to);
        String fromSql = " from stock_movement_entries e join stock_movements mv on mv.id = e.movement_id"
                + " join materials m on m.id = e.material_id join uoms u on u.code = m.uom_code"
                + " join storage_locations l on l.id = e.location_id join warehouses w on w.id = l.warehouse_id"
                + " join users usr on usr.id = mv.posted_by left join stock_movements om on om.id = mv.reversal_of_movement_id";
        long total = jdbc.sql("select count(*)" + fromSql + f.where()).params(f.params()).query(Long.class).single();
        var rows = jdbc.sql("select mv.id as movement_id, mv.movement_number, mv.movement_type, mv.document_type,"
                        + " mv.document_id, mv.document_number, om.movement_number as reversal_of_movement_number,"
                        + " mv.posted_at, mv.business_date, usr.username as posted_by, e.line_no, e.material_id,"
                        + " m.code as material_code, m.uom_code, u.scale as uom_scale, e.location_id,"
                        + " w.code || '/' || l.code as location, e.quantity_delta, e.balance_after" + fromSql + f.where()
                        + " order by " + pq.orderBy() + " limit :limit offset :offset")
                .params(f.params()).param("limit", pq.size()).param("offset", pq.offset()).query(MovementRow.class).list();
        return PageResponse.of(rows, pq, total);
    }

    @Operation(summary = "Balance vs. ledger mismatches (expected: empty list)")
    @GetMapping("/inventory/reconciliation")
    public List<ReconciliationRow> reconciliation() {
        return jdbc.sql("select coalesce(b.material_id, s.material_id) as material_id, m.code as material_code,"
                        + " coalesce(b.location_id, s.location_id) as location_id, w.code || '/' || l.code as location,"
                        + " coalesce(b.quantity, 0) as balance, coalesce(s.total, 0) as ledger_sum"
                        + " from inventory_balances b full join (select material_id, location_id, sum(quantity_delta) as total"
                        + " from stock_movement_entries group by material_id, location_id) s"
                        + " on s.material_id = b.material_id and s.location_id = b.location_id"
                        + " join materials m on m.id = coalesce(b.material_id, s.material_id)"
                        + " join storage_locations l on l.id = coalesce(b.location_id, s.location_id)"
                        + " join warehouses w on w.id = l.warehouse_id"
                        + " where coalesce(b.quantity, 0) <> coalesce(s.total, 0) order by 2, 4")
                .query(ReconciliationRow.class).list();
    }

    @Operation(summary = "Deterministic stock summary and location breakdown for a material")
    @GetMapping("/inventory/stock-summary/{materialCode}")
    public MaterialStockSummary stockSummary(@PathVariable String materialCode) {
        record MatHead(long id, String code, String name, String uomCode, int uomScale, boolean active, BigDecimal minStock) {}
        MatHead mat = jdbc.sql("select m.id, m.code, m.name, m.uom_code, u.scale as uom_scale, m.active, m.minimum_stock as min_stock "
                + "from materials m join uoms u on u.code = m.uom_code where upper(m.code) = upper(:code)")
                .param("code", materialCode.trim())
                .query(MatHead.class)
                .optional()
                .orElseThrow(() -> com.stockflow.common.ApiException.notFound("Material " + materialCode));

        List<LocationBalance> balances = jdbc.sql("select b.location_id, l.code as location_code, w.code as warehouse_code, b.quantity "
                + "from inventory_balances b "
                + "join storage_locations l on l.id = b.location_id "
                + "join warehouses w on w.id = l.warehouse_id "
                + "where b.material_id = :id and b.quantity > 0 "
                + "order by w.code, l.code")
                .param("id", mat.id())
                .query(LocationBalance.class)
                .list();

        BigDecimal total = balances.stream()
                .map(LocationBalance::quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String status;
        if (total.compareTo(BigDecimal.ZERO) == 0) {
            status = "OUT_OF_STOCK";
        } else if (mat.minStock() != null && total.compareTo(mat.minStock()) < 0) {
            status = "LOW_STOCK";
        } else {
            status = "HEALTHY";
        }

        List<MaterialMovementSummary> movements = jdbc.sql("select mv.movement_number, mv.movement_type, "
                + "mv.document_number, mv.posted_at, mv.business_date, e.quantity_delta "
                + "from stock_movement_entries e join stock_movements mv on mv.id = e.movement_id "
                + "where e.material_id = :id order by mv.posted_at desc, e.id desc limit 5")
                .param("id", mat.id())
                .query(MaterialMovementSummary.class)
                .list();

        return new MaterialStockSummary(mat.id(), mat.code(), mat.name(), mat.uomCode(), mat.uomScale(),
                mat.active(), mat.minStock(), total, status, balances, movements);
    }

    @GetMapping("/inventory/materials/{materialId}/locations")
    public List<BalanceRow> materialLocations(@PathVariable long materialId) {
        return jdbc.sql("select b.id, b.material_id, m.code as material_code, m.name as material_name, m.uom_code,"
                        + " u.scale as uom_scale, b.location_id, l.code as location_code, l.warehouse_id,"
                        + " w.code as warehouse_code, b.quantity, b.version, b.updated_at from inventory_balances b"
                        + " join materials m on m.id = b.material_id join uoms u on u.code = m.uom_code"
                        + " join storage_locations l on l.id = b.location_id join warehouses w on w.id = l.warehouse_id"
                        + " where b.material_id = :id and b.quantity > 0 order by w.code, l.code")
                .param("id", materialId).query(BalanceRow.class).list();
    }
}
