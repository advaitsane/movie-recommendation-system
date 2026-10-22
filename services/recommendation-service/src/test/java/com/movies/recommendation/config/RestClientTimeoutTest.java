package com.movies.recommendation.config;

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
 * Each client gets its own read timeout through Boot's HTTP client settings. The server accepts
 * connections but never answers, so only the read timeout can end the call.
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
                .withUserConfiguration(SearchClientConfig.class, CatalogClientConfig.class)
                .withPropertyValues(
                        "search.service.url=" + url,
                        "search.service.connect-timeout-ms=1000",
                        "search.service.read-timeout-ms=300",
                        "search.service.deadline-ms=3000",
                        "catalog.service.url=" + url,
                        "catalog.service.connect-timeout-ms=1000",
                        "catalog.service.read-timeout-ms=1500");
    }

    @AfterEach
    void tearDown() throws Exception {
        silentServer.close();
    }

    @Test
    @DisplayName("searchRestClient and catalogRestClient each time out after their own read timeout")
    void eachClientUsesItsOwnReadTimeout() {
        runner.run(context -> {
            assertThat(timeToFail(context.getBean("searchRestClient", RestClient.class)))
                    .isBetween(Duration.ofMillis(250), Duration.ofMillis(1200));
            assertThat(timeToFail(context.getBean("catalogRestClient", RestClient.class)))
                    .isBetween(Duration.ofMillis(1400), Duration.ofMillis(3000));
        });
    }

    private static Duration timeToFail(RestClient client) {
        long start = System.nanoTime();
        assertThatThrownBy(() -> client.get().uri("/").retrieve().toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class);
        return Duration.ofNanos(System.nanoTime() - start);
    }
}
