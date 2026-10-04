package com.stockflow.demo;

import com.stockflow.identity.Role;
import com.stockflow.identity.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class StartupSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupSeeder.class);
    private final DemoSeeder demo;
    private final SyntheticSeeder synthetic;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final boolean seedDemo;
    private final String bootstrapPassword;

    public StartupSeeder(DemoSeeder demo, SyntheticSeeder synthetic, UserRepository users, PasswordEncoder encoder,
            @Value("${stockflow.demo.seed:false}") boolean seedDemo,
            @Value("${stockflow.bootstrap-admin-password:}") String bootstrapPassword) {
        this.demo = demo;
        this.synthetic = synthetic;
        this.users = users;
        this.encoder = encoder;
        this.seedDemo = seedDemo;
        this.bootstrapPassword = bootstrapPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (seedDemo) {
            boolean createdDemo = demo.seed();
            log.info(createdDemo ? "Demo seed {} created" : "Demo seed {} already present; nothing to do", DemoSeeder.SEED_NAME);
            boolean createdSyn = synthetic.seed();
            log.info(createdSyn ? "Synthetic seed {} created" : "Synthetic seed {} already present; nothing to do", SyntheticSeeder.SEED_NAME);
        } else if (!bootstrapPassword.isBlank() && users.count(new com.stockflow.common.SqlFilter()) == 0) {
            if (bootstrapPassword.length() < 12) {
                throw new IllegalStateException("STOCKFLOW_BOOTSTRAP_ADMIN_PASSWORD must be at least 12 characters");
            }
            users.insert("admin", "admin@stockflow.local", "Administrator", encoder.encode(bootstrapPassword), Role.ADMIN, false);
            log.info("Bootstrap admin user created");
        }
    }
}
