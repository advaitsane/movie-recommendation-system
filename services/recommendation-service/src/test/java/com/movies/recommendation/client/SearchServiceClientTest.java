package com.movies.recommendation.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServiceUnavailable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.movies.recommendation.config.SearchServiceProperties;
import com.movies.recommendation.dto.SearchSimilarResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

@DisplayName("SearchServiceClient Unit Tests")
class SearchServiceClientTest {

    private static final long DEADLINE_MS = 300;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private MockRestServiceServer server;
    private SearchServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://search");
        // The calls run in parallel, so they can arrive in any order.
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        client = new SearchServiceClient(builder.build(),
                new SearchServiceProperties("http://search", 2000, 3000, DEADLINE_MS), meterRegistry);
    }

    @AfterEach
    void tearDown() {
        client.shutdown();
    }

    @Test
    @DisplayName("search-service failing: empty list, counted as search_unavailable")
    void serverErrorCountsAsFallback() {
        server.expect(requestTo(similarUrl("m1"))).andRespond(withServiceUnavailable());

        assertThat(client.findSimilarToAny(List.of("m1"), 20)).isEmpty();
        assertThat(searchUnavailable()).isEqualTo(1);
    }

    @Test
    @DisplayName("A 4xx (no vector for that movie yet) is an empty list, not a fallback")
    void clientErrorIsNotCounted() {
        server.expect(requestTo(similarUrl("m1"))).andRespond(withBadRequest());

        assertThat(client.findSimilarToAny(List.of("m1"), 20)).isEmpty();
        assertThat(searchUnavailable()).isZero();
    }

    @Test
    @DisplayName("Every movie's results are returned, and a failing one only drops its own")
    void oneFailureKeepsTheOthers() {
        server.expect(requestTo(similarUrl("m1"))).andRespond(similar("a1", "a2"));
        server.expect(requestTo(similarUrl("m2"))).andRespond(withServiceUnavailable());
        server.expect(requestTo(similarUrl("m3"))).andRespond(similar("c1"));

        assertThat(client.findSimilarToAny(List.of("m1", "m2", "m3"), 20))
                .extracting(result -> result.movie().id())
                .containsExactlyInAnyOrder("a1", "a2", "c1");
        assertThat(searchUnavailable()).isEqualTo(1);
    }

    @Test
    @DisplayName("The calls run in parallel: three slow ones take about one call's time")
    void callsRunInParallel() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://search");
        MockRestServiceServer slowServer = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        for (String id : List.of("m1", "m2", "m3")) {
            slowServer.expect(requestTo(similarUrl(id))).andRespond(after(500, similar(id + "-hit")));
        }
        SearchServiceClient patient = new SearchServiceClient(builder.build(),
                new SearchServiceProperties("http://search", 2000, 3000, 3000), meterRegistry);

        long started = System.nanoTime();
        List<SearchSimilarResult> results = patient.findSimilarToAny(List.of("m1", "m2", "m3"), 20);
        long tookMs = (System.nanoTime() - started) / 1_000_000;
        patient.shutdown();

        assertThat(results).hasSize(3);
        // In sequence they would take 1500ms.
        assertThat(tookMs).isLessThan(1200);
    }

    @Test
    @DisplayName("A call still running at the deadline is dropped and counted; the others are kept")
    void lateCallIsDropped() {
        server.expect(requestTo(similarUrl("m1"))).andRespond(similar("a1"));
        server.expect(requestTo(similarUrl("m2"))).andRespond(after(5000, similar("late")));

        long started = System.nanoTime();
        List<SearchSimilarResult> results = client.findSimilarToAny(List.of("m1", "m2"), 20);
        long tookMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(results).extracting(result -> result.movie().id()).containsExactly("a1");
        assertThat(tookMs).isBetween(DEADLINE_MS, DEADLINE_MS + 1500);
        assertThat(searchUnavailable()).isEqualTo(1);
    }

    private static String similarUrl(String id) {
        return "http://search/api/movies/search/" + id + "/similar?limit=20";
    }

    private static ResponseCreator similar(String... ids) {
        StringBuilder json = new StringBuilder("[");
        for (String id : ids) {
            json.append(json.length() > 1 ? "," : "")
                    .append("{\"movie\":{\"_id\":\"").append(id).append("\",\"title\":\"T\"},\"score\":0.9}");
        }
        return withSuccess(json.append("]").toString(), MediaType.APPLICATION_JSON);
    }

    private static ResponseCreator after(long ms, ResponseCreator response) {
        return request -> {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return response.createResponse(request);
        };
    }

    private double searchUnavailable() {
        return meterRegistry.get("recommendation.fallback").tag("reason", "search_unavailable").counter().count();
    }
}
