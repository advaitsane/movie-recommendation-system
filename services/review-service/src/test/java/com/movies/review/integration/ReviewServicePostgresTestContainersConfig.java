package com.movies.review.integration;

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
 * Spins up an isolated Postgres container for integration tests, independent of whatever
 * POSTGRES_URL is set locally. Only activates when the POSTGRES_URL environment variable itself isn't set.
 *
 * <p>Deliberately checks the raw {@code POSTGRES_URL} env var, not the derived
 * {@code spring.datasource.url} property: application.yml defines that property as
 * {@code ${POSTGRES_URL:jdbc:postgresql://localhost:...}}, so it always resolves to a non-blank
 * value (the hardcoded default) even when POSTGRES_URL itself was never set. Checking the derived
 * property would mean this condition is always false and Testcontainers never activates —
 * catalog-service's MongoDBTestContainersConfig documents having hit exactly this bug once
 * already for MONGODB_URI.
 */
@TestConfiguration
public class ReviewServicePostgresTestContainersConfig {

    @Bean(initMethod = "start")
    @Conditional(PostgresUrlMissingCondition.class)
    public PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:16")
                .withDatabaseName("mflix_reviews_test")
                .withUsername("mflix")
                .withPassword("mflix");
    }

    @Bean
    @Conditional(PostgresUrlMissingCondition.class)
    public DynamicPropertyRegistrar postgresProperties(PostgreSQLContainer postgresContainer) {
        return (registry) -> {
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
