package com.movies.review.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.movies.review.outbox.OutboxProperties;
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
                    "cors.allowed.origins=http://localhost:3000",
                    "app.kafka.topics.review-created=review.created",
                    "app.kafka.topics.rating-updated=rating.updated",
                    "app.outbox.batch-size=50",
                    "app.outbox.send-timeout-ms=3000",
                    "app.outbox.poll-delay-ms=500");

    @EnableConfigurationProperties({CorsProperties.class, KafkaTopicsProperties.class, OutboxProperties.class})
    static class PropertiesConfig {
    }

    @Test
    @DisplayName("valid configuration binds")
    void validConfigurationBinds() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(OutboxProperties.class).batchSize()).isEqualTo(50);
        });
    }

    @Test
    @DisplayName("a zero outbox batch size fails startup")
    void zeroOutboxBatchSizeFailsStartup() {
        assertStartupFails("app.outbox.batch-size=0", "batchSize");
    }

    @Test
    @DisplayName("a negative outbox poll delay fails startup")
    void negativeOutboxPollDelayFailsStartup() {
        assertStartupFails("app.outbox.poll-delay-ms=-1", "pollDelayMs");
    }

    @Test
    @DisplayName("a blank Kafka topic name fails startup")
    void blankTopicFailsStartup() {
        assertStartupFails("app.kafka.topics.rating-updated=", "ratingUpdated");
    }

    private void assertStartupFails(String property, String expectedInMessage) {
        runner.withPropertyValues(property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(expectedInMessage);
        });
    }
}
