package com.stockflow.common;

import java.util.Map;

public record PageQuery(int page, int size, String orderBy) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public static PageQuery of(Integer page, Integer size, String sort, Map<String, String> allowed, String defaultSort,
            String tieBreaker) {
        int p = page == null ? 0 : page;
        int s = size == null ? DEFAULT_SIZE : size;
        if (p < 0) {
            throw ApiException.field("page", "page must be >= 0");
        }
        if (s < 1 || s > MAX_SIZE) {
            throw ApiException.field("size", "size must be between 1 and " + MAX_SIZE);
        }
        String spec = sort == null || sort.isBlank() ? defaultSort : sort;
        String[] parts = spec.split(",");
        String field = parts[0].trim();
        String dir = parts.length > 1 ? parts[1].trim().toLowerCase() : "asc";
        String expr = allowed.get(field);
        if (expr == null) {
            throw ApiException.field("sort", "sort field not allowed: " + field + " (allowed: " + allowed.keySet() + ")");
        }
        if (!dir.equals("asc") && !dir.equals("desc")) {
            throw ApiException.field("sort", "sort direction must be asc or desc");
        }
        return new PageQuery(p, s, expr + " " + dir + ", " + tieBreaker);
    }

    public int offset() {
        return page * size;
    }
}
