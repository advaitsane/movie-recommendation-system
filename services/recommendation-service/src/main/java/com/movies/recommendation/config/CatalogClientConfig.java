package com.movies.recommendation.config;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * HTTP client for the startup backfill from catalog-service's REST API
 * ({@code CatalogBackfillRunner}). Not used on the request-serving path: the only synchronous
 * call a recommendation request makes is still the one to search-service (see
 * {@link SearchClientConfig}).
 */
@Configuration
@EnableConfigurationProperties(CatalogServiceProperties.class)
public class CatalogClientConfig {

    @Bean
    public RestClient catalogRestClient(
            RestClient.Builder builder,
            ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder,
            HttpClientSettings httpClientSettings,
            CatalogServiceProperties properties) {
        HttpClientSettings settings = httpClientSettings.withTimeouts(
                Duration.ofMillis(properties.connectTimeoutMs()), Duration.ofMillis(properties.readTimeoutMs()));

        // Same reason as SearchClientConfig: the managed builder carries the trace-propagating
        // customizer (see docs/adr/0008).
        return builder.baseUrl(properties.url())
                .requestFactory(requestFactoryBuilder.build(settings))
                .build();
    }
}
