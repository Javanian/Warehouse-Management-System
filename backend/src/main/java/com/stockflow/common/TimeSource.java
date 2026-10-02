package com.stockflow.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TimeSource {

    private static final ThreadLocal<Instant> PINNED = new ThreadLocal<>();
    private final ZoneId zone;

    public TimeSource(@Value("${stockflow.business-timezone:Asia/Jakarta}") String zone) {
        this.zone = ZoneId.of(zone);
    }

    public Instant now() {
        Instant p = PINNED.get();
        return p != null ? p : Instant.now();
    }

    public LocalDate businessDate(Instant instant) {
        return instant.atZone(zone).toLocalDate();
    }

    public LocalDate today() {
        return businessDate(now());
    }

    public ZoneId zone() {
        return zone;
    }

    public static void pin(Instant instant) {
        PINNED.set(instant);
    }

    public static void unpin() {
        PINNED.remove();
    }
}
