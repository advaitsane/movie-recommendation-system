package com.movies.search.integration;

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
 * Spins up an isolated Mongo container for integration tests, independent of whatever
 * MONGODB_URI is set locally. Only activates when the MONGODB_URI environment variable
 * itself isn't set.
 *
 * <p>Deliberately checks the raw {@code MONGODB_URI} env var, not the derived
 * {@code spring.mongodb.uri} property: application.yml defines that property as
 * {@code ${MONGODB_URI:mongodb://localhost:...}}, so it always resolves to a non-blank value
 * (the hardcoded default) even when MONGODB_URI itself was never set. Checking the derived
 * property meant this condition was always false and Testcontainers never activated — tests
 * silently connected to whatever Mongo happened to be listening on localhost:27017 instead
 * (e.g. a docker-compose Mongo left running from manual testing).
 */
@TestConfiguration
public class MongoDBTestContainersConfig {

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
