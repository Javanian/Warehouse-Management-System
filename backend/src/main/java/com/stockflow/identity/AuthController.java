package com.stockflow.identity;

import com.stockflow.audit.AuditService;
import com.stockflow.common.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "Session login/logout (cookie SF_SESSION + XSRF-TOKEN)")
public class AuthController {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final SecurityContextRepository contextRepository;
    private final LoginThrottle throttle;
    private final AuditService audit;
    private final CurrentUser currentUser;

    private final String dummyHash;

    public AuthController(UserRepository users, PasswordEncoder encoder, SecurityContextRepository contextRepository,
            LoginThrottle throttle, AuditService audit, CurrentUser currentUser) {
        this.users = users;
        this.encoder = encoder;
        this.contextRepository = contextRepository;
        this.throttle = throttle;
        this.audit = audit;
        this.currentUser = currentUser;
        this.dummyHash = encoder.encode(java.util.UUID.randomUUID().toString());
    }

    public record LoginRequest(@NotBlank @Size(max = 120) String username, @NotBlank @Size(max = 200) String password) {}

    public record MeResponse(long id, String username, String fullName, Role role) {}

    @Operation(summary = "Get CSRF token (also set as XSRF-TOKEN cookie)")
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @Operation(summary = "Login with username or email; creates a server session")
    @PostMapping("/login")
    public MeResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        String ip = request.getRemoteAddr();
        if (throttle.isBlocked(body.username(), ip)) {
            audit.security(null, body.username(), "LOGIN_BLOCKED", Map.of("reason", "too many failures"));
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_LOGIN_ATTEMPTS",
                    "Too many failed attempts. Wait a few minutes and try again.");
        }
        Optional<UserRecord> found = users.findByLogin(body.username());
        boolean ok = found.isPresent()
                ? encoder.matches(body.password(), found.get().passwordHash())
                : encoder.matches(body.password(), dummyHash) && false;
        if (!ok) {
            throttle.recordFailure(body.username(), ip);
            audit.security(found.map(UserRecord::id).orElse(null), body.username(), "LOGIN_FAILED", Map.of());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid username or password");
        }
        UserRecord u = found.get();
        if (!u.active()) {
            audit.security(u.id(), u.username(), "LOGIN_DISABLED", Map.of());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED",
                    "Your account is disabled. Contact an administrator.");
        }
        throttle.reset(body.username(), ip);

        HttpSession existing = request.getSession(false);
        if (existing != null) {
            existing.invalidate();
        }
        request.getSession(true);
        UsernamePasswordAuthenticationToken auth = UsernamePasswordAuthenticationToken.authenticated(
                new StockflowPrincipal(u.id(), u.username()), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + u.role().name())));
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        contextRepository.saveContext(ctx, request, response);
        audit.security(u.id(), u.username(), "LOGIN", Map.of());
        return new MeResponse(u.id(), u.username(), u.fullName(), u.role());
    }

    @Operation(summary = "Logout: invalidates the server session")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        Actor a = currentUser.actor();
        HttpSession s = request.getSession(false);
        if (s != null) {
            s.invalidate();
        }
        SecurityContextHolder.clearContext();
        audit.security(a.id(), a.username(), "LOGOUT", Map.of());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Current user")
    @GetMapping("/me")
    public MeResponse me() {
        Actor a = currentUser.actor();
        UserRecord u = users.findById(a.id()).orElseThrow(() -> ApiException.notFound("User"));
        return new MeResponse(u.id(), u.username(), u.fullName(), u.role());
    }
}
