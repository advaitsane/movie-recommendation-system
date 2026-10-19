package com.movies.recommendation.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServiceUnavailable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("SearchServiceClient Unit Tests")
class SearchServiceClientTest {

    private static final String SIMILAR_URL = "http://search/api/movies/search/m1/similar?limit=20";

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private MockRestServiceServer server;
    private SearchServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://search");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SearchServiceClient(builder.build(), meterRegistry);
    }

    @Test
    @DisplayName("search-service failing: empty list, counted as search_unavailable")
    void serverErrorCountsAsFallback() {
        server.expect(requestTo(SIMILAR_URL)).andRespond(withServiceUnavailable());

        assertThat(client.findSimilar("m1", 20)).isEmpty();
        assertThat(searchUnavailable()).isEqualTo(1);
    }

    @Test
    @DisplayName("A 4xx (no vector for that movie yet) is an empty list, not a fallback")
    void clientErrorIsNotCounted() {
        server.expect(requestTo(SIMILAR_URL)).andRespond(withBadRequest());

        assertThat(client.findSimilar("m1", 20)).isEmpty();
        assertThat(searchUnavailable()).isZero();
    }

    @Test
    @DisplayName("A normal answer isn't counted")
    void successIsNotCounted() {
        server.expect(requestTo(SIMILAR_URL)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.findSimilar("m1", 20)).isEmpty();
        assertThat(searchUnavailable()).isZero();
    }

    private double searchUnavailable() {
        return meterRegistry.get("recommendation.fallback").tag("reason", "search_unavailable").counter().count();
    }
}
