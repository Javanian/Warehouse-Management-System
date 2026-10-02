package com.stockflow.warehouse;

import com.stockflow.audit.AuditService;
import com.stockflow.common.ApiException;
import com.stockflow.common.PageQuery;
import com.stockflow.common.PageResponse;
import com.stockflow.common.SqlFilter;
import com.stockflow.common.idempotency.CommandExecutor;
import com.stockflow.identity.Actor;
import com.stockflow.identity.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Tag(name = "Warehouses", description = "Warehouses and storage locations (bins)")
public class WarehouseController {

    public static final List<String> LOCATION_TYPES = List.of("BIN", "RACK", "FLOOR", "STAGING", "QUARANTINE");
    private static final Map<String, String> LOC_SORT = Map.of("code", "upper(l.code)", "warehouse", "upper(w.code)",
            "name", "lower(l.name)");

    private final JdbcClient jdbc;
    private final CurrentUser currentUser;
    private final AuditService audit;
    private final CommandExecutor executor;

    public WarehouseController(JdbcClient jdbc, CurrentUser currentUser, AuditService audit, CommandExecutor executor) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.audit = audit;
        this.executor = executor;
    }

    public record WarehouseResponse(long id, String code, String name, String description, boolean active,
            boolean demo, long locationCount, long version) {}

    public record WarehouseRequest(@NotBlank @Size(max = 20) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String code,
            @NotBlank @Size(max = 120) String name, @Size(max = 500) String description, Boolean active, Long version) {}

    public record LocationResponse(long id, long warehouseId, String warehouseCode, String code, String name,
            String locationType, boolean active, boolean warehouseActive, boolean demo, long stockLines,
            long version) {}

    public record LocationRequest(@NotNull Long warehouseId,
            @NotBlank @Size(max = 30) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String code,
            @NotBlank @Size(max = 120) String name, @NotBlank String locationType, Boolean active, Long version) {}

    private static final String WH_SELECT = "select w.id, w.code, w.name, w.description, w.active, w.demo,"
            + " (select count(*) from storage_locations l where l.warehouse_id = w.id) as location_count, w.version"
            + " from warehouses w";

    @GetMapping("/warehouses")
    public List<WarehouseResponse> warehouses(@RequestParam(required = false) Boolean active) {
        SqlFilter f = new SqlFilter().add("w.active = :a", "a", active);
        return jdbc.sql(WH_SELECT + f.where() + " order by upper(w.code)").params(f.params())
                .query(WarehouseResponse.class).list();
    }

    private WarehouseResponse warehouse(long id) {
        return jdbc.sql(WH_SELECT + " where w.id = :id").param("id", id).query(WarehouseResponse.class).optional()
                .orElseThrow(() -> ApiException.notFound("Warehouse"));
    }

    @PostMapping("/warehouses")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<WarehouseResponse> createWarehouse(@Valid @RequestBody WarehouseRequest r) {
        Actor a = currentUser.actor();
        long id = executor.runPlain(() -> {
            long newId = jdbc.sql("insert into warehouses (code, name, description) values (:c, :n, :d) returning id")
                    .param("c", r.code().trim().toUpperCase()).param("n", r.name().trim())
                    .param("d", r.description()).query(Long.class).single();
            audit.record(a, "CREATE", "WAREHOUSE", newId, Map.of("code", r.code()));
            return newId;
        });
        return ResponseEntity.status(HttpStatus.CREATED).body(warehouse(id));
    }

    @PutMapping("/warehouses/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public WarehouseResponse updateWarehouse(@PathVariable long id, @Valid @RequestBody WarehouseRequest r) {
        Actor a = currentUser.actor();
        if (r.version() == null) {
            throw ApiException.field("version", "version is required for update");
        }
        executor.runPlain(() -> {
            int n = jdbc.sql("update warehouses set code = :c, name = :n, description = :d, active = coalesce(:a, active),"
                            + " updated_at = now(), version = version + 1 where id = :id and version = :v")
                    .param("c", r.code().trim().toUpperCase()).param("n", r.name().trim()).param("d", r.description())
                    .param("a", r.active()).param("id", id).param("v", r.version()).update();
            if (n == 0) {
                throw ApiException.conflict("VERSION_CONFLICT", "Warehouse was changed by someone else. Reload and retry.");
            }
            audit.record(a, "UPDATE", "WAREHOUSE", id, Map.of("code", r.code(), "active", String.valueOf(r.active())));
            return null;
        });
        return warehouse(id);
    }

    private static final String LOC_SELECT = "select l.id, l.warehouse_id, w.code as warehouse_code, l.code, l.name,"
            + " l.location_type, l.active, w.active as warehouse_active, l.demo,"
            + " (select count(*) from inventory_balances b where b.location_id = l.id and b.quantity > 0) as stock_lines,"
            + " l.version from storage_locations l join warehouses w on w.id = l.warehouse_id";

    @GetMapping("/storage-locations")
    public PageResponse<LocationResponse> locations(@RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) String q, @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        PageQuery pq = PageQuery.of(page, size, sort, LOC_SORT, "warehouse", "upper(l.code), l.id");
        SqlFilter f = new SqlFilter().add("l.warehouse_id = :wh", "wh", warehouseId)
                .search(q, "l.code", "l.name", "w.code || '/' || l.code")
                .add("(l.active and w.active) = :active", "active", active);
        long total = jdbc.sql("select count(*) from storage_locations l join warehouses w on w.id = l.warehouse_id"
                + f.where()).params(f.params()).query(Long.class).single();
        var rows = jdbc.sql(LOC_SELECT + f.where() + " order by " + pq.orderBy() + " limit :limit offset :offset")
                .params(f.params()).param("limit", pq.size()).param("offset", pq.offset())
                .query(LocationResponse.class).list();
        return PageResponse.of(rows, pq, total);
    }

    @GetMapping("/storage-locations/{id}")
    public LocationResponse getLocation(@PathVariable long id) {
        return location(id);
    }

    private LocationResponse location(long id) {
        return jdbc.sql(LOC_SELECT + " where l.id = :id").param("id", id).query(LocationResponse.class).optional()
                .orElseThrow(() -> ApiException.notFound("Storage location"));
    }

    @PostMapping("/storage-locations")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<LocationResponse> createLocation(@Valid @RequestBody LocationRequest r) {
        Actor a = currentUser.actor();
        if (!LOCATION_TYPES.contains(r.locationType())) {
            throw ApiException.field("locationType", "unknown location type");
        }
        warehouse(r.warehouseId());
        long id = executor.runPlain(() -> {
            long newId = jdbc.sql("insert into storage_locations (warehouse_id, code, name, location_type)"
                            + " values (:w, :c, :n, :t) returning id")
                    .param("w", r.warehouseId()).param("c", r.code().trim().toUpperCase()).param("n", r.name().trim())
                    .param("t", r.locationType()).query(Long.class).single();
            audit.record(a, "CREATE", "STORAGE_LOCATION", newId, Map.of("code", r.code(), "warehouseId", r.warehouseId()));
            return newId;
        });
        return ResponseEntity.status(HttpStatus.CREATED).body(location(id));
    }

    @PutMapping("/storage-locations/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public LocationResponse updateLocation(@PathVariable long id, @Valid @RequestBody LocationRequest r) {
        Actor a = currentUser.actor();
        if (!LOCATION_TYPES.contains(r.locationType())) {
            throw ApiException.field("locationType", "unknown location type");
        }
        if (r.version() == null) {
            throw ApiException.field("version", "version is required for update");
        }
        executor.runPlain(() -> {
            LocationResponse before = jdbc.sql(LOC_SELECT + " where l.id = :id for update of l").param("id", id)
                    .query(LocationResponse.class).optional().orElseThrow(() -> ApiException.notFound("Storage location"));
            if (before.warehouseId() != r.warehouseId()) {
                throw ApiException.conflict("INVALID_STATE", "A storage location cannot move to another warehouse");
            }
            boolean active = r.active() == null ? before.active() : r.active();
            if (before.active() && !active) {
                BigDecimal stock = jdbc.sql("select coalesce(sum(quantity), 0) from inventory_balances where location_id = :id")
                        .param("id", id).query(BigDecimal.class).single();
                if (stock.signum() > 0) {
                    throw ApiException.conflict("LOCATION_NOT_EMPTY",
                            "Location still holds stock. Transfer or issue it before deactivating.");
                }
            }
            int n = jdbc.sql("update storage_locations set code = :c, name = :n, location_type = :t, active = :a,"
                            + " updated_at = now(), version = version + 1 where id = :id and version = :v")
                    .param("c", r.code().trim().toUpperCase()).param("n", r.name().trim()).param("t", r.locationType())
                    .param("a", active).param("id", id).param("v", r.version()).update();
            if (n == 0) {
                throw ApiException.conflict("VERSION_CONFLICT", "Location was changed by someone else. Reload and retry.");
            }
            audit.record(a, before.active() && !active ? "DEACTIVATE" : "UPDATE", "STORAGE_LOCATION", id,
                    Map.of("code", r.code(), "active", active));
            return null;
        });
        return location(id);
    }
}
