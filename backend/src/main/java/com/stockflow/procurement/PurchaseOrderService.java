package com.stockflow.procurement;

import com.stockflow.audit.AuditService;
import com.stockflow.common.ApiException;
import com.stockflow.common.DocumentNumberService;
import com.stockflow.common.PageQuery;
import com.stockflow.common.PageResponse;
import com.stockflow.common.SqlFilter;
import com.stockflow.common.TimeSource;
import com.stockflow.identity.Actor;
import com.stockflow.inventory.MasterLookup;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PurchaseOrderService {

    public static final Set<String> RECEIVABLE = Set.of("OPEN", "PARTIALLY_RECEIVED");
    private static final Map<String, String> SORT = Map.of("poNumber", "upper(p.po_number)", "poDate", "p.po_date",
            "expectedDate", "p.expected_date", "status", "p.status", "supplier", "upper(s.name)",
            "createdAt", "p.created_at");

    public record ItemInput(Long materialId, BigDecimal orderedQuantity) {}

    public record PoInput(Long supplierId, LocalDate poDate, LocalDate expectedDate, String notes,
            List<ItemInput> items, Long version) {}

    public record PoItem(long id, int lineNo, long materialId, String materialCode, String materialName,
            String uomCode, int uomScale, BigDecimal orderedQuantity, BigDecimal receivedQuantity,
            BigDecimal outstandingQuantity) {}

    public record PoReceipt(long id, String documentNumber, String status, Instant postedAt, String postedBy) {}

    public record PoLineStatus(
            int lineNo,
            long itemId,
            long materialId,
            String materialCode,
            String materialName,
            String uomCode,
            BigDecimal orderedQuantity,
            BigDecimal receivedQuantity,
            BigDecimal outstandingQuantity,
            String lineStatus,
            boolean canReceive
    ) {}

    public record PoStatusReport(
            long id,
            String poNumber,
            String status,
            boolean receivable,
            long supplierId,
            String supplierCode,
            String supplierName,
            LocalDate poDate,
            LocalDate expectedDate,
            int itemCount,
            BigDecimal totalOrdered,
            BigDecimal totalReceived,
            BigDecimal totalOutstanding,
            List<PoLineStatus> lines
    ) {}

    public record PoSummary(long id, String poNumber, long supplierId, String supplierCode, String supplierName,
            LocalDate poDate, LocalDate expectedDate, String status, int itemCount, BigDecimal outstandingLines,
            boolean demo, Instant createdAt, long version) {}

    public record PoDetail(long id, String poNumber, long supplierId, String supplierCode, String supplierName,
            LocalDate poDate, LocalDate expectedDate, String status, String notes, String cancelReason,
            String createdBy, Instant createdAt, Instant openedAt, Instant cancelledAt, boolean demo, long version,
            List<PoItem> items, List<PoReceipt> receipts) {}

    private record Header(long id, String status, long version) {}

    private final JdbcClient jdbc;
    private final DocumentNumberService numbers;
    private final MasterLookup master;
    private final AuditService audit;
    private final TimeSource time;

    public PurchaseOrderService(JdbcClient jdbc, DocumentNumberService numbers, MasterLookup master,
            AuditService audit, TimeSource time) {
        this.jdbc = jdbc;
        this.numbers = numbers;
        this.master = master;
        this.audit = audit;
        this.time = time;
    }

    public PageResponse<PoSummary> list(String q, String status, Long supplierId, Boolean receivable, Integer page,
            Integer size, String sort) {
        PageQuery pq = PageQuery.of(page, size, sort, SORT, "poNumber,desc", "p.id desc");
        SqlFilter f = new SqlFilter().search(q, "p.po_number", "s.name", "s.code")
                .add("p.status = :st", "st", status)
                .add("p.supplier_id = :sup", "sup", supplierId);
        if (Boolean.TRUE.equals(receivable)) {
            f.add("p.status in ('OPEN','PARTIALLY_RECEIVED')");
        }
        String from = " from purchase_orders p join suppliers s on s.id = p.supplier_id";
        long total = jdbc.sql("select count(*)" + from + f.where()).params(f.params()).query(Long.class).single();
        var rows = jdbc.sql("select p.id, p.po_number, p.supplier_id, s.code as supplier_code, s.name as supplier_name,"
                        + " p.po_date, p.expected_date, p.status,"
                        + " (select count(*) from purchase_order_items i where i.purchase_order_id = p.id) as item_count,"
                        + " (select count(*) from purchase_order_items i where i.purchase_order_id = p.id"
                        + "   and i.received_quantity < i.ordered_quantity and p.status in ('OPEN','PARTIALLY_RECEIVED'))"
                        + "   as outstanding_lines,"
                        + " p.demo, p.created_at, p.version" + from + f.where() + " order by " + pq.orderBy()
                        + " limit :limit offset :offset")
                .params(f.params()).param("limit", pq.size()).param("offset", pq.offset())
                .query(PoSummary.class).list();
        return PageResponse.of(rows, pq, total);
    }

    public PoDetail get(long id) {
        record H(long id, String poNumber, long supplierId, String supplierCode, String supplierName, LocalDate poDate,
                LocalDate expectedDate, String status, String notes, String cancelReason, String createdBy,
                Instant createdAt, Instant openedAt, Instant cancelledAt, boolean demo, long version) {}
        H h = jdbc.sql("select p.id, p.po_number, p.supplier_id, s.code as supplier_code, s.name as supplier_name,"
                        + " p.po_date, p.expected_date, p.status, p.notes, p.cancel_reason, u.username as created_by,"
                        + " p.created_at, p.opened_at, p.cancelled_at, p.demo, p.version"
                        + " from purchase_orders p join suppliers s on s.id = p.supplier_id"
                        + " join users u on u.id = p.created_by where p.id = :id")
                .param("id", id).query(H.class).optional().orElseThrow(() -> ApiException.notFound("Purchase order"));
        boolean receivable = RECEIVABLE.contains(h.status());
        List<PoItem> items = jdbc.sql("select i.id, i.line_no, i.material_id, m.code as material_code,"
                        + " m.name as material_name, i.uom_code, u.scale as uom_scale, i.ordered_quantity,"
                        + " i.received_quantity, case when :r then i.ordered_quantity - i.received_quantity else 0 end"
                        + " as outstanding_quantity from purchase_order_items i join materials m on m.id = i.material_id"
                        + " join uoms u on u.code = i.uom_code where i.purchase_order_id = :id order by i.line_no")
                .param("id", id).param("r", receivable).query(PoItem.class).list();
        List<PoReceipt> receipts = jdbc.sql("select g.id, g.document_number, g.status, g.posted_at, u.username as posted_by"
                        + " from goods_receipts g join users u on u.id = g.posted_by where g.purchase_order_id = :id"
                        + " order by g.posted_at, g.id")
                .param("id", id).query(PoReceipt.class).list();
        return new PoDetail(h.id(), h.poNumber(), h.supplierId(), h.supplierCode(), h.supplierName(), h.poDate(),
                h.expectedDate(), h.status(), h.notes(), h.cancelReason(), h.createdBy(), h.createdAt(), h.openedAt(),
                h.cancelledAt(), h.demo(), h.version(), items, receipts);
    }

    private void validate(PoInput in) {
        if (in.supplierId() == null) {
            throw ApiException.field("supplierId", "supplier is required");
        }
        Boolean active = jdbc.sql("select active from suppliers where id = :id").param("id", in.supplierId())
                .query(Boolean.class).optional().orElseThrow(() -> ApiException.field("supplierId", "supplier not found"));
        if (!active) {
            throw ApiException.field("supplierId", "supplier is inactive");
        }
        if (in.poDate() == null) {
            throw ApiException.field("poDate", "PO date is required");
        }
        if (in.expectedDate() != null && in.expectedDate().isBefore(in.poDate())) {
            throw ApiException.field("expectedDate", "expected date cannot be before PO date");
        }
        if (in.notes() != null && in.notes().length() > 500) {
            throw ApiException.field("notes", "max 500 characters");
        }
        if (in.items() == null || in.items().isEmpty()) {
            throw ApiException.field("items", "at least one item is required");
        }
        if (in.items().size() > 100) {
            throw ApiException.field("items", "max 100 items");
        }
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < in.items().size(); i++) {
            ItemInput it = in.items().get(i);
            String fld = "items[" + i + "]";
            if (it.materialId() == null) {
                throw ApiException.field(fld + ".materialId", "material is required");
            }
            if (!seen.add(it.materialId())) {
                throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "DUPLICATE_LINE",
                        "Each material may appear only once per PO",
                        List.of(new com.stockflow.common.ErrorResponse.FieldError(fld + ".materialId", "duplicate material")),
                        Map.of());
            }
            MasterLookup.MaterialInfo m = master.material(it.materialId(), fld + ".materialId");
            if (!m.active()) {
                throw ApiException.field(fld + ".materialId", "material " + m.code() + " is inactive");
            }
            MasterLookup.quantity(it.orderedQuantity(), m, fld + ".orderedQuantity");
        }
    }

    private void insertItems(long poId, List<ItemInput> items) {
        int line = 0;
        for (ItemInput it : items) {
            MasterLookup.MaterialInfo m = master.material(it.materialId(), "materialId");
            jdbc.sql("insert into purchase_order_items (purchase_order_id, line_no, material_id, uom_code, ordered_quantity)"
                            + " values (:po, :ln, :m, :u, :q)")
                    .param("po", poId).param("ln", ++line).param("m", m.id()).param("u", m.uomCode())
                    .param("q", it.orderedQuantity()).update();
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PoDetail create(Actor a, PoInput in, boolean demo) {
        validate(in);
        String number = numbers.next("PO", in.poDate());
        long id = jdbc.sql("insert into purchase_orders (po_number, supplier_id, po_date, expected_date, status, notes,"
                        + " created_by, created_at, updated_at, demo) values (:n, :s, :d, :e, 'DRAFT', :notes, :by, :at, :at, :demo)"
                        + " returning id")
                .param("n", number).param("s", in.supplierId()).param("d", in.poDate()).param("e", in.expectedDate())
                .param("notes", blank(in.notes())).param("by", a.id()).param("at", java.sql.Timestamp.from(time.now()))
                .param("demo", demo).query(Long.class).single();
        insertItems(id, in.items());
        audit.record(a, "PO_CREATE", "PURCHASE_ORDER", id, Map.of("poNumber", number, "items", in.items().size()));
        return get(id);
    }

    private Header lock(long id) {
        return jdbc.sql("select id, status, version from purchase_orders where id = :id for update").param("id", id)
                .query(Header.class).optional().orElseThrow(() -> ApiException.notFound("Purchase order"));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PoDetail update(Actor a, long id, PoInput in) {
        Header h = lock(id);
        if (!h.status().equals("DRAFT")) {
            throw ApiException.conflict("INVALID_STATE", "Only DRAFT purchase orders can be edited (status " + h.status() + ")");
        }
        if (in.version() == null || in.version() != h.version()) {
            throw ApiException.conflict("VERSION_CONFLICT", "Purchase order was changed by someone else. Reload and retry.");
        }
        validate(in);
        jdbc.sql("update purchase_orders set supplier_id = :s, po_date = :d, expected_date = :e, notes = :n,"
                        + " updated_at = now(), version = version + 1 where id = :id")
                .param("s", in.supplierId()).param("d", in.poDate()).param("e", in.expectedDate())
                .param("n", blank(in.notes())).param("id", id).update();
        jdbc.sql("delete from purchase_order_items where purchase_order_id = :id").param("id", id).update();
        insertItems(id, in.items());
        audit.record(a, "PO_UPDATE", "PURCHASE_ORDER", id, Map.of("items", in.items().size()));
        return get(id);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PoDetail open(Actor a, long id) {
        Header h = lock(id);
        if (!h.status().equals("DRAFT")) {
            throw ApiException.conflict("INVALID_STATE", "Only DRAFT purchase orders can be opened (status " + h.status() + ")");
        }
        jdbc.sql("update purchase_orders set status = 'OPEN', opened_at = :at, updated_at = now(), version = version + 1"
                        + " where id = :id")
                .param("at", java.sql.Timestamp.from(time.now())).param("id", id).update();
        audit.record(a, "PO_OPEN", "PURCHASE_ORDER", id, Map.of("from", "DRAFT", "to", "OPEN"));
        return get(id);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PoDetail cancel(Actor a, long id, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw ApiException.field("reason", "cancellation reason is required (max 500 characters)");
        }
        Header h = lock(id);
        if (!Set.of("DRAFT", "OPEN", "PARTIALLY_RECEIVED").contains(h.status())) {
            throw ApiException.conflict("INVALID_STATE", "Purchase order in status " + h.status() + " cannot be cancelled");
        }
        jdbc.sql("update purchase_orders set status = 'CANCELLED', cancel_reason = :r, cancelled_at = :at,"
                        + " updated_at = now(), version = version + 1 where id = :id")
                .param("r", reason.trim()).param("at", java.sql.Timestamp.from(time.now())).param("id", id).update();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("from", h.status());
        d.put("to", "CANCELLED");
        d.put("reason", reason.trim());
        audit.record(a, "PO_CANCEL", "PURCHASE_ORDER", id, d);
        return get(id);
    }

    public PoStatusReport lookupByPoNumber(String poNumber) {
        if (poNumber == null || poNumber.isBlank()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "PO number is required");
        }
        record PoHead(long id, String poNumber, String status, long supplierId, String supplierCode,
                      String supplierName, LocalDate poDate, LocalDate expectedDate) {}

        PoHead head = jdbc.sql("select p.id, p.po_number, p.status, p.supplier_id, s.code as supplier_code, "
                + "s.name as supplier_name, p.po_date, p.expected_date "
                + "from purchase_orders p join suppliers s on s.id = p.supplier_id "
                + "where upper(p.po_number) = upper(:po)")
                .param("po", poNumber.trim())
                .query(PoHead.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Purchase order " + poNumber));

        record ItemRow(int lineNo, long id, long materialId, String code, String name, String uom,
                       BigDecimal ordered, BigDecimal received) {}

        List<ItemRow> items = jdbc.sql("select i.line_no, i.id, i.material_id, m.code, m.name, m.uom_code as uom, "
                + "i.ordered_quantity as ordered, i.received_quantity as received "
                + "from purchase_order_items i join materials m on m.id = i.material_id "
                + "where i.purchase_order_id = :id order by i.line_no")
                .param("id", head.id())
                .query(ItemRow.class)
                .list();

        boolean isReceivable = RECEIVABLE.contains(head.status());
        BigDecimal totOrd = BigDecimal.ZERO;
        BigDecimal totRec = BigDecimal.ZERO;
        List<PoLineStatus> lines = new ArrayList<>();

        for (ItemRow r : items) {
            BigDecimal out = r.ordered().subtract(r.received()).max(BigDecimal.ZERO);
            totOrd = totOrd.add(r.ordered());
            totRec = totRec.add(r.received());
            String lStatus;
            if (r.received().compareTo(BigDecimal.ZERO) == 0) {
                lStatus = "OPEN";
            } else if (r.received().compareTo(r.ordered()) < 0) {
                lStatus = "PARTIAL";
            } else if (r.received().compareTo(r.ordered()) == 0) {
                lStatus = "COMPLETED";
            } else {
                lStatus = "OVER_RECEIVED";
            }
            boolean canReceiveLine = isReceivable && out.signum() > 0;
            lines.add(new PoLineStatus(r.lineNo(), r.id(), r.materialId(), r.code(), r.name(), r.uom(),
                    r.ordered(), r.received(), out, lStatus, canReceiveLine));
        }

        BigDecimal totOut = totOrd.subtract(totRec).max(BigDecimal.ZERO);
        return new PoStatusReport(head.id(), head.poNumber(), head.status(), isReceivable, head.supplierId(),
                head.supplierCode(), head.supplierName(), head.poDate(), head.expectedDate(), items.size(),
                totOrd, totRec, totOut, lines);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String refreshReceiptStatus(long poId) {
        record Agg(String status, BigDecimal received, long incomplete) {}
        Agg g = jdbc.sql("select p.status, coalesce(sum(i.received_quantity), 0) as received,"
                        + " count(*) filter (where i.received_quantity < i.ordered_quantity) as incomplete"
                        + " from purchase_orders p join purchase_order_items i on i.purchase_order_id = p.id"
                        + " where p.id = :id group by p.status")
                .param("id", poId).query(Agg.class).single();
        if (g.status().equals("CANCELLED") || g.status().equals("DRAFT")) {
            return g.status();
        }
        String next = g.incomplete() == 0 ? "COMPLETED" : g.received().signum() == 0 ? "OPEN" : "PARTIALLY_RECEIVED";
        if (!next.equals(g.status())) {
            jdbc.sql("update purchase_orders set status = :s, updated_at = now(), version = version + 1 where id = :id")
                    .param("s", next).param("id", poId).update();
        }
        return next;
    }

    static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
