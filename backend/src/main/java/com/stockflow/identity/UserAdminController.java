package com.stockflow.identity;

import com.stockflow.audit.AuditService;
import com.stockflow.common.ApiException;
import com.stockflow.common.PageQuery;
import com.stockflow.common.PageResponse;
import com.stockflow.common.SqlFilter;
import com.stockflow.common.idempotency.CommandExecutor;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Users", description = "User administration (ADMIN)")
public class UserAdminController {

    private static final Map<String, String> SORT = Map.of("username", "lower(username)", "fullName",
            "lower(full_name)", "role", "role", "createdAt", "created_at");

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final CommandExecutor executor;

    public UserAdminController(UserRepository users, PasswordEncoder encoder, AuditService audit,
            CurrentUser currentUser, CommandExecutor executor) {
        this.users = users;
        this.encoder = encoder;
        this.audit = audit;
        this.currentUser = currentUser;
        this.executor = executor;
    }

    public record UserResponse(long id, String username, String email, String fullName, Role role, boolean active,
            boolean demo, Instant createdAt, long version) {
        static UserResponse of(UserRecord u) {
            return new UserResponse(u.id(), u.username(), u.email(), u.fullName(), u.role(), u.active(), u.demo(),
                    u.createdAt(), u.version());
        }
    }

    public record CreateUserRequest(
            @NotBlank @Pattern(regexp = "^[a-zA-Z0-9._-]{3,50}$", message = "3-50 chars: letters, digits, . _ -") String username,
            @NotBlank @Email @Size(max = 120) String email,
            @NotBlank @Size(max = 120) String fullName,
            @NotNull Role role,
            @NotBlank @Size(min = 10, max = 100, message = "password must be 10-100 characters") String password) {}

    public record UpdateUserRequest(@NotBlank @Size(max = 120) String fullName, @NotNull Role role,
            @NotNull Boolean active, @NotNull Long version) {}

    public record ResetPasswordRequest(
            @NotBlank @Size(min = 10, max = 100, message = "password must be 10-100 characters") String password) {}

    @GetMapping
    public PageResponse<UserResponse> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) Role role, @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        PageQuery pq = PageQuery.of(page, size, sort, SORT, "username", "id");
        SqlFilter f = new SqlFilter().search(q, "username", "email", "full_name")
                .add("role = :role", "role", role == null ? null : role.name())
                .add("active = :active", "active", active);
        return PageResponse.of(users.page(f, pq).stream().map(UserResponse::of).toList(), pq, users.count(f));
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest r) {
        Actor a = currentUser.actor();
        UserResponse res = executor.runPlain(() -> {
            long id = users.insert(r.username().trim(), r.email().trim().toLowerCase(), r.fullName().trim(),
                    encoder.encode(r.password()), r.role(), false);
            audit.record(a, "CREATE", "USER", id, Map.of("username", r.username(), "role", r.role()));
            return UserResponse.of(users.findById(id).orElseThrow());
        });
        return ResponseEntity.status(HttpStatus.CREATED).body(res);
    }

    @PutMapping("/{id}")
    public UserResponse update(@PathVariable long id, @Valid @RequestBody UpdateUserRequest r) {
        Actor a = currentUser.actor();
        return executor.runPlain(() -> {
            UserRecord before = users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
            if (id == a.id() && (!r.active() || r.role() != Role.ADMIN)) {
                throw ApiException.conflict("INVALID_STATE", "You cannot disable or demote your own account");
            }
            if (before.role() == Role.ADMIN && before.active() && (r.role() != Role.ADMIN || !r.active())
                    && users.countActiveAdmins() <= 1) {
                throw ApiException.conflict("INVALID_STATE", "At least one active administrator is required");
            }
            if (users.update(id, r.version(), r.fullName().trim(), r.role(), r.active()) == 0) {
                throw ApiException.conflict("VERSION_CONFLICT", "User was changed by someone else. Reload and retry.");
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("before", Map.of("role", before.role(), "active", before.active(), "fullName", before.fullName()));
            d.put("after", Map.of("role", r.role(), "active", r.active(), "fullName", r.fullName()));
            audit.record(a, "UPDATE", "USER", id, d);
            return UserResponse.of(users.findById(id).orElseThrow());
        });
    }

    @PostMapping("/{id}/reset-password")
    public ResponseEntity<Void> resetPassword(@PathVariable long id, @Valid @RequestBody ResetPasswordRequest r) {
        Actor a = currentUser.actor();
        executor.runPlain(() -> {
            if (users.updatePassword(id, encoder.encode(r.password())) == 0) {
                throw ApiException.notFound("User");
            }
            audit.record(a, "RESET_PASSWORD", "USER", id, Map.of());
            return null;
        });
        return ResponseEntity.noContent().build();
    }
}
