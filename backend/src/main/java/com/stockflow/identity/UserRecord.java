package com.stockflow.identity;

import java.time.Instant;

public record UserRecord(long id, String username, String email, String fullName, String passwordHash, Role role,
        boolean active, boolean demo, Instant createdAt, Instant updatedAt, long version) {}
