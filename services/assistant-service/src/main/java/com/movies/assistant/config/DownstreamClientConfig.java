package com.movies.assistant.config;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP clients for the assistant's tools. Both start from the Spring-managed
 * {@link RestClient.Builder}, so each tool call carries trace context and shows up in Jaeger under
 * the chat request that triggered it (see docs/adr/0008).
 */
@Configuration
@EnableConfigurationProperties({DownstreamProperties.class, AssistantProperties.class})
public class DownstreamClientConfig {

    @Bean
    public RestClient searchRestClient(RestClient.Builder builder, DownstreamProperties properties) {
        return builder.clone()
                .baseUrl(properties.searchServiceUrl())
                .requestFactory(requestFactory(properties))
                .build();
    }

    @Bean
    public RestClient recommendationRestClient(RestClient.Builder builder, DownstreamProperties properties) {
        return builder.clone()
                .baseUrl(properties.recommendationServiceUrl())
                .requestFactory(requestFactory(properties))
                .build();
    }

    private static SimpleClientHttpRequestFactory requestFactory(DownstreamProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(properties.connectTimeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));
        return requestFactory;
    }
}
