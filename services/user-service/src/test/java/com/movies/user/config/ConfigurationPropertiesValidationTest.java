package com.movies.user.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
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
                    "jwt.secret=unit-test-jwt-signing-secret-at-least-32-bytes-long",
                    "jwt.expiration=1h",
                    "cors.allowed.origins=http://localhost:3000, http://localhost:5173",
                    "app.kafka.topics.user-activity=user.activity");

    @EnableConfigurationProperties({JwtProperties.class, CorsProperties.class, KafkaTopicsProperties.class})
    static class PropertiesConfig {
    }

    @Test
    @DisplayName("valid configuration binds")
    void validConfigurationBinds() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(JwtProperties.class).expiration()).isEqualTo(Duration.ofHours(1));
            assertThat(context.getBean(CorsProperties.class).origins())
                    .containsExactly("http://localhost:3000", "http://localhost:5173");
        });
    }

    @Test
    @DisplayName("a zero JWT expiration fails startup")
    void zeroJwtExpirationFailsStartup() {
        assertStartupFails("jwt.expiration=0s", "jwt.expiration must be a positive duration");
    }

    @Test
    @DisplayName("a negative JWT expiration fails startup")
    void negativeJwtExpirationFailsStartup() {
        assertStartupFails("jwt.expiration=-5m", "jwt.expiration must be a positive duration");
    }

    @Test
    @DisplayName("blank CORS origins fail startup")
    void blankCorsOriginsFailStartup() {
        assertStartupFails("cors.allowed.origins=", "origins");
    }

    @Test
    @DisplayName("a blank Kafka topic name fails startup")
    void blankTopicFailsStartup() {
        assertStartupFails("app.kafka.topics.user-activity=", "userActivity");
    }

    private void assertStartupFails(String property, String expectedInMessage) {
        runner.withPropertyValues(property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(expectedInMessage);
        });
    }
}
