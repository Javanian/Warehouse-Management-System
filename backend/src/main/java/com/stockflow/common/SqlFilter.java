package com.stockflow.common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SqlFilter {
    private final List<String> clauses = new ArrayList<>();
    private final Map<String, Object> params = new HashMap<>();

    public SqlFilter add(String clause) {
        clauses.add(clause);
        return this;
    }

    public SqlFilter add(String clause, String name, Object value) {
        if (value != null && !(value instanceof String s && s.isBlank())) {
            clauses.add(clause);
            params.put(name, value);
        }
        return this;
    }

    public SqlFilter search(String text, String... columns) {
        if (text == null || text.isBlank()) {
            return this;
        }
        String term = "%" + text.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        List<String> ors = new ArrayList<>();
        for (String c : columns) {
            ors.add("lower(" + c + ") like :q");
        }
        clauses.add("(" + String.join(" or ", ors) + ")");
        params.put("q", term);
        return this;
    }

    public String where() {
        return clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses);
    }

    public Map<String, Object> params() {
        return params;
    }
}
