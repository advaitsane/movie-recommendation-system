package com.movies.search.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * HTTP client for the one-time startup backfill from catalog-service's REST API
 * ({@code CatalogBackfillRunner}). Not used on the request-serving path — search-service's
 * own endpoints never call catalog-service synchronously, by design (see docs/adr/0001's
 * "Alternatives considered": a per-request sync call was rejected).
 */
@Configuration
@EnableConfigurationProperties(CatalogServiceProperties.class)
public class CatalogClientConfig {

    @Bean
    public RestClient catalogRestClient(
            RestClient.Builder builder, CatalogServiceProperties properties) {
        // Must start from the Spring-managed RestClient.Builder bean, not a static
        // RestClient.builder() — only the managed builder carries the ObservationRestClient
        // customizer that propagates trace context (see docs/adr/0008).
        return builder.baseUrl(properties.url()).build();
    }
}
