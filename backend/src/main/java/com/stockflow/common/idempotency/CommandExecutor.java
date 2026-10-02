package com.stockflow.common.idempotency;

import com.stockflow.common.ApiException;
import com.stockflow.identity.Actor;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class CommandExecutor {

    public static final String HEADER = "Idempotency-Key";
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,100}$");

    private final TransactionTemplate tx;
    private final IdempotencyService idempotency;
    private final JdbcClient jdbc;
    private final long lockTimeoutMs;

    public CommandExecutor(PlatformTransactionManager tm, IdempotencyService idempotency, JdbcClient jdbc,
            @Value("${stockflow.lock-timeout-ms:5000}") long lockTimeoutMs) {
        this.tx = new TransactionTemplate(tm);
        this.idempotency = idempotency;
        this.jdbc = jdbc;
        this.lockTimeoutMs = lockTimeoutMs;
    }

    public <T> CommandResult<T> run(Actor actor, String operation, String key, Object payload, Class<T> type,
            Supplier<T> action) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw ApiException.field(HEADER, "Idempotency-Key header must be 8-100 characters [A-Za-z0-9._:-]");
        }
        return tx.execute(status -> {
            jdbc.sql("select set_config('lock_timeout', :v, true)").param("v", lockTimeoutMs + "ms")
                    .query(String.class).single();
            return idempotency.execute(actor.id(), operation, key, payload, type, action);
        });
    }

    public <T> T runPlain(Supplier<T> action) {
        return tx.execute(status -> {
            jdbc.sql("select set_config('lock_timeout', :v, true)").param("v", lockTimeoutMs + "ms")
                    .query(String.class).single();
            return action.get();
        });
    }

    public static <T> ResponseEntity<T> created(CommandResult<T> r) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Idempotent-Replayed", Boolean.toString(r.replayed()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(r.body());
    }

    public static <T> ResponseEntity<T> ok(CommandResult<T> r) {
        return ResponseEntity.ok().header("Idempotent-Replayed", Boolean.toString(r.replayed())).body(r.body());
    }
}
