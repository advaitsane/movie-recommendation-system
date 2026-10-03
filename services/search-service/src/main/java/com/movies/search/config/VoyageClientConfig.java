package com.movies.search.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * HTTP client for Voyage AI's embeddings API, used by {@code VoyageEmbeddingService} to build
 * the vectors behind semantic search over movies_search. Only created when
 * {@code embedding.provider=voyage} (the default) — see {@code OpenAiClientConfig} for the
 * alternative provider. Voyage's wire shape is snake_case, so request/response records annotate
 * fields with {@code @JsonProperty} explicitly rather than relying on a naming strategy.
 */
@Configuration
@ConditionalOnProperty(prefix = "embedding", name = "provider", havingValue = "voyage", matchIfMissing = true)
@EnableConfigurationProperties(VoyageProperties.class)
public class VoyageClientConfig {

    @Bean
    public RestClient voyageRestClient(
            RestClient.Builder builder, VoyageProperties properties) {
        // Must start from the Spring-managed RestClient.Builder bean, not a static
        // RestClient.builder() — only the managed builder carries the ObservationRestClient
        // customizer that emits a trace span for this call (see docs/adr/0008).
        return builder.baseUrl(properties.api().baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.api().key())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
