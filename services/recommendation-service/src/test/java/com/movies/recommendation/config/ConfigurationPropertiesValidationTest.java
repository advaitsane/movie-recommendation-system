package com.movies.recommendation.config;

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
                    "cors.allowed.origins=http://localhost:3000",
                    "app.kafka.topics.movie-created=movie.created",
                    "app.kafka.topics.movie-updated=movie.updated",
                    "app.kafka.topics.movie-deleted=movie.deleted",
                    "app.kafka.topics.review-created=review.created",
                    "app.kafka.topics.rating-updated=rating.updated",
                    "app.recommendation.top-k-similar-users=10",
                    "app.recommendation.content-weight=0.5",
                    "app.recommendation.collab-weight=0.5",
                    "app.recommendation.cache-ttl-seconds=300",
                    "app.recommendation.like-threshold=4",
                    "search.service.url=http://localhost:8082",
                    "search.service.connect-timeout-ms=2000",
                    "search.service.read-timeout-ms=3000",
                    "catalog.service.url=http://localhost:8081",
                    "catalog.service.connect-timeout-ms=2000",
                    "catalog.service.read-timeout-ms=10000");

    @EnableConfigurationProperties({CorsProperties.class, KafkaTopicsProperties.class, RecommendationProperties.class, SearchServiceProperties.class, CatalogServiceProperties.class})
    static class PropertiesConfig {
    }

    @Test
    @DisplayName("valid configuration binds")
    void validConfigurationBinds() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RecommendationProperties.class).likeThreshold()).isEqualTo(4);
            assertThat(context.getBean(SearchServiceProperties.class).readTimeoutMs()).isEqualTo(3000);
        });
    }

    @Test
    @DisplayName("a blend weight above 1 fails startup")
    void blendWeightAboveOneFailsStartup() {
        assertStartupFails("app.recommendation.content-weight=1.5", "contentWeight");
    }

    @Test
    @DisplayName("a like threshold outside the 1-5 rating scale fails startup")
    void likeThresholdOutsideRatingScaleFailsStartup() {
        assertStartupFails("app.recommendation.like-threshold=6", "likeThreshold");
    }

    @Test
    @DisplayName("a missing top-k fails startup")
    void missingTopKFailsStartup() {
        assertStartupFails("app.recommendation.top-k-similar-users=", "topKSimilarUsers");
    }

    @Test
    @DisplayName("a zero search-service read timeout fails startup")
    void zeroSearchReadTimeoutFailsStartup() {
        assertStartupFails("search.service.read-timeout-ms=0", "readTimeoutMs");
    }

    @Test
    @DisplayName("a blank search-service URL fails startup")
    void blankSearchServiceUrlFailsStartup() {
        assertStartupFails("search.service.url=", "url");
    }

    @Test
    @DisplayName("a blank catalog-service URL fails startup")
    void blankCatalogServiceUrlFailsStartup() {
        assertStartupFails("catalog.service.url=", "url");
    }

    private void assertStartupFails(String property, String expectedInMessage) {
        runner.withPropertyValues(property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(expectedInMessage);
        });
    }
}
