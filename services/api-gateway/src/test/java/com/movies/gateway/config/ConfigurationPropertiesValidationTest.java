package com.movies.gateway.config;

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
                    "gateway.rate-limit.limit-for-period=50",
                    "gateway.rate-limit.limit-refresh-period-ms=1000");

    @EnableConfigurationProperties({RateLimitProperties.class})
    static class PropertiesConfig {
    }

    @Test
    @DisplayName("valid configuration binds")
    void validConfigurationBinds() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RateLimitProperties.class).limitForPeriod()).isEqualTo(50);
        });
    }

    @Test
    @DisplayName("a zero rate limit fails startup")
    void zeroRateLimitFailsStartup() {
        assertStartupFails("gateway.rate-limit.limit-for-period=0", "limitForPeriod");
    }

    @Test
    @DisplayName("a negative refresh period fails startup")
    void negativeRefreshPeriodFailsStartup() {
        assertStartupFails("gateway.rate-limit.limit-refresh-period-ms=-1", "limitRefreshPeriodMs");
    }

    private void assertStartupFails(String property, String expectedInMessage) {
        runner.withPropertyValues(property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(expectedInMessage);
        });
    }
}
