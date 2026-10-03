package com.movies.catalog.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.lang.NonNull;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.mongodb.MongoDBAtlasLocalContainer;

/**
 * Spins up an isolated Mongo container for integration tests, only when the raw MONGODB_URI
 * env var isn't set — checking the derived spring.mongodb.uri property instead would always
 * see application.yml's hardcoded localhost default and never activate, silently connecting
 * tests to whatever Mongo happened to be listening on localhost:27017.
 */
@TestConfiguration
public class MongoDBTestContainersConfig {

    // Note: @ServiceConnection cannot be used here because MongoDBAtlasLocalContainer
    // extends GenericContainer, not MongoDBContainer. We use initMethod instead so Spring
    // manages the full lifecycle (start on init, close/stop on context shutdown).

    @Bean(initMethod = "start")
    @Conditional(MongoUriMissingCondition.class)
    public MongoDBAtlasLocalContainer mongoDbContainer() {
        return new MongoDBAtlasLocalContainer("mongodb/mongodb-atlas-local:8");
    }

    @Bean
    @Conditional(MongoUriMissingCondition.class)
    public DynamicPropertyRegistrar mongoDbProperties(MongoDBAtlasLocalContainer mongoDBContainer) {
        return (registry) -> {
            registry.add("spring.mongodb.uri", mongoDBContainer::getConnectionString);
        };
    }

    static class MongoUriMissingCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, @NonNull AnnotatedTypeMetadata metadata) {
            String uri = context.getEnvironment().getProperty("MONGODB_URI");
            return uri == null || uri.isBlank();
        }
    }
}
