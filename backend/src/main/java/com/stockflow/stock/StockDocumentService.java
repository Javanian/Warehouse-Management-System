package com.stockflow.stock;

import com.stockflow.audit.AuditService;
import com.stockflow.common.ApiException;
import com.stockflow.common.ErrorResponse;
import com.stockflow.common.PageQuery;
import com.stockflow.common.PageResponse;
import com.stockflow.common.Quantities;
import com.stockflow.common.SqlFilter;
import com.stockflow.identity.Actor;
import com.stockflow.inventory.InventoryPostingService;
import com.stockflow.inventory.InventoryPostingService.Line;
import com.stockflow.inventory.InventoryPostingService.Posted;
import com.stockflow.inventory.MasterLookup;
import com.stockflow.inventory.MasterLookup.LocationInfo;
import com.stockflow.inventory.MasterLookup.MaterialInfo;
import com.stockflow.procurement.PurchaseOrderService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockDocumentService {

    public enum DocType {
        GOODS_RECEIPT("goods_receipts", "goods_receipt_items", "goods_receipt_id", "GR", "GOODS_RECEIPT"),
        STOCK_ISSUE("stock_issues", "stock_issue_items", "stock_issue_id", "ISS", "ISSUE"),
        STOCK_TRANSFER("stock_transfers", "stock_transfer_items", "stock_transfer_id", "TRF", "TRANSFER");

        final String table;
        final String itemTable;
        final String fk;
        final String prefix;
        final String movementType;

        DocType(String table, String itemTable, String fk, String prefix, String movementType) {
            this.table = table;
            this.itemTable = itemTable;
            this.fk = fk;
            this.prefix = prefix;
            this.movementType = movementType;
        }
    }

    public static final Set<String> ISSUE_REASONS = Set.of("PRODUCTION", "MAINTENANCE", "INTERNAL_USE", "SCRAP", "SAMPLE");

    public record ReceiptLineInput(Long purchaseOrderItemId, Long locationId, BigDecimal quantity) {}

    public record ReceiptInput(Long purchaseOrderId, String reference, String notes, List<ReceiptLineInput> lines) {}

    public record IssueLineInput(Long materialId, Long locationId, BigDecimal quantity) {}

    public record IssueInput(String reasonCode, String reference, String notes, List<IssueLineInput> lines) {}

    public record TransferLineInput(Long materialId, Long fromLocationId, Long toLocationId, BigDecimal quantity) {}

    public record TransferInput(String reference, String notes, List<TransferLineInput> lines) {}

    public record ReversalInput(String reason) {}

    public record DocLine(int lineNo, long materialId, String materialCode, String materialName, String uomCode,
            int uomScale, Long fromLocationId, String fromLocation, Long toLocationId, String toLocation,
            BigDecimal quantity, Long purchaseOrderItemId) {}

    public record DocView(long id, String type, String documentNumber, String status, String reasonCode,
            String reference, String notes, Long purchaseOrderId, String poNumber, String postedBy, Instant postedAt,
            LocalDate businessDate, String movementNumber, String reversalMovementNumber, String reversalDocumentNumber,
            String reversedBy, Instant reversedAt, String reversalReason, List<DocLine> lines) {}

    public record DocSummary(long id, String documentNumber, String status, String reference, String reasonCode,
            String poNumber, int lineCount, String postedBy, Instant postedAt, LocalDate businessDate) {}

    private record PoItemRow(long id, long purchaseOrderId, long materialId, BigDecimal orderedQuantity,
            BigDecimal receivedQuantity) {}

    private final JdbcClient jdbc;
    private final InventoryPostingService posting;
    private final MasterLookup master;
    private final AuditService audit;
    private final PurchaseOrderService purchaseOrders;

    public StockDocumentService(JdbcClient jdbc, InventoryPostingService posting, MasterLookup master,
            AuditService audit, PurchaseOrderService purchaseOrders) {
        this.jdbc = jdbc;
        this.posting = posting;
        this.master = master;
        this.audit = audit;
        this.purchaseOrders = purchaseOrders;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public DocView receive(Actor a, ReceiptInput in) {
        if (in.purchaseOrderId() == null) {
            throw ApiException.field("purchaseOrderId", "purchase order is required");
        }
        text(in.reference(), "reference", 60, false);
        text(in.notes(), "notes", 500, false);
        List<ReceiptLineInput> lines = lines(in.lines());
        String status = jdbc.sql("select status from purchase_orders where id = :id for update")
                .param("id", in.purchaseOrderId()).query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound("Purchase order"));
        if (!PurchaseOrderService.RECEIVABLE.contains(status)) {
            throw ApiException.conflict("INVALID_STATE", "Purchase order is " + status + " and cannot be received");
        }
        Set<Long> itemIds = new java.util.TreeSet<>();
        for (int i = 0; i < lines.size(); i++) {
            ReceiptLineInput l = lines.get(i);
            if (l.purchaseOrderItemId() == null) {
                throw ApiException.field("lines[" + i + "].purchaseOrderItemId", "PO item is required");
            }
            itemIds.add(l.purchaseOrderItemId());
        }
        Map<Long, PoItemRow> items = new HashMap<>();
        for (Long id : itemIds) {
            PoItemRow r = jdbc.sql("select id, purchase_order_id, material_id, ordered_quantity, received_quantity"
                            + " from purchase_order_items where id = :id for update")
                    .param("id", id).query(PoItemRow.class).optional().orElse(null);
            if (r == null || r.purchaseOrderId() != in.purchaseOrderId()) {
                throw ApiException.field("lines", "PO item " + id + " does not belong to this purchase order");
            }
            items.put(id, r);
        }
        Map<Long, BigDecimal> perItem = new HashMap<>();
        Set<String> dup = new HashSet<>();
        List<Line> post = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            ReceiptLineInput l = lines.get(i);
            String fld = "lines[" + i + "]";
            PoItemRow item = items.get(l.purchaseOrderItemId());
            if (l.locationId() == null) {
                throw ApiException.field(fld + ".locationId", "target bin is required");
            }
            if (!dup.add(l.purchaseOrderItemId() + ":" + l.locationId())) {
                throw duplicate(fld, "same PO item and bin appear twice");
            }
            MaterialInfo m = master.material(item.materialId(), fld + ".purchaseOrderItemId");
            master.requireActive(m);
            MasterLookup.quantity(l.quantity(), m, fld + ".quantity");
            LocationInfo loc = master.location(l.locationId(), fld + ".locationId");
            master.requireInbound(loc, fld + ".locationId");
            perItem.merge(item.id(), l.quantity(), BigDecimal::add);
            post.add(new Line(m.id(), loc.id(), l.quantity()));
        }
        for (var e : perItem.entrySet()) {
            PoItemRow item = items.get(e.getKey());
            BigDecimal outstanding = item.orderedQuantity().subtract(item.receivedQuantity());
            if (e.getValue().compareTo(outstanding) > 0) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("purchaseOrderItemId", item.id());
                d.put("outstanding", Quantities.fmt(outstanding));
                d.put("requested", Quantities.fmt(e.getValue()));
                throw ApiException.conflict("PO_OUTSTANDING_EXCEEDED", "Receipt quantity " + Quantities.fmt(e.getValue())
                        + " exceeds outstanding " + Quantities.fmt(outstanding) + " for PO item " + item.id(), d);
            }
            jdbc.sql("update purchase_order_items set received_quantity = received_quantity + :q, version = version + 1"
                            + " where id = :id")
                    .param("q", e.getValue()).param("id", item.id()).update();
        }
        long docId = nextId(DocType.GOODS_RECEIPT);
        Posted p = posting.post(a, new InventoryPostingService.Request("GOODS_RECEIPT", "GOODS_RECEIPT", docId, "GR", null,
                blank(in.notes()), post));
        jdbc.sql("insert into goods_receipts (id, document_number, purchase_order_id, status, reference, notes, movement_id,"
                        + " posted_by, posted_at, business_date) values (:id, :n, :po, 'POSTED', :ref, :notes, :mv, :by, :at, :bd)")
                .param("id", docId).param("n", p.documentNumber()).param("po", in.purchaseOrderId())
                .param("ref", blank(in.reference())).param("notes", blank(in.notes())).param("mv", p.movementId())
                .param("by", a.id()).param("at", java.sql.Timestamp.from(p.postedAt())).param("bd", p.businessDate()).update();
        for (int i = 0; i < lines.size(); i++) {
            ReceiptLineInput l = lines.get(i);
            jdbc.sql("insert into goods_receipt_items (goods_receipt_id, line_no, purchase_order_item_id, material_id,"
                            + " location_id, quantity) values (:g, :ln, :poi, :m, :l, :q)")
                    .param("g", docId).param("ln", i + 1).param("poi", l.purchaseOrderItemId())
                    .param("m", items.get(l.purchaseOrderItemId()).materialId()).param("l", l.locationId())
                    .param("q", l.quantity()).update();
        }
        String poStatus = purchaseOrders.refreshReceiptStatus(in.purchaseOrderId());
        audit.record(a, "GR_POST", "GOODS_RECEIPT", docId, Map.of("documentNumber", p.documentNumber(),
                "movementNumber", p.movementNumber(), "purchaseOrderId", in.purchaseOrderId(), "poStatus", poStatus,
                "lines", lines.size()));
        return get(DocType.GOODS_RECEIPT, docId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public DocView issue(Actor a, IssueInput in) {
        if (in.reasonCode() == null || !ISSUE_REASONS.contains(in.reasonCode())) {
            throw ApiException.field("reasonCode", "reason must be one of " + new java.util.TreeSet<>(ISSUE_REASONS));
        }
        text(in.reference(), "reference", 60, true);
        text(in.notes(), "notes", 500, false);
        List<IssueLineInput> lines = lines(in.lines());
        Set<String> dup = new HashSet<>();
        List<Line> post = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            IssueLineInput l = lines.get(i);
            String fld = "lines[" + i + "]";
            if (l.materialId() == null) {
                throw ApiException.field(fld + ".materialId", "material is required");
            }
            if (l.locationId() == null) {
                throw ApiException.field(fld + ".locationId", "source bin is required");
            }
            if (!dup.add(l.materialId() + ":" + l.locationId())) {
                throw duplicate(fld, "same material and bin appear twice");
            }
            MaterialInfo m = master.material(l.materialId(), fld + ".materialId");
            MasterLookup.quantity(l.quantity(), m, fld + ".quantity");
            master.location(l.locationId(), fld + ".locationId");
            post.add(new Line(m.id(), l.locationId(), l.quantity().negate()));
        }
        long docId = nextId(DocType.STOCK_ISSUE);
        Posted p = posting.post(a, new InventoryPostingService.Request("ISSUE", "STOCK_ISSUE", docId, "ISS", null,
                blank(in.notes()), post));
        jdbc.sql("insert into stock_issues (id, document_number, status, reason_code, reference, notes, movement_id,"
                        + " posted_by, posted_at, business_date) values (:id, :n, 'POSTED', :rc, :ref, :notes, :mv, :by, :at, :bd)")
                .param("id", docId).param("n", p.documentNumber()).param("rc", in.reasonCode())
                .param("ref", in.reference().trim()).param("notes", blank(in.notes())).param("mv", p.movementId())
                .param("by", a.id()).param("at", java.sql.Timestamp.from(p.postedAt())).param("bd", p.businessDate()).update();
        for (int i = 0; i < lines.size(); i++) {
            IssueLineInput l = lines.get(i);
            jdbc.sql("insert into stock_issue_items (stock_issue_id, line_no, material_id, location_id, quantity)"
                            + " values (:d, :ln, :m, :l, :q)")
                    .param("d", docId).param("ln", i + 1).param("m", l.materialId()).param("l", l.locationId())
                    .param("q", l.quantity()).update();
        }
        audit.record(a, "ISSUE_POST", "STOCK_ISSUE", docId, Map.of("documentNumber", p.documentNumber(),
                "movementNumber", p.movementNumber(), "reasonCode", in.reasonCode(), "lines", lines.size()));
        return get(DocType.STOCK_ISSUE, docId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public DocView transfer(Actor a, TransferInput in) {
        text(in.reference(), "reference", 60, false);
        text(in.notes(), "notes", 500, false);
        List<TransferLineInput> lines = lines(in.lines());
        Set<String> dup = new HashSet<>();
        List<Line> post = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            TransferLineInput l = lines.get(i);
            String fld = "lines[" + i + "]";
            if (l.materialId() == null) {
                throw ApiException.field(fld + ".materialId", "material is required");
            }
            if (l.fromLocationId() == null) {
                throw ApiException.field(fld + ".fromLocationId", "source bin is required");
            }
            if (l.toLocationId() == null) {
                throw ApiException.field(fld + ".toLocationId", "destination bin is required");
            }
            if (l.fromLocationId().equals(l.toLocationId())) {
                throw ApiException.field(fld + ".toLocationId", "destination must differ from source");
            }
            if (!dup.add(l.materialId() + ":" + l.fromLocationId() + ":" + l.toLocationId())) {
                throw duplicate(fld, "same material, source and destination appear twice");
            }
            MaterialInfo m = master.material(l.materialId(), fld + ".materialId");
            MasterLookup.quantity(l.quantity(), m, fld + ".quantity");
            master.location(l.fromLocationId(), fld + ".fromLocationId");
            master.requireInbound(master.location(l.toLocationId(), fld + ".toLocationId"), fld + ".toLocationId");
            post.add(new Line(m.id(), l.fromLocationId(), l.quantity().negate()));
            post.add(new Line(m.id(), l.toLocationId(), l.quantity()));
        }
        long docId = nextId(DocType.STOCK_TRANSFER);
        Posted p = posting.post(a, new InventoryPostingService.Request("TRANSFER", "STOCK_TRANSFER", docId, "TRF", null,
                blank(in.notes()), post));
        jdbc.sql("insert into stock_transfers (id, document_number, status, reference, notes, movement_id, posted_by,"
                        + " posted_at, business_date) values (:id, :n, 'POSTED', :ref, :notes, :mv, :by, :at, :bd)")
                .param("id", docId).param("n", p.documentNumber()).param("ref", blank(in.reference()))
                .param("notes", blank(in.notes())).param("mv", p.movementId()).param("by", a.id())
                .param("at", java.sql.Timestamp.from(p.postedAt())).param("bd", p.businessDate()).update();
        for (int i = 0; i < lines.size(); i++) {
            TransferLineInput l = lines.get(i);
            jdbc.sql("insert into stock_transfer_items (stock_transfer_id, line_no, material_id, from_location_id,"
                            + " to_location_id, quantity) values (:d, :ln, :m, :f, :t, :q)")
                    .param("d", docId).param("ln", i + 1).param("m", l.materialId()).param("f", l.fromLocationId())
                    .param("t", l.toLocationId()).param("q", l.quantity()).update();
        }
        audit.record(a, "TRANSFER_POST", "STOCK_TRANSFER", docId, Map.of("documentNumber", p.documentNumber(),
                "movementNumber", p.movementNumber(), "lines", lines.size()));
        return get(DocType.STOCK_TRANSFER, docId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public DocView reverse(Actor a, DocType type, long id, ReversalInput in) {
        String reason = in == null ? null : in.reason();
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw ApiException.field("reason", "reversal reason is required (max 500 characters)");
        }
        record Head(long id, String documentNumber, String status, long movementId, Long purchaseOrderId) {}
        String poCol = type == DocType.GOODS_RECEIPT ? "purchase_order_id" : "null::bigint";
        Head h = jdbc.sql("select id, document_number, status, movement_id, " + poCol + " as purchase_order_id from "
                        + type.table + " where id = :id for update")
                .param("id", id).query(Head.class).optional().orElseThrow(() -> ApiException.notFound("Document"));
        if (!h.status().equals("POSTED")) {
            throw ApiException.conflict("ALREADY_REVERSED", h.documentNumber() + " has already been reversed");
        }
        if (type == DocType.GOODS_RECEIPT) {
            jdbc.sql("select id from purchase_orders where id = :id for update").param("id", h.purchaseOrderId())
                    .query(Long.class).single();
            record GrItem(long purchaseOrderItemId, BigDecimal quantity) {}
            List<GrItem> grItems = jdbc.sql("select purchase_order_item_id, sum(quantity) as quantity from goods_receipt_items"
                            + " where goods_receipt_id = :id group by purchase_order_item_id order by purchase_order_item_id")
                    .param("id", id).query(GrItem.class).list();
            for (GrItem gi : grItems) {
                BigDecimal received = jdbc.sql("select received_quantity from purchase_order_items where id = :id for update")
                        .param("id", gi.purchaseOrderItemId()).query(BigDecimal.class).single();
                if (received.compareTo(gi.quantity()) < 0) {
                    throw ApiException.conflict("INVALID_STATE", "PO item received quantity is lower than the receipt");
                }
                jdbc.sql("update purchase_order_items set received_quantity = received_quantity - :q, version = version + 1"
                                + " where id = :id")
                        .param("q", gi.quantity()).param("id", gi.purchaseOrderItemId()).update();
            }
        }
        List<Line> neg = posting.negatedLines(h.movementId());
        Posted p;
        try {
            p = posting.post(a, new InventoryPostingService.Request("REVERSAL", type.name(), id, "REV", null,
                    reason.trim(), neg, h.movementId()));
        } catch (ApiException e) {
            if ("INSUFFICIENT_STOCK".equals(e.getCode())) {
                throw ApiException.conflict("REVERSAL_INSUFFICIENT_STOCK", "Cannot reverse " + h.documentNumber()
                        + ": " + e.getMessage() + ". Stock already moved on; reverse later documents first.", e.getDetails());
            }
            throw e;
        }
        jdbc.sql("update " + type.table + " set status = 'REVERSED', reversal_movement_id = :mv, reversed_by = :by,"
                        + " reversed_at = :at, reversal_reason = :r where id = :id")
                .param("mv", p.movementId()).param("by", a.id()).param("at", java.sql.Timestamp.from(p.postedAt()))
                .param("r", reason.trim()).param("id", id).update();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("documentNumber", h.documentNumber());
        d.put("reversalDocumentNumber", p.documentNumber());
        d.put("reversalMovementNumber", p.movementNumber());
        d.put("reason", reason.trim());
        if (type == DocType.GOODS_RECEIPT) {
            d.put("poStatus", purchaseOrders.refreshReceiptStatus(h.purchaseOrderId()));
        }
        audit.record(a, "REVERSE", type.name(), id, d);
        return get(type, id);
    }

    public DocView get(DocType type, long id) {
        String extra = switch (type) {
            case GOODS_RECEIPT -> "null as reason_code, d.purchase_order_id, po.po_number";
            case STOCK_ISSUE -> "d.reason_code, null::bigint as purchase_order_id, null as po_number";
            case STOCK_TRANSFER -> "null as reason_code, null::bigint as purchase_order_id, null as po_number";
        };
        String join = type == DocType.GOODS_RECEIPT ? " join purchase_orders po on po.id = d.purchase_order_id" : "";
        record Head(long id, String documentNumber, String status, String reasonCode, Long purchaseOrderId,
                String poNumber, String reference, String notes, String postedBy, Instant postedAt,
                LocalDate businessDate, String movementNumber, String reversalMovementNumber,
                String reversalDocumentNumber, String reversedBy, Instant reversedAt, String reversalReason) {}
        Head h = jdbc.sql("select d.id, d.document_number, d.status, " + extra + ", d.reference, d.notes,"
                        + " u.username as posted_by, d.posted_at, d.business_date, mv.movement_number,"
                        + " rv.movement_number as reversal_movement_number, rv.document_number as reversal_document_number,"
                        + " ru.username as reversed_by, d.reversed_at, d.reversal_reason from " + type.table + " d" + join
                        + " join users u on u.id = d.posted_by join stock_movements mv on mv.id = d.movement_id"
                        + " left join stock_movements rv on rv.id = d.reversal_movement_id"
                        + " left join users ru on ru.id = d.reversed_by where d.id = :id")
                .param("id", id).query(Head.class).optional().orElseThrow(() -> ApiException.notFound("Document"));
        String lineSql = switch (type) {
            case GOODS_RECEIPT -> "select i.line_no, i.material_id, m.code as material_code, m.name as material_name,"
                    + " m.uom_code, u.scale as uom_scale, null::bigint as from_location_id, null as from_location,"
                    + " i.location_id as to_location_id, w.code || '/' || l.code as to_location, i.quantity,"
                    + " i.purchase_order_item_id from goods_receipt_items i join storage_locations l on l.id = i.location_id"
                    + " join warehouses w on w.id = l.warehouse_id join materials m on m.id = i.material_id"
                    + " join uoms u on u.code = m.uom_code where i.goods_receipt_id = :id order by i.line_no";
            case STOCK_ISSUE -> "select i.line_no, i.material_id, m.code as material_code, m.name as material_name,"
                    + " m.uom_code, u.scale as uom_scale, i.location_id as from_location_id,"
                    + " w.code || '/' || l.code as from_location, null::bigint as to_location_id, null as to_location,"
                    + " i.quantity, null::bigint as purchase_order_item_id from stock_issue_items i"
                    + " join storage_locations l on l.id = i.location_id join warehouses w on w.id = l.warehouse_id"
                    + " join materials m on m.id = i.material_id join uoms u on u.code = m.uom_code"
                    + " where i.stock_issue_id = :id order by i.line_no";
            case STOCK_TRANSFER -> "select i.line_no, i.material_id, m.code as material_code, m.name as material_name,"
                    + " m.uom_code, u.scale as uom_scale, i.from_location_id, fw.code || '/' || fl.code as from_location,"
                    + " i.to_location_id, tw.code || '/' || tl.code as to_location, i.quantity,"
                    + " null::bigint as purchase_order_item_id from stock_transfer_items i"
                    + " join storage_locations fl on fl.id = i.from_location_id join warehouses fw on fw.id = fl.warehouse_id"
                    + " join storage_locations tl on tl.id = i.to_location_id join warehouses tw on tw.id = tl.warehouse_id"
                    + " join materials m on m.id = i.material_id join uoms u on u.code = m.uom_code"
                    + " where i.stock_transfer_id = :id order by i.line_no";
        };
        List<DocLine> lines = jdbc.sql(lineSql).param("id", id).query(DocLine.class).list();
        return new DocView(h.id(), type.name(), h.documentNumber(), h.status(), h.reasonCode(), h.reference(),
                h.notes(), h.purchaseOrderId(), h.poNumber(), h.postedBy(), h.postedAt(), h.businessDate(),
                h.movementNumber(), h.reversalMovementNumber(), h.reversalDocumentNumber(), h.reversedBy(),
                h.reversedAt(), h.reversalReason(), lines);
    }

    private static final Map<String, String> SORT = Map.of("documentNumber", "d.document_number", "postedAt",
            "d.posted_at", "status", "d.status", "businessDate", "d.business_date");

    public PageResponse<DocSummary> list(DocType type, String q, String status, LocalDate from, LocalDate to,
            Long purchaseOrderId, Integer page, Integer size, String sort) {
        PageQuery pq = PageQuery.of(page, size, sort, SORT, "postedAt,desc", "d.id desc");
        boolean gr = type == DocType.GOODS_RECEIPT;
        String join = gr ? " join purchase_orders po on po.id = d.purchase_order_id" : "";
        SqlFilter f = gr ? new SqlFilter().search(q, "d.document_number", "coalesce(d.reference,'')", "po.po_number")
                : new SqlFilter().search(q, "d.document_number", "coalesce(d.reference,'')");
        f.add("d.status = :st", "st", status).add("d.business_date >= :from", "from", from)
                .add("d.business_date <= :to", "to", to);
        if (gr) {
            f.add("d.purchase_order_id = :po", "po", purchaseOrderId);
        }
        String base = " from " + type.table + " d" + join + " join users u on u.id = d.posted_by";
        long total = jdbc.sql("select count(*)" + base + f.where()).params(f.params()).query(Long.class).single();
        var rows = jdbc.sql("select d.id, d.document_number, d.status, d.reference, "
                        + (type == DocType.STOCK_ISSUE ? "d.reason_code" : "null") + " as reason_code, "
                        + (gr ? "po.po_number" : "null") + " as po_number,"
                        + " (select count(*) from " + type.itemTable + " i where i." + type.fk + " = d.id) as line_count,"
                        + " u.username as posted_by, d.posted_at, d.business_date" + base + f.where()
                        + " order by " + pq.orderBy() + " limit :limit offset :offset")
                .params(f.params()).param("limit", pq.size()).param("offset", pq.offset())
                .query(DocSummary.class).list();
        return PageResponse.of(rows, pq, total);
    }

    private long nextId(DocType t) {
        return jdbc.sql("select nextval('" + t.table + "_id_seq')").query(Long.class).single();
    }

    private static <T> List<T> lines(List<T> lines) {
        if (lines == null || lines.isEmpty()) {
            throw ApiException.field("lines", "at least one line is required");
        }
        if (lines.size() > 50) {
            throw ApiException.field("lines", "max 50 lines per document");
        }
        return lines;
    }

    private static void text(String v, String field, int max, boolean required) {
        if (required && (v == null || v.isBlank())) {
            throw ApiException.field(field, field + " is required");
        }
        if (v != null && v.length() > max) {
            throw ApiException.field(field, "max " + max + " characters");
        }
    }

    private static ApiException duplicate(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_LINE", "Duplicate line: " + message,
                List.of(new ErrorResponse.FieldError(field, message)), Map.of());
    }

    static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
