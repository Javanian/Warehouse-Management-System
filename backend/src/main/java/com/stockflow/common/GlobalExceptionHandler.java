package com.stockflow.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public static ErrorResponse body(HttpStatus status, String code, String message, String path,
            List<ErrorResponse.FieldError> fields, Map<String, Object> details) {
        return new ErrorResponse(Instant.now(), status.value(), code, message, path, RequestContext.requestId(),
                fields, details);
    }

    private ResponseEntity<ErrorResponse> respond(HttpStatus status, String code, String message, HttpServletRequest req,
            List<ErrorResponse.FieldError> fields, Map<String, Object> details) {
        return ResponseEntity.status(status).body(body(status, code, message, req.getRequestURI(), fields, details));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> api(ApiException ex, HttpServletRequest req) {
        return respond(ex.getStatus(), ex.getCode(), ex.getMessage(), req, ex.getFieldErrors(), ex.getDetails());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> invalid(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<ErrorResponse.FieldError> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> new ErrorResponse.FieldError(f.getField(), f.getDefaultMessage())).toList();
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", req, fields, null);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ErrorResponse> invalidParams(HandlerMethodValidationException ex, HttpServletRequest req) {
        List<ErrorResponse.FieldError> fields = ex.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream().map(e -> new ErrorResponse.FieldError(
                        r.getMethodParameter().getParameterName(), e.getDefaultMessage())))
                .toList();
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", req, fields, null);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ErrorResponse> violation(ConstraintViolationException ex, HttpServletRequest req) {
        List<ErrorResponse.FieldError> fields = ex.getConstraintViolations().stream()
                .map(v -> new ErrorResponse.FieldError(v.getPropertyPath().toString(), v.getMessage())).toList();
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", req, fields, null);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<ErrorResponse> unreadable(Exception ex, HttpServletRequest req) {
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Malformed request or parameter", req, null, null);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ErrorResponse> missingHeader(MissingRequestHeaderException ex, HttpServletRequest req) {
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Missing header " + ex.getHeaderName(), req,
                List.of(new ErrorResponse.FieldError(ex.getHeaderName(), "required")), null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> denied(AccessDeniedException ex, HttpServletRequest req) {
        return respond(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission for this action", req, null, null);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ErrorResponse> duplicate(DuplicateKeyException ex, HttpServletRequest req) {
        log.info("duplicate key: {}", rootMessage(ex));
        return respond(HttpStatus.CONFLICT, "DUPLICATE_CODE", "A record with the same unique key already exists", req,
                null, null);
    }

    @ExceptionHandler({CannotAcquireLockException.class, PessimisticLockingFailureException.class})
    ResponseEntity<ErrorResponse> lock(Exception ex, HttpServletRequest req) {
        log.warn("lock conflict: {}", rootMessage(ex));
        return respond(HttpStatus.CONFLICT, "LOCK_TIMEOUT",
                "The records are busy with another transaction. Retry the same request.", req, null, null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> integrity(DataIntegrityViolationException ex, HttpServletRequest req) {
        String msg = rootMessage(ex);
        log.warn("integrity violation: {}", msg);
        if (msg != null && msg.contains("ck_balance_nonnegative")) {
            return respond(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", "Stock would become negative", req, null, null);
        }
        if (msg != null && msg.contains("ck_poi_not_over_received")) {
            return respond(HttpStatus.CONFLICT, "PO_OUTSTANDING_EXCEEDED", "Receipt exceeds ordered quantity", req,
                    null, null);
        }
        if (msg != null && msg.contains("restrict_violation") || msg != null && msg.contains("immutable")) {
            return respond(HttpStatus.CONFLICT, "INVALID_STATE", "Posted records cannot be changed", req, null, null);
        }
        return respond(HttpStatus.CONFLICT, "INTEGRITY_VIOLATION", "The change violates a data integrity rule", req,
                null, null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> noResource(NoResourceFoundException ex, HttpServletRequest req) {
        return respond(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found", req, null, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> method(HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Method not allowed", req, null, null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception ex, HttpServletRequest req) {
        log.error("unhandled error on {}", req.getRequestURI(), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error. Reference the requestId.",
                req, null, null);
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getMessage();
    }
}
