package com.stockflow.agent;

import com.stockflow.common.ApiException;
import com.stockflow.common.Json;
import com.stockflow.common.RequestContext;
import com.stockflow.identity.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

@Component
public class AgentToolDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AgentToolDispatcher.class);

    public static final Set<String> ALLOWED_TOOLS = Set.of(
            "READ_PURCHASE_ORDER",
            "READ_MATERIAL_CATALOG",
            "READ_INVENTORY_BALANCE",
            "CREATE_DOCUMENT_DRAFT",
            "RUN_DOCUMENT_OCR",
            "VALIDATE_DOMAIN_MATCH"
    );

    private static final Pattern SENSITIVE_KEY_PATTERN = Pattern.compile("(?i).*(password|secret|token|key|credential|auth).*");

    @FunctionalInterface
    public interface ToolAction<T> {
        T execute() throws Exception;
    }

    private static class ActionExecutionException extends RuntimeException {
        public ActionExecutionException(Throwable cause) { super(cause); }
    }

    private final JdbcClient agentJdbc;
    private final JdbcClient adminJdbc;
    private final Json json;
    private final PlatformTransactionManager agentTxManager;
    private final Map<String, AtomicInteger> sequenceTrackers = new java.util.concurrent.ConcurrentHashMap<>();

    public AgentToolDispatcher(@Qualifier("agentJdbcClient") JdbcClient agentJdbc,
                               @Qualifier("jdbcClient") JdbcClient adminJdbc,
                               @Qualifier("agentDataSource") javax.sql.DataSource agentDataSource,
                               Json json) {
        this.agentJdbc = agentJdbc;
        this.adminJdbc = adminJdbc;
        this.agentTxManager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(agentDataSource);
        this.json = json;
    }

    public <T> T dispatch(Actor actor, String runId, String toolName, Map<String, Object> rawParams, ToolAction<T> action) {
        if (runId == null || runId.isBlank()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "runId is required for agent tool dispatch");
        }
        if (toolName == null || toolName.isBlank()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "toolName is required for agent tool dispatch");
        }

        String normalizedToolName = toolName.toUpperCase();
        int seq = sequenceTrackers.computeIfAbsent(runId, k -> new AtomicInteger(1)).getAndIncrement();
        Map<String, Object> sanitizedParams = sanitizeMap(rawParams);

        var runRecord = agentJdbc.sql("select agent_id, triggered_by from agent_runs where run_id = :runId")
                .param("runId", runId)
                .query((rs, rowNum) -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("agent_id", rs.getLong("agent_id"));
                    map.put("triggered_by", rs.getObject("triggered_by"));
                    return map;
                })
                .optional()
                .orElse(null);

        if (runRecord == null) {
            recordSecurityDenial(actor, runId, normalizedToolName, sanitizedParams, "Agent run " + runId + " not found", null, null);
            throw ApiException.notFound("Agent run " + runId);
        }

        Long runOwnerId = (Long) runRecord.get("agent_id");
        Long triggeredBy = (Long) runRecord.get("triggered_by");

        if (runOwnerId == null || runOwnerId != actor.id()) {
            Map<String, Object> denialDetails = new HashMap<>();
            denialDetails.put("denial_reason", "Run ownership mismatch: actor " + actor.id() + " does not own run " + runId);
            denialDetails.put("actor_id", actor.id());
            denialDetails.put("run_owner_id", runOwnerId != null ? runOwnerId : 0L);
            if (RequestContext.requestId() != null) {
                denialDetails.put("correlation_id", RequestContext.requestId());
            }
            recordToolCall(runId, seq, normalizedToolName, sanitizedParams, "DENIED", denialDetails);
            recordSecurityDenial(actor, runId, normalizedToolName, sanitizedParams,
                    "Run ownership mismatch: actor " + actor.id() + " does not own run " + runId, runOwnerId, triggeredBy);
            throw ApiException.forbidden("RUN_OWNERSHIP_MISMATCH",
                    "Actor " + actor.username() + " does not own the agent run " + runId);
        }

        if (!ALLOWED_TOOLS.contains(normalizedToolName)) {
            Map<String, Object> denialDetails = new HashMap<>();
            denialDetails.put("denial_reason", "Tool " + toolName + " is not in the approved agent tool allowlist");
            if (RequestContext.requestId() != null) {
                denialDetails.put("correlation_id", RequestContext.requestId());
            }
            recordToolCall(runId, seq, normalizedToolName, sanitizedParams, "DENIED", denialDetails);
            recordSecurityDenial(actor, runId, normalizedToolName, sanitizedParams,
                    "Tool " + toolName + " is not in the approved agent tool allowlist", runOwnerId, triggeredBy);
            throw ApiException.forbidden("FORBIDDEN_TOOL",
                    "Tool " + toolName + " is not in the approved agent tool allowlist: " + ALLOWED_TOOLS);
        }

        try {
            T result = action.execute();
            Map<String, Object> summary = extractSummary(result);
            recordToolCall(runId, seq, normalizedToolName, sanitizedParams, "SUCCESS", sanitizeMap(summary));
            return result;
        } catch (ApiException ae) {
            recordFailureDurable(runId, seq, normalizedToolName, sanitizedParams, ae.getCode(), ae.getMessage());
            throw ae;
        } catch (Exception e) {
            String rawError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            recordFailureDurable(runId, seq, normalizedToolName, sanitizedParams, "TOOL_ERROR", rawError);
            throw new RuntimeException("Tool execution failed: " + sanitizeString(rawError), e);
        }
    }

    private void recordFailureDurable(String runId, int seq, String toolName, Map<String, Object> params,
                                      String code, String error) {
        try {
            TransactionTemplate tx = new TransactionTemplate(agentTxManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.executeWithoutResult(status -> {
                String safeError = sanitizeString(error);
                Map<String, Object> errSummary = new HashMap<>();
                errSummary.put("error", safeError);
                if (code != null) errSummary.put("code", code);
                if (RequestContext.requestId() != null) {
                    errSummary.put("correlation_id", RequestContext.requestId());
                }
                recordToolCall(runId, seq, toolName, params, "ERROR", errSummary);
            });
        } catch (Exception e) {
            log.error("Failed to record durable failure audit for runId={}, tool={}: {}", runId, toolName, e.getMessage());
            throw new IllegalStateException("AUDIT_UNAVAILABLE: Failed to record tool failure event in persistent audit store: " + e.getMessage(), e);
        }
    }

    private void recordSecurityDenial(Actor actor, String runId, String toolName, Map<String, Object> params,
                                      String reason, Long runOwnerId, Long humanTriggerId) {
        try {
            Long validActorId = null;
            if (actor.id() > 0) {
                Long exists = adminJdbc.sql("select count(*) from users where id = :id")
                        .param("id", actor.id()).query(Long.class).single();
                if (exists > 0) {
                    validActorId = actor.id();
                }
            }

            Map<String, Object> details = new HashMap<>();
            details.put("denial_reason", reason);
            details.put("tool_name", toolName);
            details.put("parameters", params);
            details.put("actor_id", actor.id());
            details.put("actor_username", actor.username());
            details.put("actor_role", actor.role().name());
            if (runOwnerId != null) {
                details.put("run_owner_id", runOwnerId);
            }
            if (humanTriggerId != null) {
                details.put("human_trigger_id", humanTriggerId);
            }
            details.put("correlation_id", RequestContext.requestId());

            adminJdbc.sql("insert into audit_logs (actor_id, actor_name, action, entity_type, entity_id, request_id, ip_address, details) "
                            + "values (:actorId, :actorName, 'AGENT_TOOL_DENIED', 'AGENT_RUN', :runId, :reqId, :ip, cast(:details as jsonb))")
                    .param("actorId", validActorId)
                    .param("actorName", actor.username())
                    .param("runId", runId != null ? runId : "UNKNOWN")
                    .param("reqId", RequestContext.requestId())
                    .param("ip", RequestContext.clientIp())
                    .param("details", json.write(details))
                    .update();
        } catch (Exception e) {
            log.error("Failed to record security denial audit for runId={}, tool={}: {}", runId, toolName, e.getMessage());
            throw new IllegalStateException("Security denial audit recording failed: " + e.getMessage(), e);
        }
    }

    private void recordToolCall(String runId, int seq, String toolName, Map<String, Object> params,
                                String status, Map<String, Object> summary) {
        try {
            agentJdbc.sql("insert into agent_tool_calls (run_id, call_sequence, tool_name, parameters, result_status, result_summary) "
                            + "values (:runId, :seq, :name, :params::jsonb, :status, :summary::jsonb)")
                    .param("runId", runId)
                    .param("seq", seq)
                    .param("name", toolName)
                    .param("params", params != null ? json.write(params) : null)
                    .param("status", status)
                    .param("summary", summary != null ? json.write(summary) : null)
                    .update();
        } catch (Exception e) {
            log.error("Failed to record tool call audit for runId={}, tool={}: {}", runId, toolName, e.getMessage());
            throw new IllegalStateException("Durable audit recording failed for tool " + toolName + ": " + e.getMessage(), e);
        }
    }

    public Map<String, Object> sanitizeMap(Map<String, Object> input) {
        if (input == null) return Map.of();
        Map<String, Object> clean = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String k = entry.getKey();
            if (k != null && SENSITIVE_KEY_PATTERN.matcher(k).matches()) {
                clean.put(k, "[REDACTED]");
            } else {
                clean.put(k, sanitizeValue(entry.getValue()));
            }
        }
        return clean;
    }

    public Object sanitizeValue(Object val) {
        if (val == null) return null;
        if (val instanceof Map<?, ?> m) {
            Map<String, Object> clean = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (SENSITIVE_KEY_PATTERN.matcher(k).matches()) {
                    clean.put(k, "[REDACTED]");
                } else {
                    clean.put(k, sanitizeValue(e.getValue()));
                }
            }
            return clean;
        } else if (val instanceof List<?> l) {
            List<Object> clean = new ArrayList<>();
            for (Object item : l) {
                clean.add(sanitizeValue(item));
            }
            return clean;
        } else if (val instanceof String s) {
            return sanitizeString(s);
        }
        return val;
    }

    public String sanitizeString(String s) {
        if (s == null) return null;
        if (s.toLowerCase().contains("bearer ") || s.toLowerCase().contains("secret") || s.toLowerCase().contains("password")) {
            return "[REDACTED]";
        }
        return s;
    }

    private Map<String, Object> extractSummary(Object result) {
        if (result == null) return Map.of("outcome", "NULL");
        if (result instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> resMap = (Map<String, Object>) m;
            return resMap;
        }
        return Map.of("result_type", result.getClass().getSimpleName());
    }
}
