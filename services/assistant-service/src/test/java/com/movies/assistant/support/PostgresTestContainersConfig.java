package com.movies.assistant.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.lang.NonNull;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A Postgres container unless POSTGRES_URL is set. Checks the raw variable: spring.datasource.url
 * always resolves to application.yml's default (see review-service's test config).
 */
@TestConfiguration
public class PostgresTestContainersConfig {

    @Bean(initMethod = "start")
    @Conditional(PostgresUrlMissingCondition.class)
    public PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:16")
                .withDatabaseName("mflix_assistant_test")
                .withUsername("mflix")
                .withPassword("mflix");
    }

    @Bean
    @Conditional(PostgresUrlMissingCondition.class)
    public DynamicPropertyRegistrar postgresProperties(PostgreSQLContainer postgresContainer) {
        return registry -> {
            registry.add("spring.datasource.url", postgresContainer::getJdbcUrl);
            registry.add("spring.datasource.username", postgresContainer::getUsername);
            registry.add("spring.datasource.password", postgresContainer::getPassword);
        };
    }

    static class PostgresUrlMissingCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, @NonNull AnnotatedTypeMetadata metadata) {
            String url = context.getEnvironment().getProperty("POSTGRES_URL");
            return url == null || url.isBlank();
        }
    }
}
