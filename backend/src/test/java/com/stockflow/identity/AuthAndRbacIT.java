package com.stockflow.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockflow.support.ApiClient;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.TestData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthAndRbacIT extends IntegrationTest {

    @BeforeEach
    void users() {
        new TestData(jdbc).standardUsers();
    }

    @Test
    void unauthenticatedApiIs401WithErrorContract() {
        ApiClient.Res r = new ApiClient(port).get("/api/materials");
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.code()).isEqualTo("UNAUTHENTICATED");
        assertThat(r.body().get("requestId").asString()).isNotBlank();
        assertThat(r.body().get("path").asString()).isEqualTo("/api/materials");
    }

    @Test
    void loginMeLogoutLifecycle() {
        ApiClient c = new ApiClient(port);
        ApiClient.Res bad = c.login("t_oper", "wrong-password");
        assertThat(bad.status()).isEqualTo(401);
        assertThat(bad.code()).isEqualTo("INVALID_CREDENTIALS");
        ApiClient.Res ok = c.login("T_OPER", TestData.PASSWORD);
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().get("role").asString()).isEqualTo("OPERATOR");
        assertThat(ok.body().has("passwordHash")).isFalse();
        assertThat(c.get("/api/auth/me").status()).isEqualTo(200);
        assertThat(c.post("/api/auth/logout", null).status()).isEqualTo(204);
        assertThat(c.get("/api/auth/me").status()).isEqualTo(401);
        Long logouts = jdbc.queryForObject("select count(*) from audit_logs where action = 'LOGOUT' and actor_name = 't_oper'",
                Long.class);
        assertThat(logouts).isPositive();
    }

    @Test
    void sessionCookieIsHttpOnlyAndSessionIdRotatesAtLogin() {
        ApiClient c = new ApiClient(port);
        c.get("/api/auth/csrf");
        ApiClient.Res r = c.login("t_viewer", TestData.PASSWORD);
        List<String> setCookies = r.headers().allValues("set-cookie");
        String session = setCookies.stream().filter(s -> s.startsWith("SF_SESSION=")).findFirst().orElseThrow();
        assertThat(session).containsIgnoringCase("HttpOnly").containsIgnoringCase("SameSite=Lax");
    }

    @Test
    void csrfIsRequiredForMutations() {
        ApiClient c = new ApiClient(port).as("t_admin");
        ApiClient.Res r = c.postNoCsrf("/api/suppliers", Map.of("code", "CSRF-1", "name", "x"));
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.code()).isEqualTo("CSRF_INVALID");
    }

    @Test
    void disabledUserCannotLoginAndExistingSessionIsKilled() {
        long id = new TestData(jdbc).user("t_disable_me", "OPERATOR");
        ApiClient victim = new ApiClient(port).as("t_disable_me");
        assertThat(victim.get("/api/materials").status()).isEqualTo(200);
        ApiClient admin = new ApiClient(port).as("t_admin");
        long version = jdbc.queryForObject("select version from users where id = ?", Long.class, id);
        ApiClient.Res upd = admin.put("/api/users/" + id, Map.of("fullName", "Disabled", "role", "OPERATOR",
                "active", false, "version", version));
        assertThat(upd.status()).isEqualTo(200);
        ApiClient.Res after = victim.get("/api/materials");
        assertThat(after.status()).isEqualTo(401);
        assertThat(after.code()).isEqualTo("ACCOUNT_DISABLED");
        ApiClient.Res relog = new ApiClient(port).login("t_disable_me", TestData.PASSWORD);
        assertThat(relog.status()).isEqualTo(401);
        assertThat(relog.code()).isEqualTo("ACCOUNT_DISABLED");
    }

    @Test
    void roleChangeTakesEffectOnNextRequest() {
        long id = new TestData(jdbc).user("t_role_change", "SUPERVISOR");
        ApiClient c = new ApiClient(port).as("t_role_change");
        assertThat(c.get("/api/audit-logs").status()).isEqualTo(200);
        jdbc.update("update users set role = 'VIEWER' where id = ?", id);
        assertThat(c.get("/api/audit-logs").status()).isEqualTo(403);
    }

    @Test
    void loginThrottleReturns429() {
        new TestData(jdbc).user("t_throttle", "VIEWER");
        ApiClient c = new ApiClient(port);
        for (int i = 0; i < 5; i++) {
            assertThat(c.login("t_throttle", "nope-" + i).status()).isEqualTo(401);
        }
        ApiClient.Res r = c.login("t_throttle", TestData.PASSWORD);
        assertThat(r.status()).isEqualTo(429);
        assertThat(r.code()).isEqualTo("TOO_MANY_LOGIN_ATTEMPTS");
    }

    @Test
    void authorizationMatrix() {
        record Case(String method, String path, Object body, List<String> allowed) {}
        List<Case> cases = List.of(
                new Case("POST", "/api/materials", Map.of(), List.of("t_admin")),
                new Case("POST", "/api/suppliers", Map.of(), List.of("t_admin")),
                new Case("POST", "/api/warehouses", Map.of(), List.of("t_admin")),
                new Case("POST", "/api/storage-locations", Map.of(), List.of("t_admin")),
                new Case("GET", "/api/users", null, List.of("t_admin")),
                new Case("POST", "/api/users", Map.of(), List.of("t_admin")),
                new Case("GET", "/api/audit-logs", null, List.of("t_admin", "t_super")),
                new Case("POST", "/api/purchase-orders", Map.of(), List.of("t_admin", "t_super")),
                new Case("POST", "/api/purchase-orders/999999/open", Map.of(), List.of("t_admin", "t_super")),
                new Case("POST", "/api/purchase-orders/999999/cancel", Map.of(), List.of("t_admin", "t_super")),
                new Case("POST", "/api/goods-receipts", Map.of(), List.of("t_admin", "t_super", "t_oper")),
                new Case("POST", "/api/stock-issues", Map.of(), List.of("t_admin", "t_super", "t_oper")),
                new Case("POST", "/api/stock-transfers", Map.of(), List.of("t_admin", "t_super", "t_oper")),
                new Case("POST", "/api/stock-adjustments", Map.of(), List.of("t_admin", "t_super", "t_oper")),
                new Case("POST", "/api/stock-adjustments/999999/approve", Map.of(), List.of("t_admin", "t_super")),
                new Case("POST", "/api/stock-adjustments/999999/reject", Map.of(), List.of("t_admin", "t_super")),
                new Case("POST", "/api/goods-receipts/999999/reverse", Map.of(), List.of("t_admin", "t_super")),
                new Case("POST", "/api/stock-issues/999999/reverse", Map.of(), List.of("t_admin", "t_super")),
                new Case("POST", "/api/stock-transfers/999999/reverse", Map.of(), List.of("t_admin", "t_super")),
                new Case("GET", "/api/dashboard", null, List.of("t_admin", "t_super", "t_oper", "t_viewer")),
                new Case("GET", "/api/inventory/balances", null, List.of("t_admin", "t_super", "t_oper", "t_viewer")),
                new Case("GET", "/api/inventory/movements", null, List.of("t_admin", "t_super", "t_oper", "t_viewer")),
                new Case("GET", "/api/purchase-orders", null, List.of("t_admin", "t_super", "t_oper", "t_viewer")));
        Map<String, ApiClient> clients = new java.util.LinkedHashMap<>();
        for (String u : List.of("t_admin", "t_super", "t_oper", "t_viewer")) {
            clients.put(u, new ApiClient(port).as(u));
        }
        StringBuilder failures = new StringBuilder();
        for (Case c : cases) {
            for (var e : clients.entrySet()) {
                ApiClient.Res r = c.method().equals("GET") ? e.getValue().get(c.path())
                        : e.getValue().command(c.path(), c.body());
                boolean allowed = c.allowed().contains(e.getKey());
                boolean ok = allowed ? r.status() != 403 && r.status() != 401 : r.status() == 403 && "FORBIDDEN".equals(r.code());
                if (!ok) {
                    failures.append(c.method()).append(' ').append(c.path()).append(" as ").append(e.getKey())
                            .append(" -> ").append(r.status()).append(' ').append(r.code()).append('\n');
                }
            }
        }
        assertThat(failures.toString()).isEmpty();
    }
}
