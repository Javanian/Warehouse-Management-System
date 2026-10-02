package com.stockflow.support;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public class ApiClient {

    public static final JsonMapper MAPPER = JsonMapper.builder().build();

    public record Res(int status, JsonNode body, java.net.http.HttpHeaders headers) {
        public String code() {
            return body == null || body.get("error") == null ? null : body.get("error").asString();
        }

        public long id() {
            return body.get("id").asLong();
        }
    }

    private final String base;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http;

    public ApiClient(int port) {
        this.base = "http://127.0.0.1:" + port;
        this.http = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
    }

    private String xsrf() {
        for (HttpCookie c : cookies.getCookieStore().getCookies()) {
            if (c.getName().equals("XSRF-TOKEN")) {
                return c.getValue();
            }
        }
        get("/api/auth/csrf");
        for (HttpCookie c : cookies.getCookieStore().getCookies()) {
            if (c.getName().equals("XSRF-TOKEN")) {
                return c.getValue();
            }
        }
        return "";
    }

    public boolean hasCookie(String name) {
        return cookies.getCookieStore().getCookies().stream().anyMatch(c -> c.getName().equals(name));
    }

    public void dropCookie(String name) {
        cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals(name)).toList()
                .forEach(c -> cookies.getCookieStore().remove(null, c));
    }

    public Res login(String user, String password) {
        return post("/api/auth/login", java.util.Map.of("username", user, "password", password));
    }

    public ApiClient as(String user) {
        Res r = login(user, TestData.PASSWORD);
        if (r.status() != 200) {
            throw new IllegalStateException("login failed for " + user + ": " + r.status() + " " + r.body());
        }
        return this;
    }

    public Res get(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET(), false);
    }

    public Res post(String path, Object body) {
        return send(json(path, "POST", body), true);
    }

    public Res put(String path, Object body) {
        return send(json(path, "PUT", body), true);
    }

    public Res delete(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).DELETE(), true);
    }

    public Res command(String path, Object body, String key) {
        HttpRequest.Builder b = json(path, "POST", body);
        b.header("Idempotency-Key", key);
        return send(b, true);
    }

    public Res command(String path, Object body) {
        return command(path, body, UUID.randomUUID().toString());
    }

    public Res postNoCsrf(String path, Object body) {
        return send(json(path, "POST", body), false);
    }

    private HttpRequest.Builder json(String path, String method, Object body) {
        String s = body == null ? "" : body instanceof String str ? str : MAPPER.writeValueAsString(body);
        return HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(s));
    }

    private Res send(HttpRequest.Builder b, boolean csrf) {
        try {
            if (csrf) {
                b.header("X-XSRF-TOKEN", xsrf());
            }
            HttpResponse<String> r = http.send(b.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
            JsonNode node = r.body() == null || r.body().isBlank() ? null : tryParse(r.body());
            return new Res(r.statusCode(), node, r.headers());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode tryParse(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }
}
