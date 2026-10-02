package com.stockflow.identity;

import java.io.Serializable;

public record StockflowPrincipal(long id, String username) implements Serializable {}
