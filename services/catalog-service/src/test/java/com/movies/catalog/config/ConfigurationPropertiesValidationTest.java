package com.movies.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Binds the real {@code @ConfigurationProperties} records through Spring's binder, so
 * {@code @Validated} constraints are exercised the same way they run at startup.
 */
@DisplayName("Configuration properties validation")
class ConfigurationPropertiesValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class)
            .withPropertyValues(
                    "app.kafka.topics.movie-created=movie.created",
                    "app.kafka.topics.movie-updated=movie.updated",
                    "app.kafka.topics.movie-deleted=movie.deleted");

    @EnableConfigurationProperties({KafkaTopicsProperties.class})
    static class PropertiesConfig {
    }

    @Test
    @DisplayName("valid configuration binds")
    void validConfigurationBinds() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(KafkaTopicsProperties.class).movieCreated()).isEqualTo("movie.created");
        });
    }

    @Test
    @DisplayName("a blank Kafka topic name fails startup")
    void blankTopicFailsStartup() {
        assertStartupFails("app.kafka.topics.movie-deleted=", "movieDeleted");
    }

    private void assertStartupFails(String property, String expectedInMessage) {
        runner.withPropertyValues(property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(expectedInMessage);
        });
    }
}
