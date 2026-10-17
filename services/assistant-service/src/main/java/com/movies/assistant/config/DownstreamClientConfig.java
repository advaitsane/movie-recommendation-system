package com.movies.assistant.config;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * HTTP clients for the assistant's tools. Both start from the Spring-managed
 * {@link RestClient.Builder}, so each tool call carries trace context and shows up in Jaeger under
 * the chat request that triggered it (see docs/adr/0008).
 */
@Configuration
@EnableConfigurationProperties(DownstreamProperties.class)
public class DownstreamClientConfig {

    @Bean
    public RestClient searchRestClient(RestClient.Builder builder, ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder,
            HttpClientSettings httpClientSettings, DownstreamProperties properties) {
        return builder.clone()
                .baseUrl(properties.searchServiceUrl())
                .requestFactory(requestFactoryBuilder.build(timeouts(httpClientSettings, properties)))
                .build();
    }

    @Bean
    public RestClient recommendationRestClient(RestClient.Builder builder, ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder,
            HttpClientSettings httpClientSettings, DownstreamProperties properties) {
        return builder.clone()
                .baseUrl(properties.recommendationServiceUrl())
                .requestFactory(requestFactoryBuilder.build(timeouts(httpClientSettings, properties)))
                .build();
    }

    private static HttpClientSettings timeouts(HttpClientSettings httpClientSettings, DownstreamProperties properties) {
        return httpClientSettings.withTimeouts(
                Duration.ofMillis(properties.connectTimeoutMs()), Duration.ofMillis(properties.readTimeoutMs()));
    }
}
