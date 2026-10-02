package com.stockflow.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LoginThrottle {

    private final int maxFailures;
    private final Duration window;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public LoginThrottle(@Value("${stockflow.login.max-failures:5}") int maxFailures,
            @Value("${stockflow.login.window-minutes:5}") int windowMinutes) {
        this.maxFailures = maxFailures;
        this.window = Duration.ofMinutes(windowMinutes);
    }

    private static String key(String login, String ip) {
        return (login == null ? "" : login.trim().toLowerCase()) + "|" + ip;
    }

    public boolean isBlocked(String login, String ip) {
        Deque<Instant> d = failures.get(key(login, ip));
        if (d == null) {
            return false;
        }
        synchronized (d) {
            prune(d);
            return d.size() >= maxFailures;
        }
    }

    public void recordFailure(String login, String ip) {
        Deque<Instant> d = failures.computeIfAbsent(key(login, ip), k -> new ArrayDeque<>());
        synchronized (d) {
            prune(d);
            d.addLast(Instant.now());
        }
    }

    public void reset(String login, String ip) {
        failures.remove(key(login, ip));
    }

    public void clearAll() {
        failures.clear();
    }

    private void prune(Deque<Instant> d) {
        Instant limit = Instant.now().minus(window);
        while (!d.isEmpty() && d.peekFirst().isBefore(limit)) {
            d.pollFirst();
        }
    }
}
