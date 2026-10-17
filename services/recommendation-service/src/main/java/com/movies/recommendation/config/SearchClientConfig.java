package com.movies.recommendation.config;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * HTTP client for the one synchronous, on-request-path inter-service call in this repo: search-service's
 * {@code GET /api/movies/search/{id}/similar}. Bounded connect/read timeouts, not a circuit breaker,
 * are the whole degradation story — {@code SearchServiceClient}'s catch-all already degrades any
 * failure to collaborative-only. The timeouts differ from catalogRestClient's, so they're set
 * per client on top of Boot's {@link HttpClientSettings}, not as one spring.http.clients value.
 */
@Configuration
@EnableConfigurationProperties(SearchServiceProperties.class)
public class SearchClientConfig {

    @Bean
    public RestClient searchRestClient(
            RestClient.Builder builder,
            ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder,
            HttpClientSettings httpClientSettings,
            SearchServiceProperties properties) {
        HttpClientSettings settings = httpClientSettings.withTimeouts(
                Duration.ofMillis(properties.connectTimeoutMs()), Duration.ofMillis(properties.readTimeoutMs()));

        // Must start from the Spring-managed RestClient.Builder bean, not a static
        // RestClient.builder() — only the managed builder carries the ObservationRestClient
        // customizer that propagates trace context onto this call, the one place in the repo
        // where a distributed trace actually needs to span two services. See docs/adr/0008.
        return builder.baseUrl(properties.url())
                .requestFactory(requestFactoryBuilder.build(settings))
                .build();
    }
}
