package com.stockflow.common.idempotency;

import com.stockflow.common.ApiException;
import com.stockflow.common.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyService {

    private final JdbcClient jdbc;
    private final Json json;

    public IdempotencyService(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public <T> CommandResult<T> execute(long actorId, String operation, String key, Object payload, Class<T> type,
            Supplier<T> action) {
        String hash = sha256(json.write(payload));
        int inserted = jdbc.sql("insert into idempotency_records (actor_id, operation, idem_key, request_hash)"
                        + " values (:a, :o, :k, :h) on conflict (actor_id, operation, idem_key) do nothing")
                .param("a", actorId).param("o", operation).param("k", key).param("h", hash).update();
        if (inserted == 0) {
            Stored stored = jdbc.sql("select request_hash, response_status, response_body from idempotency_records"
                            + " where actor_id = :a and operation = :o and idem_key = :k")
                    .param("a", actorId).param("o", operation).param("k", key).query(Stored.class).single();
            if (!stored.requestHash().equals(hash)) {
                throw ApiException.conflict("IDEMPOTENCY_PAYLOAD_CONFLICT",
                        "This Idempotency-Key was already used with a different request. Use a new key.");
            }
            if (stored.responseBody() == null) {
                throw ApiException.conflict("LOCK_TIMEOUT", "The original request is still being processed. Retry.");
            }
            return new CommandResult<>(json.read(stored.responseBody(), type), true);
        }
        T result = action.get();
        jdbc.sql("update idempotency_records set response_status = 201, response_body = :b"
                        + " where actor_id = :a and operation = :o and idem_key = :k")
                .param("b", json.write(result)).param("a", actorId).param("o", operation).param("k", key).update();
        return new CommandResult<>(result, false);
    }

    record Stored(String requestHash, Integer responseStatus, String responseBody) {}

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
