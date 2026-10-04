package com.stockflow.agent;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
public class AgentConfiguration {

    @Value("${stockflow.agent.datasource.url:${spring.datasource.url}}")
    private String agentDbUrl;

    @Value("${stockflow.agent.datasource.username:${AGENT_DB_USERNAME:stockflow_agent}}")
    private String agentDbUsername;

    @Value("${stockflow.agent.datasource.password:${AGENT_DB_PASSWORD:}}")
    private String agentDbPassword;

    @Bean(name = "agentDataSource")
    public DataSource agentDataSource() {
        if (agentDbPassword == null || agentDbPassword.isBlank()) {
            throw new IllegalStateException(
                    "AGENT_ISOLATION_MISCONFIGURED: AGENT_DB_PASSWORD is required for StockFlow agent database isolation. "
                    + "Fallback to primary datasource is strictly prohibited to guarantee database-level privilege isolation.");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(agentDbUrl);
        config.setUsername(agentDbUsername);
        config.setPassword(agentDbPassword);
        config.setMaximumPoolSize(5);
        config.setPoolName("StockFlow-Agent-Pool");
        config.setConnectionTimeout(5000);
        return new HikariDataSource(config);
    }

    @Bean(name = "agentJdbcClient")
    public JdbcClient agentJdbcClient(@Qualifier("agentDataSource") DataSource agentDataSource) {
        return JdbcClient.create(agentDataSource);
    }
}
