package com.movies.assistant.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The tool clients time out after assistant.downstream.read-timeout-ms, applied through Boot's
 * HTTP client settings. The server accepts connections but never answers.
 */
@DisplayName("RestClient timeouts")
class RestClientTimeoutTest {

    private ServerSocket silentServer;
    private ApplicationContextRunner runner;

    @BeforeEach
    void setUp() throws Exception {
        silentServer = new ServerSocket(0);
        String url = "http://localhost:" + silentServer.getLocalPort();
        runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class,
                        ImperativeHttpClientAutoConfiguration.class, RestClientAutoConfiguration.class))
                .withUserConfiguration(DownstreamClientConfig.class)
                .withPropertyValues(
                        "spring.http.clients.imperative.factory=jdk",
                        "assistant.downstream.search-service-url=" + url,
                        "assistant.downstream.recommendation-service-url=" + url,
                        "assistant.downstream.connect-timeout-ms=1000",
                        "assistant.downstream.read-timeout-ms=300");
    }

    @AfterEach
    void tearDown() throws Exception {
        silentServer.close();
    }

    @Test
    @DisplayName("both tool clients time out after the configured read timeout")
    void toolClientsTimeOut() {
        runner.run(context -> {
            assertThat(timeToFail(context.getBean("searchRestClient", RestClient.class)))
                    .isBetween(Duration.ofMillis(250), Duration.ofMillis(1200));
            assertThat(timeToFail(context.getBean("recommendationRestClient", RestClient.class)))
                    .isBetween(Duration.ofMillis(250), Duration.ofMillis(1200));
        });
    }

    private static Duration timeToFail(RestClient client) {
        long start = System.nanoTime();
        assertThatThrownBy(() -> client.get().uri("/").retrieve().toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class);
        return Duration.ofNanos(System.nanoTime() - start);
    }
}
