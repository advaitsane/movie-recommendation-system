package com.movies.recommendation.config;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
            RestClient.Builder builder, CatalogServiceProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(properties.connectTimeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));

        // Same reason as SearchClientConfig: the managed builder carries the trace-propagating
        // customizer (see docs/adr/0008).
        return builder.baseUrl(properties.url())
                .requestFactory(requestFactory)
                .build();
    }
}
