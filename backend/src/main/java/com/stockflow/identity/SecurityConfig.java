package com.stockflow.identity;

import com.stockflow.common.GlobalExceptionHandler;
import com.stockflow.common.Json;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, UserRepository users, Json json,
            SecurityContextRepository contextRepository) throws Exception {
        CookieCsrfTokenRepository csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepo.setCookieCustomizer(c -> c.sameSite("Lax").path("/"));
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);

        http
            .securityContext(sc -> sc.securityContextRepository(contextRepository))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .csrf(csrf -> csrf.csrfTokenRepository(csrfRepo).csrfTokenRequestHandler(csrfHandler))
            .formLogin(f -> f.disable())
            .httpBasic(b -> b.disable())
            .logout(l -> l.disable())
            .headers(h -> h
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:;"
                    + " font-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self';"
                    + " form-action 'self'"))
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                .frameOptions(fo -> fo.deny()))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/auth/login", "/api/auth/csrf").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()

                .requestMatchers("/api/users", "/api/users/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/audit-logs", "/api/audit-logs/**").hasAnyRole("ADMIN", "SUPERVISOR")
                .requestMatchers(HttpMethod.POST, "/api/materials/**", "/api/materials", "/api/suppliers", "/api/suppliers/**",
                        "/api/warehouses", "/api/warehouses/**", "/api/storage-locations", "/api/storage-locations/**")
                    .hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/materials/**", "/api/suppliers/**", "/api/warehouses/**",
                        "/api/storage-locations/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/purchase-orders", "/api/purchase-orders/**").hasAnyRole("ADMIN", "SUPERVISOR")
                .requestMatchers(HttpMethod.PUT, "/api/purchase-orders/**").hasAnyRole("ADMIN", "SUPERVISOR")
                .requestMatchers(HttpMethod.POST, "/api/*/*/reverse", "/api/stock-adjustments/*/approve",
                        "/api/stock-adjustments/*/reject").hasAnyRole("ADMIN", "SUPERVISOR")
                .requestMatchers(HttpMethod.POST, "/api/goods-receipts", "/api/stock-issues", "/api/stock-transfers",
                        "/api/stock-adjustments").hasAnyRole("ADMIN", "SUPERVISOR", "OPERATOR")
                .requestMatchers(HttpMethod.POST, "/api/auth/logout").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/**", "/api/*").denyAll()
                .requestMatchers(HttpMethod.PUT, "/api/**").denyAll()
                .requestMatchers(HttpMethod.DELETE, "/api/**").denyAll()
                .requestMatchers("/api/**").authenticated()
                .requestMatchers("/actuator/**").denyAll()
                .anyRequest().permitAll())
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> write(res, json, HttpStatus.UNAUTHORIZED,
                    "UNAUTHENTICATED", "Login required", req.getRequestURI()))
                .accessDeniedHandler((req, res, ex) -> {
                    boolean csrf = ex.getClass().getSimpleName().contains("Csrf");
                    write(res, json, HttpStatus.FORBIDDEN, csrf ? "CSRF_INVALID" : "FORBIDDEN",
                        csrf ? "Missing or invalid CSRF token. Reload the page and retry."
                             : "You do not have permission for this action", req.getRequestURI());
                }))
            .addFilterBefore(new ActiveUserFilter(users, json), AuthorizationFilter.class);
        return http.build();
    }

    private static void write(HttpServletResponse res, Json json, HttpStatus status, String code, String message,
            String path) throws java.io.IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.getWriter().write(json.write(GlobalExceptionHandler.body(status, code, message, path, null, null)));
    }
}
