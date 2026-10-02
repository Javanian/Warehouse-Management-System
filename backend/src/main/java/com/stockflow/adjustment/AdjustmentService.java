package com.stockflow.adjustment;

import com.stockflow.audit.AuditService;
import com.stockflow.common.ApiException;
import com.stockflow.common.DocumentNumberService;
import com.stockflow.common.PageQuery;
import com.stockflow.common.PageResponse;
import com.stockflow.common.Quantities;
import com.stockflow.common.SqlFilter;
import com.stockflow.common.TimeSource;
import com.stockflow.identity.Actor;
import com.stockflow.inventory.InventoryPostingService;
import com.stockflow.inventory.MasterLookup;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdjustmentService {

    public record RequestInput(Long materialId, Long locationId, BigDecimal physicalQuantity,
            BigDecimal observedQuantity, Long observedVersion, String reason) {}

    public record DecisionInput(String note) {}

    public record AdjustmentView(long id, String documentNumber, String status, long materialId, String materialCode,
            String materialName, String uomCode, int uomScale, long locationId, String location,
            BigDecimal physicalQuantity, BigDecimal observedQuantity, long observedVersion, BigDecimal delta,
            BigDecimal currentQuantity, long currentVersion, boolean stale, String reason, long requestedById,
            String requestedBy, Instant requestedAt, String decidedBy, Instant decidedAt, String decisionNote,
            String movementNumber, long version) {}

    public record CountSnapshot(BigDecimal quantity, long version) {}

    private static final Map<String, String> SORT = Map.of("requestedAt", "a.requested_at", "documentNumber",
            "a.document_number", "status", "a.status");

    private final JdbcClient jdbc;
    private final MasterLookup master;
    private final InventoryPostingService posting;
    private final DocumentNumberService numbers;
    private final AuditService audit;
    private final TimeSource time;

    public AdjustmentService(JdbcClient jdbc, MasterLookup master, InventoryPostingService posting,
            DocumentNumberService numbers, AuditService audit, TimeSource time) {
        this.jdbc = jdbc;
        this.master = master;
        this.posting = posting;
        this.numbers = numbers;
        this.audit = audit;
        this.time = time;
    }

    public CountSnapshot snapshot(long materialId, long locationId) {
        return jdbc.sql("select quantity, version from inventory_balances where material_id = :m and location_id = :l")
                .param("m", materialId).param("l", locationId).query(CountSnapshot.class).optional()
                .orElse(new CountSnapshot(BigDecimal.ZERO, 0));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AdjustmentView request(Actor a, RequestInput in) {
        if (in.materialId() == null) {
            throw ApiException.field("materialId", "material is required");
        }
        if (in.locationId() == null) {
            throw ApiException.field("locationId", "bin is required");
        }
        if (in.reason() == null || in.reason().isBlank() || in.reason().length() > 500) {
            throw ApiException.field("reason", "reason is required (max 500 characters)");
        }
        if (in.observedQuantity() == null || in.observedVersion() == null) {
            throw ApiException.field("observedQuantity", "observed system quantity and version are required");
        }
        MasterLookup.MaterialInfo m = master.material(in.materialId(), "materialId");
        master.requireActive(m);
        MasterLookup.LocationInfo loc = master.location(in.locationId(), "locationId");
        if (in.physicalQuantity() == null || in.physicalQuantity().signum() < 0) {
            throw ApiException.field("physicalQuantity", "physical quantity must be >= 0");
        }
        Quantities.requireScale(in.physicalQuantity(), m.scale(), m.uomCode(), "physicalQuantity");
        CountSnapshot now = snapshot(m.id(), loc.id());
        if (now.version() != in.observedVersion() || now.quantity().compareTo(in.observedQuantity()) != 0) {
            throw ApiException.conflict("STALE_COUNT", "System quantity changed since the count screen was loaded"
                    + " (now " + Quantities.fmt(now.quantity()) + " " + m.uomCode() + "). Reload and recount.",
                    Map.of("currentQuantity", Quantities.fmt(now.quantity()), "currentVersion", now.version()));
        }
        if (in.physicalQuantity().compareTo(now.quantity()) == 0) {
            throw ApiException.badRequest("ZERO_DELTA", "Physical quantity equals system quantity; no adjustment needed");
        }
        if (in.physicalQuantity().compareTo(now.quantity()) > 0) {
            master.requireInbound(loc, "locationId");
        }
        String number = numbers.next("ADJ", time.today());
        long id = jdbc.sql("insert into stock_adjustments (document_number, status, material_id, location_id,"
                        + " physical_quantity, observed_quantity, observed_version, reason, requested_by, requested_at)"
                        + " values (:n, 'PENDING', :m, :l, :pq, :oq, :ov, :r, :by, :at) returning id")
                .param("n", number).param("m", m.id()).param("l", loc.id()).param("pq", in.physicalQuantity())
                .param("oq", now.quantity()).param("ov", now.version()).param("r", in.reason().trim())
                .param("by", a.id()).param("at", java.sql.Timestamp.from(time.now())).query(Long.class).single();
        audit.record(a, "ADJUSTMENT_REQUEST", "STOCK_ADJUSTMENT", id, Map.of("documentNumber", number,
                "physical", Quantities.fmt(in.physicalQuantity()), "observed", Quantities.fmt(now.quantity())));
        return get(id);
    }

    private record Locked(long id, String status, long materialId, long locationId, BigDecimal physicalQuantity,
            BigDecimal observedQuantity, long observedVersion, long requestedBy, String documentNumber) {}

    private Locked lock(long id) {
        return jdbc.sql("select id, status, material_id, location_id, physical_quantity, observed_quantity,"
                        + " observed_version, requested_by, document_number from stock_adjustments where id = :id for update")
                .param("id", id).query(Locked.class).optional().orElseThrow(() -> ApiException.notFound("Adjustment"));
    }

    private void guard(Actor a, Locked r) {
        if (!r.status().equals("PENDING")) {
            throw ApiException.conflict("INVALID_STATE", r.documentNumber() + " is already " + r.status());
        }
        if (r.requestedBy() == a.id()) {
            throw ApiException.forbidden("SELF_APPROVAL_FORBIDDEN", "You cannot approve or reject your own adjustment request");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AdjustmentView approve(Actor a, long id, DecisionInput in) {
        Locked r = lock(id);
        guard(a, r);
        String note = in == null || in.note() == null || in.note().isBlank() ? null : in.note().trim();
        if (note != null && note.length() > 500) {
            throw ApiException.field("note", "max 500 characters");
        }

        CountSnapshot cur = jdbc.sql("select quantity, version from inventory_balances where material_id = :m"
                        + " and location_id = :l for update")
                .param("m", r.materialId()).param("l", r.locationId()).query(CountSnapshot.class).optional()
                .orElse(new CountSnapshot(BigDecimal.ZERO, 0));
        if (cur.version() != r.observedVersion()) {
            throw ApiException.conflict("STALE_COUNT", "Stock at this bin changed after the count (observed "
                    + Quantities.fmt(r.observedQuantity()) + ", now " + Quantities.fmt(cur.quantity())
                    + "). Reject this request and recount.",
                    Map.of("observedQuantity", Quantities.fmt(r.observedQuantity()),
                            "currentQuantity", Quantities.fmt(cur.quantity())));
        }
        BigDecimal delta = r.physicalQuantity().subtract(r.observedQuantity());
        if (delta.signum() > 0) {
            master.requireInbound(master.location(r.locationId(), "locationId"), "locationId");
        }
        var p = posting.post(a, new InventoryPostingService.Request(delta.signum() > 0 ? "ADJUSTMENT_IN" : "ADJUSTMENT_OUT",
                "STOCK_ADJUSTMENT", id, null, r.documentNumber(), note,
                List.of(new InventoryPostingService.Line(r.materialId(), r.locationId(), delta))));
        jdbc.sql("update stock_adjustments set status = 'APPROVED', decided_by = :by, decided_at = :at, decision_note = :n,"
                        + " movement_id = :mv, version = version + 1 where id = :id")
                .param("by", a.id()).param("at", java.sql.Timestamp.from(p.postedAt())).param("n", note)
                .param("mv", p.movementId()).param("id", id).update();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("documentNumber", r.documentNumber());
        d.put("delta", Quantities.fmt(delta));
        d.put("movementNumber", p.movementNumber());
        audit.record(a, "ADJUSTMENT_APPROVE", "STOCK_ADJUSTMENT", id, d);
        return get(id);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AdjustmentView reject(Actor a, long id, DecisionInput in) {
        if (in == null || in.note() == null || in.note().isBlank() || in.note().length() > 500) {
            throw ApiException.field("note", "rejection note is required (max 500 characters)");
        }
        Locked r = lock(id);
        guard(a, r);
        jdbc.sql("update stock_adjustments set status = 'REJECTED', decided_by = :by, decided_at = :at, decision_note = :n,"
                        + " version = version + 1 where id = :id")
                .param("by", a.id()).param("at", java.sql.Timestamp.from(time.now())).param("n", in.note().trim())
                .param("id", id).update();
        audit.record(a, "ADJUSTMENT_REJECT", "STOCK_ADJUSTMENT", id,
                Map.of("documentNumber", r.documentNumber(), "note", in.note().trim()));
        return get(id);
    }

    private static final String SELECT = "select a.id, a.document_number, a.status, a.material_id, m.code as material_code,"
            + " m.name as material_name, m.uom_code, u.scale as uom_scale, a.location_id, w.code || '/' || l.code as location,"
            + " a.physical_quantity, a.observed_quantity, a.observed_version,"
            + " a.physical_quantity - a.observed_quantity as delta,"
            + " coalesce(b.quantity, 0) as current_quantity, coalesce(b.version, 0) as current_version,"
            + " (a.status = 'PENDING' and coalesce(b.version, 0) <> a.observed_version) as stale,"
            + " a.reason, a.requested_by as requested_by_id, ru.username as requested_by, a.requested_at,"
            + " du.username as decided_by, a.decided_at, a.decision_note, mv.movement_number, a.version"
            + " from stock_adjustments a join materials m on m.id = a.material_id join uoms u on u.code = m.uom_code"
            + " join storage_locations l on l.id = a.location_id join warehouses w on w.id = l.warehouse_id"
            + " join users ru on ru.id = a.requested_by left join users du on du.id = a.decided_by"
            + " left join stock_movements mv on mv.id = a.movement_id"
            + " left join inventory_balances b on b.material_id = a.material_id and b.location_id = a.location_id";

    public AdjustmentView get(long id) {
        return jdbc.sql(SELECT + " where a.id = :id").param("id", id).query(AdjustmentView.class).optional()
                .orElseThrow(() -> ApiException.notFound("Adjustment"));
    }

    public PageResponse<AdjustmentView> list(String q, String status, Integer page, Integer size, String sort) {
        PageQuery pq = PageQuery.of(page, size, sort, SORT, "requestedAt,desc", "a.id desc");
        SqlFilter f = new SqlFilter().search(q, "a.document_number", "m.code", "m.name").add("a.status = :st", "st", status);
        long total = jdbc.sql("select count(*) from stock_adjustments a join materials m on m.id = a.material_id"
                + f.where()).params(f.params()).query(Long.class).single();
        var rows = jdbc.sql(SELECT + f.where() + " order by " + pq.orderBy() + " limit :limit offset :offset")
                .params(f.params()).param("limit", pq.size()).param("offset", pq.offset())
                .query(AdjustmentView.class).list();
        return PageResponse.of(rows, pq, total);
    }
}
