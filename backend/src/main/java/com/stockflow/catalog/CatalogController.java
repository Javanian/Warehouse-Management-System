package com.stockflow.catalog;

import com.stockflow.audit.AuditService;
import com.stockflow.common.ApiException;
import com.stockflow.common.PageQuery;
import com.stockflow.common.PageResponse;
import com.stockflow.common.Quantities;
import com.stockflow.common.SqlFilter;
import com.stockflow.common.idempotency.CommandExecutor;
import com.stockflow.identity.Actor;
import com.stockflow.identity.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
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
@Tag(name = "Catalog", description = "Materials, units of measure, suppliers")
public class CatalogController {

    private static final Map<String, String> MAT_SORT = Map.of("code", "upper(m.code)", "name", "lower(m.name)",
            "category", "m.category", "updatedAt", "m.updated_at", "totalQuantity", "total_quantity");
    private static final Map<String, String> SUP_SORT = Map.of("code", "upper(code)", "name", "lower(name)");
    public static final List<String> CATEGORIES = List.of("FASTENER", "ELECTRICAL", "PIPING", "CHEMICAL", "PACKAGING",
            "SPARE_PART", "CONSUMABLE", "SAFETY", "RAW_MATERIAL");

    private final JdbcClient jdbc;
    private final CurrentUser currentUser;
    private final AuditService audit;
    private final CommandExecutor executor;

