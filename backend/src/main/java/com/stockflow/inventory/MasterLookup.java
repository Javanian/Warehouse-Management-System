package com.stockflow.inventory;

import com.stockflow.common.ApiException;
import com.stockflow.common.Quantities;
import java.math.BigDecimal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class MasterLookup {

    public record MaterialInfo(long id, String code, String name, String uomCode, int scale, boolean active) {}

    public record LocationInfo(long id, String code, long warehouseId, String warehouseCode, boolean active,
            boolean warehouseActive) {
        public boolean usable() {
            return active && warehouseActive;
        }
    }

    private final JdbcClient jdbc;

    public MasterLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public MaterialInfo material(long id, String field) {
        return jdbc.sql("select m.id, m.code, m.name, m.uom_code, u.scale, m.active from materials m"
                        + " join uoms u on u.code = m.uom_code where m.id = :id")
                .param("id", id).query(MaterialInfo.class).optional()
                .orElseThrow(() -> ApiException.field(field, "material " + id + " not found"));
    }

    public LocationInfo location(long id, String field) {
        return jdbc.sql("select l.id, l.code, l.warehouse_id, w.code as warehouse_code, l.active,"
                        + " w.active as warehouse_active from storage_locations l join warehouses w on w.id = l.warehouse_id"
                        + " where l.id = :id")
                .param("id", id).query(LocationInfo.class).optional()
                .orElseThrow(() -> ApiException.field(field, "storage location " + id + " not found"));
    }

    public void requireInbound(LocationInfo l, String field) {
        if (!l.usable()) {
            throw ApiException.conflict("LOCATION_INACTIVE",
                    "Location " + l.warehouseCode() + "/" + l.code() + " is inactive and cannot receive stock");
        }
    }

    public void requireActive(MaterialInfo m) {
        if (!m.active()) {
            throw ApiException.conflict("MATERIAL_INACTIVE", "Material " + m.code() + " is inactive");
        }
    }

    public static void quantity(BigDecimal qty, MaterialInfo m, String field) {
        Quantities.requirePositive(qty, field);
        Quantities.requireScale(qty, m.scale(), m.uomCode(), field);
    }
}
