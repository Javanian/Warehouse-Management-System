package com.stockflow.inventory;

import com.stockflow.common.ApiException;
import com.stockflow.common.DocumentNumberService;
import com.stockflow.common.Quantities;
import com.stockflow.common.TimeSource;
import com.stockflow.identity.Actor;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryPostingService {

    public record Line(long materialId, long locationId, BigDecimal delta) {}

    public record Entry(int lineNo, long materialId, long locationId, BigDecimal delta, BigDecimal balanceAfter) {}

    public record Posted(long movementId, String movementNumber, String documentNumber, Instant postedAt,
            LocalDate businessDate, List<Entry> entries) {}

    public record Request(String movementType, String documentType, Long documentId, String numberDocType,
            String documentNumber, String notes, List<Line> lines, Long reversalOfMovementId) {
        public Request(String movementType, String documentType, Long documentId, String numberDocType,
                String documentNumber, String notes, List<Line> lines) {
            this(movementType, documentType, documentId, numberDocType, documentNumber, notes, lines, null);
        }
    }

    private record Key(long materialId, long locationId) {}

    private record Locked(long id, BigDecimal quantity) {}

    private final JdbcClient jdbc;
    private final DocumentNumberService numbers;
    private final TimeSource time;

    public InventoryPostingService(JdbcClient jdbc, DocumentNumberService numbers, TimeSource time) {
        this.jdbc = jdbc;
        this.numbers = numbers;
        this.time = time;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Posted post(Actor actor, Request req) {
        if (req.lines().isEmpty()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "A movement needs at least one line");
        }
        Map<Key, BigDecimal> inbound = new HashMap<>();
        List<Key> keys = new ArrayList<>();
        for (Line l : req.lines()) {
            if (l.delta() == null || l.delta().signum() == 0) {
                throw ApiException.badRequest("VALIDATION_ERROR", "Movement line quantity must not be zero");
            }
            Key k = new Key(l.materialId(), l.locationId());
            if (!keys.contains(k)) {
                keys.add(k);
            }
            if (l.delta().signum() > 0) {
                inbound.merge(k, l.delta(), BigDecimal::add);
            }
        }
        keys.sort(Comparator.comparingLong(Key::materialId).thenComparingLong(Key::locationId));


        Map<Key, Locked> locked = new LinkedHashMap<>();
        for (Key k : keys) {
            if (inbound.containsKey(k)) {
                jdbc.sql("insert into inventory_balances (material_id, location_id, quantity) values (:m, :l, 0)"
                                + " on conflict (material_id, location_id) do nothing")
                        .param("m", k.materialId()).param("l", k.locationId()).update();
            }
            jdbc.sql("select id, quantity from inventory_balances where material_id = :m and location_id = :l for update")
                    .param("m", k.materialId()).param("l", k.locationId()).query(Locked.class).optional()
                    .ifPresent(r -> locked.put(k, r));
        }

        Map<Key, BigDecimal> running = new HashMap<>();
        locked.forEach((k, r) -> running.put(k, r.quantity()));
        List<Entry> entries = new ArrayList<>();
        int lineNo = 0;
        for (Line l : req.lines()) {
            Key k = new Key(l.materialId(), l.locationId());
            BigDecimal current = running.getOrDefault(k, BigDecimal.ZERO);
            BigDecimal after = current.add(l.delta());
            if (after.signum() < 0) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("materialId", k.materialId());
                d.put("locationId", k.locationId());
                d.put("available", Quantities.fmt(current));
                d.put("requested", Quantities.fmt(l.delta().negate()));
                d.put("line", lineNo + 1);
                throw ApiException.conflict("INSUFFICIENT_STOCK", describe(k) + ": available "
                        + Quantities.fmt(current) + ", requested " + Quantities.fmt(l.delta().negate()), d);
            }
            running.put(k, after);
            entries.add(new Entry(++lineNo, k.materialId(), k.locationId(), l.delta(), after));
        }

        for (Key k : keys) {
            Locked r = locked.get(k);
            jdbc.sql("update inventory_balances set quantity = :q, version = version + 1, updated_at = now() where id = :id")
                    .param("q", running.get(k)).param("id", r.id()).update();
        }

        Instant now = time.now();
        LocalDate businessDate = time.businessDate(now);
        String documentNumber = req.numberDocType() != null ? numbers.next(req.numberDocType(), businessDate)
                : req.documentNumber();
        String movementNumber = numbers.next("MOV", businessDate);
        long movementId = jdbc.sql("insert into stock_movements (movement_number, movement_type, document_type,"
                        + " document_id, document_number, reversal_of_movement_id, posted_by, posted_at, business_date, notes)"
                        + " values (:n, :mt, :dt, :did, :dn, :rev, :by, :at, :bd, :notes) returning id")
                .param("n", movementNumber).param("mt", req.movementType()).param("dt", req.documentType())
                .param("did", req.documentId()).param("dn", documentNumber)
                .param("rev", req.reversalOfMovementId()).param("by", actor.id())
                .param("at", java.sql.Timestamp.from(now)).param("bd", businessDate).param("notes", req.notes())
                .query(Long.class).single();
        for (Entry e : entries) {
            jdbc.sql("insert into stock_movement_entries (movement_id, line_no, material_id, location_id, quantity_delta,"
                            + " balance_after) values (:mv, :ln, :m, :l, :d, :a)")
                    .param("mv", movementId).param("ln", e.lineNo()).param("m", e.materialId())
                    .param("l", e.locationId()).param("d", e.delta()).param("a", e.balanceAfter()).update();
        }
        return new Posted(movementId, movementNumber, documentNumber, now, businessDate, entries);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<Line> negatedLines(long movementId) {
        return jdbc.sql("select material_id, location_id, -quantity_delta as delta from stock_movement_entries"
                        + " where movement_id = :id order by line_no")
                .param("id", movementId).query(Line.class).list();
    }

    private String describe(Key k) {
        return jdbc.sql("select m.code || ' at ' || w.code || '/' || l.code from materials m, storage_locations l"
                        + " join warehouses w on w.id = l.warehouse_id where m.id = :m and l.id = :l")
                .param("m", k.materialId()).param("l", k.locationId()).query(String.class).optional()
                .orElse("material " + k.materialId());
    }
}
