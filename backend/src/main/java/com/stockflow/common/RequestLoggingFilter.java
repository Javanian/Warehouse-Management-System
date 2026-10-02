package com.stockflow.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("stockflow.access");
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String inbound = request.getHeader("X-Request-Id");
        String requestId = inbound != null && SAFE_ID.matcher(inbound).matches() ? inbound : UUID.randomUUID().toString();
        long start = System.nanoTime();
        MDC.put(RequestContext.REQUEST_ID, requestId);
        MDC.put(RequestContext.CLIENT_IP, request.getRemoteAddr());
        response.setHeader("X-Request-Id", requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            String uri = request.getRequestURI();
            if (uri.startsWith("/api/")) {
                long ms = (System.nanoTime() - start) / 1_000_000;
                MDC.put("method", request.getMethod());
                MDC.put("endpoint", uri);
                MDC.put("status", Integer.toString(response.getStatus()));
                MDC.put("durationMs", Long.toString(ms));
                log.info("{} {} -> {} in {} ms", request.getMethod(), uri, response.getStatus(), ms);
            }
            MDC.clear();
        }
    }
}
