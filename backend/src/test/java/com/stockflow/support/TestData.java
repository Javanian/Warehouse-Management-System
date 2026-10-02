package com.stockflow.support;

import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public final class TestData {

    public static final String PASSWORD = "Test#Pass2026";
    private static final String HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);

    private final JdbcTemplate jdbc;

    public TestData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long user(String username, String role) {
        Long id = jdbc.query("select id from users where lower(username) = lower(?)", rs -> rs.next() ? rs.getLong(1) : null,
                username);
        if (id != null) {
            return id;
        }
        return jdbc.queryForObject("insert into users (username, email, full_name, password_hash, role) values (?,?,?,?,?)"
                + " returning id", Long.class, username, username + "@test.invalid", "Test " + username, HASH, role);
    }

    public void standardUsers() {
        user("t_admin", "ADMIN");
        user("t_super", "SUPERVISOR");
        user("t_super2", "SUPERVISOR");
        user("t_oper", "OPERATOR");
        user("t_oper2", "OPERATOR");
        user("t_viewer", "VIEWER");
    }

    public long warehouse(String code) {
        return jdbc.queryForObject("insert into warehouses (code, name) values (?, ?) returning id", Long.class, code,
                "WH " + code);
    }

    public long bin(long warehouseId, String code) {
        return jdbc.queryForObject("insert into storage_locations (warehouse_id, code, name, location_type)"
                + " values (?, ?, ?, 'BIN') returning id", Long.class, warehouseId, code, "Bin " + code);
    }

    public long material(String code, String uom) {
        return jdbc.queryForObject("insert into materials (code, name, category, uom_code) values (?, ?, 'FASTENER', ?)"
                + " returning id", Long.class, code, "Mat " + code, uom);
    }

    public long supplier(String code) {
        return jdbc.queryForObject("insert into suppliers (code, name) values (?, ?) returning id", Long.class, code,
                "Sup " + code);
    }

    public BigDecimal balance(long material, long location) {
        BigDecimal b = jdbc.query("select quantity from inventory_balances where material_id = ? and location_id = ?",
                rs -> rs.next() ? rs.getBigDecimal(1) : null, material, location);
        return b == null ? BigDecimal.ZERO : b;
    }

    public BigDecimal ledger(long material, long location) {
        return jdbc.queryForObject("select coalesce(sum(quantity_delta), 0) from stock_movement_entries"
                + " where material_id = ? and location_id = ?", BigDecimal.class, material, location);
    }

    public long mismatches() {
        return jdbc.queryForObject("select count(*) from inventory_balances b full join (select material_id, location_id,"
                + " sum(quantity_delta) t from stock_movement_entries group by 1, 2) s on s.material_id = b.material_id"
                + " and s.location_id = b.location_id where coalesce(b.quantity, 0) <> coalesce(s.t, 0)", Long.class);
    }
}
