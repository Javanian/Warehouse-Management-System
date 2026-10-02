package com.stockflow.common.idempotency;

public record CommandResult<T>(T body, boolean replayed) {}
