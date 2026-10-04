package com.stockflow.support;

import java.net.URI;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;

public final class TestDatabaseGuard {

    public static final String MARKER = "stockflow:disposable-test";
    public static final String TEST_ROLE = "stockflow_test";
    private static final Pattern DB_NAME = Pattern.compile("stockflow_test(_[a-z0-9]+)?");
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "localhost", "::1", "[::1]");

    private TestDatabaseGuard() {}

    public record Target(String database, String user, String comment) {}

    public static void checkUrl(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) {
            throw refuse("not a PostgreSQL JDBC URL: " + jdbcUrl);
        }
        String host = URI.create(jdbcUrl.substring("jdbc:".length())).getHost();
        if (host == null || !LOOPBACK.contains(host.toLowerCase())) {
            throw refuse("host '" + host + "' is not loopback");
        }
    }

    public static void checkTarget(Target t) {
        if (t.database() == null || !DB_NAME.matcher(t.database()).matches()) {
            throw refuse("database '" + t.database() + "' is not an allowed test database name");
        }
        if (!TEST_ROLE.equals(t.user())) {
            throw refuse("connected as '" + t.user() + "', expected the test-only role '" + TEST_ROLE + "'");
        }
        if (!MARKER.equals(t.comment())) {
            throw refuse("database '" + t.database() + "' has no disposable-test marker comment");
        }
    }

    public static Target inspect(DataSource ds) {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("select current_database(), current_user,"
                        + " shobj_description((select oid from pg_database where datname = current_database()), 'pg_database')")) {
            rs.next();
            checkUrl(c.getMetaData().getURL());
            return new Target(rs.getString(1), rs.getString(2), rs.getString(3));
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot inspect test database target", e);
        }
    }

    public static void verify(DataSource ds) {
        checkTarget(inspect(ds));
    }

    private static IllegalStateException refuse(String why) {
        return new IllegalStateException("Refusing destructive test setup: " + why);
    }
}