    public CatalogController(JdbcClient jdbc, CurrentUser currentUser, AuditService audit, CommandExecutor executor) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.audit = audit;
        this.executor = executor;
    }

    public record Uom(String code, String name, int scale) {}

    public record MaterialResponse(long id, String code, String name, String description, String category,
            String uomCode, int uomScale, BigDecimal minimumStock, boolean active, boolean demo,
            BigDecimal totalQuantity, boolean lowStock, boolean referenced, Instant createdAt, Instant updatedAt,
            long version) {}

    public record MaterialRequest(
            @NotBlank @Size(max = 40) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._/-]*$",
                    message = "letters, digits and . _ / - only") String code,
            @NotBlank @Size(max = 160) String name,
            @Size(max = 1000) String description,
            @NotBlank String category,
            @NotBlank String uomCode,
            @NotNull @DecimalMin(value = "0", message = "must be >= 0") BigDecimal minimumStock,
            Boolean active,
            Long version) {}

    public record SupplierResponse(long id, String code, String name, boolean active, boolean demo, long version) {}

    public record SupplierRequest(@NotBlank @Size(max = 30) @Pattern(regexp = "^[A-Za-z0-9._-]+$") String code,
            @NotBlank @Size(max = 120) String name, Boolean active, Long version) {}

    @GetMapping("/uoms")
    public List<Uom> uoms() {
        return jdbc.sql("select code, name, scale from uoms order by code").query(Uom.class).list();
    }

    @GetMapping("/material-categories")
    public List<String> categories() {
        return CATEGORIES;
    }

    private static final String MAT_SELECT = "select m.id, m.code, m.name, m.description, m.category, m.uom_code,"
            + " u.scale as uom_scale, m.minimum_stock, m.active, m.demo,"
            + " coalesce(t.total, 0) as total_quantity,"
            + " (m.active and m.minimum_stock > 0 and coalesce(t.total, 0) < m.minimum_stock) as low_stock,"
            + " (exists (select 1 from purchase_order_items i where i.material_id = m.id)"
            + "  or exists (select 1 from stock_movement_entries e where e.material_id = m.id)) as referenced,"
            + " m.created_at, m.updated_at, m.version"
            + " from materials m join uoms u on u.code = m.uom_code"
            + " left join (select material_id, sum(quantity) as total from inventory_balances group by material_id) t"
            + " on t.material_id = m.id";

    @GetMapping("/materials")
    public PageResponse<MaterialResponse> materials(@RequestParam(required = false) String q,
            @RequestParam(required = false) String category, @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Boolean lowStock,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        PageQuery pq = PageQuery.of(page, size, sort, MAT_SORT, "code", "m.id");
        SqlFilter f = new SqlFilter().search(q, "m.code", "m.name")
                .add("m.category = :cat", "cat", category)
                .add("m.active = :active", "active", active);
        if (Boolean.TRUE.equals(lowStock)) {
            f.add("(m.active and m.minimum_stock > 0 and coalesce(t.total, 0) < m.minimum_stock)");
        }
        long total = jdbc.sql("select count(*) from materials m left join (select material_id, sum(quantity) as total"
                + " from inventory_balances group by material_id) t on t.material_id = m.id" + f.where())
                .params(f.params()).query(Long.class).single();
        List<MaterialResponse> rows = jdbc.sql(MAT_SELECT + f.where() + " order by " + pq.orderBy()
                        + " limit :limit offset :offset")
                .params(f.params()).param("limit", pq.size()).param("offset", pq.offset())
                .query(MaterialResponse.class).list();
        return PageResponse.of(rows, pq, total);
    }

    @GetMapping("/materials/{id}")
    public MaterialResponse material(@PathVariable long id) {
        return jdbc.sql(MAT_SELECT + " where m.id = :id").param("id", id).query(MaterialResponse.class).optional()
                .orElseThrow(() -> ApiException.notFound("Material"));
    }

    private int uomScale(String uom) {
        return jdbc.sql("select scale from uoms where code = :c").param("c", uom).query(Integer.class).optional()
                .orElseThrow(() -> ApiException.field("uomCode", "unknown unit of measure"));
    }

    private void validateMaterial(MaterialRequest r) {
        if (!CATEGORIES.contains(r.category())) {
            throw ApiException.field("category", "unknown category");
        }
        Quantities.requireScale(r.minimumStock(), uomScale(r.uomCode()), r.uomCode(), "minimumStock");
    }

    @PostMapping("/materials")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<MaterialResponse> createMaterial(@Valid @RequestBody MaterialRequest r) {
        Actor a = currentUser.actor();
        validateMaterial(r);
        long id = executor.runPlain(() -> {
            long newId = jdbc.sql("insert into materials (code, name, description, category, uom_code, minimum_stock)"
                            + " values (:code, :name, :d, :cat, :uom, :min) returning id")
                    .param("code", r.code().trim()).param("name", r.name().trim()).param("d", blankToNull(r.description()))
                    .param("cat", r.category()).param("uom", r.uomCode()).param("min", r.minimumStock())
                    .query(Long.class).single();
            audit.record(a, "CREATE", "MATERIAL", newId, Map.of("code", r.code(), "uom", r.uomCode()));
            return newId;
        });
        return ResponseEntity.status(HttpStatus.CREATED).body(material(id));
    }

    @PutMapping("/materials/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public MaterialResponse updateMaterial(@PathVariable long id, @Valid @RequestBody MaterialRequest r) {
        Actor a = currentUser.actor();
        validateMaterial(r);
        if (r.version() == null) {
            throw ApiException.field("version", "version is required for update");
        }
        executor.runPlain(() -> {
            MaterialResponse before = material(id);
            if (!before.uomCode().equals(r.uomCode()) && before.referenced()) {
                throw ApiException.conflict("UOM_LOCKED",
                        "Unit of measure cannot change after the material is used in a PO or stock movement");
            }
            boolean active = r.active() == null ? before.active() : r.active();
            int n = jdbc.sql("update materials set code = :code, name = :name, description = :d, category = :cat,"
                            + " uom_code = :uom, minimum_stock = :min, active = :active, updated_at = now(),"
                            + " version = version + 1 where id = :id and version = :v")
                    .param("code", r.code().trim()).param("name", r.name().trim()).param("d", blankToNull(r.description()))
                    .param("cat", r.category()).param("uom", r.uomCode()).param("min", r.minimumStock())
                    .param("active", active).param("id", id).param("v", r.version()).update();
            if (n == 0) {
                throw ApiException.conflict("VERSION_CONFLICT", "Material was changed by someone else. Reload and retry.");
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("before", Map.of("code", before.code(), "name", before.name(), "category", before.category(),
                    "uom", before.uomCode(), "minimumStock", before.minimumStock(), "active", before.active()));
            d.put("after", Map.of("code", r.code(), "name", r.name(), "category", r.category(), "uom", r.uomCode(),
                    "minimumStock", r.minimumStock(), "active", active));
            audit.record(a, before.active() && !active ? "DEACTIVATE" : "UPDATE", "MATERIAL", id, d);
            return null;
        });
        return material(id);
    }

    @GetMapping("/suppliers")
    public PageResponse<SupplierResponse> suppliers(@RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean active, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        PageQuery pq = PageQuery.of(page, size, sort, SUP_SORT, "code", "id");
        SqlFilter f = new SqlFilter().search(q, "code", "name").add("active = :active", "active", active);
        long total = jdbc.sql("select count(*) from suppliers" + f.where()).params(f.params()).query(Long.class).single();
        var rows = jdbc.sql("select id, code, name, active, demo, version from suppliers" + f.where() + " order by "
                        + pq.orderBy() + " limit :limit offset :offset")
                .params(f.params()).param("limit", pq.size()).param("offset", pq.offset())
                .query(SupplierResponse.class).list();
        return PageResponse.of(rows, pq, total);
    }

    @GetMapping("/suppliers/{id}")
    public SupplierResponse supplier(@PathVariable long id) {
        return jdbc.sql("select id, code, name, active, demo, version from suppliers where id = :id").param("id", id)
                .query(SupplierResponse.class).optional().orElseThrow(() -> ApiException.notFound("Supplier"));
    }

    @PostMapping("/suppliers")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SupplierResponse> createSupplier(@Valid @RequestBody SupplierRequest r) {
        Actor a = currentUser.actor();
        SupplierResponse res = executor.runPlain(() -> {
            long id = jdbc.sql("insert into suppliers (code, name) values (:c, :n) returning id")
                    .param("c", r.code().trim()).param("n", r.name().trim()).query(Long.class).single();
            audit.record(a, "CREATE", "SUPPLIER", id, Map.of("code", r.code()));
            return jdbc.sql("select id, code, name, active, demo, version from suppliers where id = :id")
                    .param("id", id).query(SupplierResponse.class).single();
        });
        return ResponseEntity.status(HttpStatus.CREATED).body(res);
    }

    @PutMapping("/suppliers/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public SupplierResponse updateSupplier(@PathVariable long id, @Valid @RequestBody SupplierRequest r) {
        Actor a = currentUser.actor();
        if (r.version() == null) {
            throw ApiException.field("version", "version is required for update");
        }
        return executor.runPlain(() -> {
            int n = jdbc.sql("update suppliers set code = :c, name = :n, active = coalesce(:a, active), updated_at = now(),"
                            + " version = version + 1 where id = :id and version = :v")
                    .param("c", r.code().trim()).param("n", r.name().trim()).param("a", r.active())
                    .param("id", id).param("v", r.version()).update();
            if (n == 0) {
                throw ApiException.conflict("VERSION_CONFLICT", "Supplier was changed by someone else. Reload and retry.");
            }
            audit.record(a, "UPDATE", "SUPPLIER", id, Map.of("code", r.code(), "name", r.name()));
            return jdbc.sql("select id, code, name, active, demo, version from suppliers where id = :id")
                    .param("id", id).query(SupplierResponse.class).single();
        });
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
