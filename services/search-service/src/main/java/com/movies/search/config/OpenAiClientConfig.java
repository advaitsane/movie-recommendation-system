package com.movies.search.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * HTTP client for OpenAI's embeddings API — the alternative to {@link VoyageClientConfig},
 * selected by setting {@code embedding.provider=openai} ({@code EMBEDDING_PROVIDER=openai}).
 * See {@code OpenAiEmbeddingService}.
 */
@Configuration
@ConditionalOnProperty(prefix = "embedding", name = "provider", havingValue = "openai")
@EnableConfigurationProperties(OpenAiProperties.class)
public class OpenAiClientConfig {

    @Bean
    public RestClient openAiRestClient(
            RestClient.Builder builder, OpenAiProperties properties) {
        // Must start from the Spring-managed RestClient.Builder bean, not a static
        // RestClient.builder() — only the managed builder carries the ObservationRestClient
        // customizer that emits a trace span for this call (see docs/adr/0008).
        return builder.baseUrl(properties.api().baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.api().key())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
