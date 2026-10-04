package com.stockflow.dataquality;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockflow.common.PageQuery;
import com.stockflow.demo.SyntheticSeeder;
import com.stockflow.identity.Actor;
import com.stockflow.identity.Role;
import com.stockflow.identity.UserRepository;
import com.stockflow.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

class DataQualityIT extends IntegrationTest {

    @Autowired
    private DataQualityService dqService;
    @Autowired
    private SyntheticSeeder synthetic;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder encoder;

    private Actor supervisor;

    @BeforeEach
    void setUp() {
        synthetic.seed();
        long supId = users.findByLogin("sup_dq")
                .map(u -> u.id())
                .orElseGet(() -> users.insert(
                        "sup_dq",
                        "sup@stockflow.invalid",
                        "Supervisor DQ",
                        encoder.encode("secret"),
                        Role.SUPERVISOR,
                        true
                ));
        supervisor = new Actor(supId, "sup_dq", Role.SUPERVISOR);
    }

    @Test
    void scanDetectsCatalogQualityIssuesAndSupportsResolution() {
        // Intentionally create a catalog quality condition:
        // 1. Set a material active=false while it has stock (Rule 1)
        jdbc.update("update materials set active = false where code = 'SYN-MAT-002'");

        // 2. Clear description of a material (Rule 2)
        jdbc.update("update materials set description = '' where code = 'SYN-MAT-003'");

        // 3. Set minimum stock to 0 for active material (Rule 3)
        jdbc.update("update materials set minimum_stock = 0 where code = 'SYN-MAT-005'");

        int scanned = dqService.scanCatalog();
        assertThat(scanned).isGreaterThanOrEqualTo(3);

        var summary = dqService.getSummary();
        assertThat(summary.totalOpen()).isGreaterThanOrEqualTo(3);
        assertThat(summary.highSeverity()).isGreaterThanOrEqualTo(1); // from deactivated material with stock

        var issues = dqService.listIssues("OPEN", null, null, new PageQuery(0, 50, "created_at desc"));
        assertThat(issues.content()).isNotEmpty();

        // Find the high severity issue for SYN-MAT-002
        var highIssue = issues.content().stream()
                .filter(i -> "RULE-DQ-01".equals(i.ruleCode()))
                .findFirst()
                .orElseThrow();

        assertThat(highIssue.entityCode()).isEqualTo("SYN-MAT-002");
        assertThat(highIssue.severity()).isEqualTo("HIGH");
        assertThat(highIssue.status()).isEqualTo("OPEN");

        // Resolve the issue
        var resolved = dqService.resolveIssue(supervisor, highIssue.issueKey(),
                new DataQualityService.ResolveRequest("RESOLVED", "Reactivated material after cycle count"));

        assertThat(resolved.status()).isEqualTo("RESOLVED");
        assertThat(resolved.resolvedBy()).isEqualTo(supervisor.id());
        assertThat(resolved.resolutionNotes()).contains("Reactivated material");

        // Verify summary count updated
        var updatedSummary = dqService.getSummary();
        assertThat(updatedSummary.totalOpen()).isEqualTo(summary.totalOpen() - 1);
    }
}
