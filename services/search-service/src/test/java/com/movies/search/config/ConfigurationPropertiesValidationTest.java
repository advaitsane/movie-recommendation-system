package com.movies.search.config;

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
                    "app.kafka.topics.movie-deleted=movie.deleted",
                    "catalog.service.url=http://localhost:8081",
                    "search.vector.index-name=movie_vector_index",
                    "openai.api.base-url=https://api.openai.com",
                    "openai.api.key=",
                    "openai.model=text-embedding-3-small",
                    "openai.embedding-dimensions=1536",
                    "voyage.api.base-url=https://api.voyageai.com",
                    "voyage.api.key=test-key",
                    "voyage.model=voyage-3.5",
                    "voyage.embedding-dimensions=1024");

    @EnableConfigurationProperties({KafkaTopicsProperties.class, CatalogServiceProperties.class, VectorSearchProperties.class,
            OpenAiProperties.class, VoyageProperties.class})
    static class PropertiesConfig {
    }

    @Test
    @DisplayName("valid configuration binds")
    void validConfigurationBinds() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            // A blank key is allowed: the embedding service disables itself rather than failing.
            assertThat(context.getBean(OpenAiProperties.class).api().hasKey()).isFalse();
            assertThat(context.getBean(VoyageProperties.class).api().hasKey()).isTrue();
            assertThat(context.getBean(OpenAiProperties.class).embeddingDimensions()).isEqualTo(1536);
        });
    }

    @Test
    @DisplayName("zero embedding dimensions fail startup")
    void zeroEmbeddingDimensionsFailStartup() {
        assertStartupFails("openai.embedding-dimensions=0", "embeddingDimensions");
    }

    @Test
    @DisplayName("a blank embedding API base URL fails startup")
    void blankEmbeddingBaseUrlFailsStartup() {
        assertStartupFails("voyage.api.base-url=", "baseUrl");
    }

    @Test
    @DisplayName("a blank vector index name fails startup")
    void blankVectorIndexNameFailsStartup() {
        assertStartupFails("search.vector.index-name=", "indexName");
    }

    @Test
    @DisplayName("a blank catalog-service URL fails startup")
    void blankCatalogUrlFailsStartup() {
        assertStartupFails("catalog.service.url=", "url");
    }

    private void assertStartupFails(String property, String expectedInMessage) {
        runner.withPropertyValues(property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(expectedInMessage);
        });
    }
}
