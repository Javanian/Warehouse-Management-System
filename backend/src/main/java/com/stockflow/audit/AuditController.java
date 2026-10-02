package com.stockflow.audit;

import com.stockflow.common.PageQuery;
import com.stockflow.common.PageResponse;
import com.stockflow.common.SqlFilter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit-logs")
@PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
@Tag(name = "Audit", description = "Business and security audit trail (ADMIN, SUPERVISOR)")
public class AuditController {

    private static final Map<String, String> SORT = Map.of("occurredAt", "occurred_at", "action", "action");
    private final JdbcClient jdbc;

    public AuditController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record AuditEntry(long id, Instant occurredAt, Long actorId, String actorName, String action,
            String entityType, String entityId, String requestId, String ipAddress, String details) {}

    @GetMapping
    public PageResponse<AuditEntry> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) String action, @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        PageQuery pq = PageQuery.of(page, size, sort, SORT, "occurredAt,desc", "id desc");
        SqlFilter f = new SqlFilter().search(q, "coalesce(actor_name,'')", "coalesce(entity_id,'')", "action")
                .add("action = :action", "action", action)
                .add("entity_type = :et", "et", entityType)
                .add("entity_id = :eid", "eid", entityId)
                .add("occurred_at >= cast(:from as date)", "from", from)
                .add("occurred_at < cast(:to as date) + 1", "to", to);
        long total = jdbc.sql("select count(*) from audit_logs" + f.where()).params(f.params()).query(Long.class).single();
        var rows = jdbc.sql("select id, occurred_at, actor_id, actor_name, action, entity_type, entity_id, request_id,"
                        + " ip_address, details::text as details from audit_logs" + f.where() + " order by " + pq.orderBy()
                        + " limit :limit offset :offset")
                .params(f.params()).param("limit", pq.size()).param("offset", pq.offset())
                .query(AuditEntry.class).list();
        return PageResponse.of(rows, pq, total);
    }
}
