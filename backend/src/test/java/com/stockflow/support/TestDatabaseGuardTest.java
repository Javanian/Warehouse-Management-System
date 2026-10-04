package com.stockflow.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class TestDatabaseGuardTest {

    static final String URL = env("STOCKFLOW_TEST_DB_URL", "jdbc:postgresql://127.0.0.1:55432/stockflow_test");
    static final String USER = env("STOCKFLOW_TEST_DB_USER", "stockflow_test");
    static final String PASSWORD = env("STOCKFLOW_TEST_DB_PASSWORD", "");
    static final String UNMARKED_URL = URL.substring(0, URL.lastIndexOf('/') + 1) + "stockflow_test_unmarked";

    static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }

    @Test
    void rejectsNonLoopbackAndNonPostgresUrls() {
        assertThatThrownBy(() -> TestDatabaseGuard.checkUrl("jdbc:postgresql://db.example.com:5432/stockflow_test"))
                .hasMessageContaining("not loopback");
        assertThatThrownBy(() -> TestDatabaseGuard.checkUrl("jdbc:h2:mem:x")).hasMessageContaining("not a PostgreSQL");
        TestDatabaseGuard.checkUrl("jdbc:postgresql://127.0.0.1:55432/stockflow_test");
        TestDatabaseGuard.checkUrl("jdbc:postgresql://localhost:5432/stockflow_test");
    }

    @Test
    void rejectsApplicationDatabaseNameWrongRoleAndMissingMarker() {
        assertThatThrownBy(() -> TestDatabaseGuard.checkTarget(
                new TestDatabaseGuard.Target("stockflow", "stockflow_test", TestDatabaseGuard.MARKER)))
                .hasMessageContaining("not an allowed test database name");
        assertThatThrownBy(() -> TestDatabaseGuard.checkTarget(
                new TestDatabaseGuard.Target("postgres", "stockflow_test", TestDatabaseGuard.MARKER)))
                .hasMessageContaining("not an allowed test database name");
        assertThatThrownBy(() -> TestDatabaseGuard.checkTarget(
                new TestDatabaseGuard.Target("stockflow_test", "stockflow", TestDatabaseGuard.MARKER)))
                .hasMessageContaining("test-only role");
        assertThatThrownBy(() -> TestDatabaseGuard.checkTarget(
                new TestDatabaseGuard.Target("stockflow_test", "stockflow_test", null)))
                .hasMessageContaining("marker");
        TestDatabaseGuard.checkTarget(new TestDatabaseGuard.Target("stockflow_test", "stockflow_test", TestDatabaseGuard.MARKER));
    }

    @Test
    void realUnmarkedDatabaseIsRefusedBeforeAnyMutation() throws Exception {
        try (Connection c = DriverManager.getConnection(UNMARKED_URL, USER, PASSWORD); Statement s = c.createStatement()) {
            s.execute("create table if not exists guard_canary (id int primary key)");
            s.execute("insert into guard_canary values (1) on conflict do nothing");
        }
        Flyway flyway = Flyway.configure().cleanDisabled(false)
                .dataSource(new DriverManagerDataSource(UNMARKED_URL, USER, PASSWORD)).load();

        assertThatThrownBy(() -> IntegrationTest.GuardedClean.cleanAndMigrate(flyway))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("marker");

        try (Connection c = DriverManager.getConnection(UNMARKED_URL, USER, PASSWORD); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("select count(*) from guard_canary")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
            try (ResultSet rs = s.executeQuery("select to_regclass('public.flyway_schema_history') is null")) {
                rs.next();
                assertThat(rs.getBoolean(1)).as("no migration ran on the refused target").isTrue();
            }
        }
    }

    @Test
    void configuredTestDatabaseIsAccepted() {
        TestDatabaseGuard.verify(new DriverManagerDataSource(URL, USER, PASSWORD));
    }
}
