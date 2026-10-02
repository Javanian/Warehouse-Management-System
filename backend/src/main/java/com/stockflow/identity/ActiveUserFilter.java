package com.stockflow.identity;

import com.stockflow.common.GlobalExceptionHandler;
import com.stockflow.common.Json;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class ActiveUserFilter extends OncePerRequestFilter {

    private final UserRepository users;
    private final Json json;

    public ActiveUserFilter(UserRepository users, Json json) {
        this.users = users;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SecurityContext ctx = SecurityContextHolder.getContext();
        Authentication auth = ctx.getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof StockflowPrincipal p) {
            Optional<UserRecord> u = users.findById(p.id());
            if (u.isEmpty() || !u.get().active()) {
                HttpSession s = request.getSession(false);
                if (s != null) {
                    s.invalidate();
                }
                SecurityContextHolder.clearContext();
                response.setStatus(401);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write(json.write(GlobalExceptionHandler.body(HttpStatus.UNAUTHORIZED,
                        "ACCOUNT_DISABLED", "Your account is disabled. Contact an administrator.",
                        request.getRequestURI(), null, null)));
                return;
            }
            String current = "ROLE_" + u.get().role().name();
            boolean same = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(current))
                    && auth.getAuthorities().size() == 1;
            if (!same) {
                UsernamePasswordAuthenticationToken refreshed = UsernamePasswordAuthenticationToken.authenticated(
                        p, null, List.of(new SimpleGrantedAuthority(current)));
                ctx.setAuthentication(refreshed);
                HttpSession s = request.getSession(false);
                if (s != null) {
                    s.setAttribute("SPRING_SECURITY_CONTEXT", ctx);
                }
            }
            MDC.put("user", p.username());
        }
        chain.doFilter(request, response);
    }
}
