package com.stockflow.audit;

import com.stockflow.common.Json;
import com.stockflow.common.RequestContext;
import com.stockflow.identity.Actor;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    private final JdbcClient jdbc;
    private final Json json;

    public AuditService(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Actor actor, String action, String entityType, Object entityId, Map<String, ?> details) {
        insert(actor.id(), actor.username(), action, entityType, entityId, details);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void security(Long userId, String login, String action, Map<String, ?> details) {
        String name = login == null ? null : login.length() > 50 ? login.substring(0, 50) : login;
        insert(userId, name, action, "SESSION", userId, details);
    }

    private void insert(Long actorId, String actorName, String action, String entityType, Object entityId,
            Map<String, ?> details) {
        jdbc.sql("insert into audit_logs (actor_id, actor_name, action, entity_type, entity_id, request_id, ip_address,"
                        + " details) values (:a, :n, :act, :t, :id, :rid, :ip, cast(:d as jsonb))")
                .param("a", actorId).param("n", actorName).param("act", action).param("t", entityType)
                .param("id", entityId == null ? null : entityId.toString())
                .param("rid", RequestContext.requestId()).param("ip", RequestContext.clientIp())
                .param("d", details == null || details.isEmpty() ? null : json.write(details))
                .update();
    }
}
