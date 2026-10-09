package com.movies.assistant.config;

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
                    "assistant.downstream.search-service-url=http://localhost:8082",
                    "assistant.downstream.recommendation-service-url=http://localhost:8084",
                    "assistant.downstream.connect-timeout-ms=2000",
                    "assistant.downstream.read-timeout-ms=6000",
                    "assistant.chat.memory-max-messages=20",
                    "assistant.chat.tool-result-limit=8");

    @EnableConfigurationProperties({DownstreamProperties.class, AssistantProperties.class})
    static class PropertiesConfig {
    }

    @Test
    @DisplayName("valid configuration binds")
    void validConfigurationBinds() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AssistantProperties.class).toolResultLimit()).isEqualTo(8);
            assertThat(context.getBean(DownstreamProperties.class).readTimeoutMs()).isEqualTo(6000);
        });
    }

    @Test
    @DisplayName("a blank search-service URL fails startup")
    void blankSearchServiceUrlFailsStartup() {
        assertStartupFails("assistant.downstream.search-service-url=", "searchServiceUrl");
    }

    @Test
    @DisplayName("a blank recommendation-service URL fails startup")
    void blankRecommendationServiceUrlFailsStartup() {
        assertStartupFails("assistant.downstream.recommendation-service-url=", "recommendationServiceUrl");
    }

    @Test
    @DisplayName("a zero read timeout fails startup")
    void zeroReadTimeoutFailsStartup() {
        assertStartupFails("assistant.downstream.read-timeout-ms=0", "readTimeoutMs");
    }

    @Test
    @DisplayName("a memory window below one exchange fails startup")
    void memoryWindowBelowOneExchangeFailsStartup() {
        assertStartupFails("assistant.chat.memory-max-messages=1", "memoryMaxMessages");
    }

    @Test
    @DisplayName("a tool result limit above 50 fails startup")
    void toolResultLimitAboveFiftyFailsStartup() {
        assertStartupFails("assistant.chat.tool-result-limit=51", "toolResultLimit");
    }

    private void assertStartupFails(String override, String field) {
        runner.withPropertyValues(override).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(field);
        });
    }
}
