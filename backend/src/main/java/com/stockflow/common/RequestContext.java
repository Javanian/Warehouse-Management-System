package com.stockflow.common;

import org.slf4j.MDC;

public final class RequestContext {
    public static final String REQUEST_ID = "requestId";
    public static final String CLIENT_IP = "clientIp";
    public static final String USER = "user";

    private RequestContext() {}

    public static String requestId() { return MDC.get(REQUEST_ID); }
    public static String clientIp() { return MDC.get(CLIENT_IP); }
}
