package com.stockflow.common;

import java.util.List;

public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    public static <T> PageResponse<T> of(List<T> content, PageQuery q, long total) {
        return new PageResponse<>(content, q.page(), q.size(), total, (int) Math.ceil(total / (double) q.size()));
    }
}
