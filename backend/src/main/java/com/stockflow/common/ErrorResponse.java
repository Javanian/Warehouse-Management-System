package com.stockflow.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorResponse(Instant timestamp, int status, String error, String message, String path, String requestId,
        List<FieldError> fieldErrors, Map<String, Object> details) {

    public record FieldError(String field, String message) {}
}
