package com.stockflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockflow.support.IntegrationTest;
import org.junit.jupiter.api.Test;

class StockflowApplicationTests extends IntegrationTest {

    @Test
    void contextLoadsAndFlywayMigratedRealPostgres() {
        String version = jdbc.queryForObject("select version()", String.class);
        assertThat(version).startsWith("PostgreSQL");
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success", Integer.class);
        assertThat(applied).isGreaterThanOrEqualTo(1);
    }
}
