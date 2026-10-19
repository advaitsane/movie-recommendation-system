package com.movies.recommendation.client;

import com.movies.recommendation.dto.SearchSimilarResult;
import com.movies.recommendation.metrics.FallbackMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Wraps the one synchronous, on-request-path call to another service in this repo: fetching
 * content-based similar movies from search-service. Every failure mode (timeout, connection
 * refused, 4xx/5xx, a movie with no embedding yet) is caught and turned into an empty list,
 * since this signal is "nice to have" and its absence should never surface as an error. Only an
 * unreachable, slow or failing search-service counts as a fallback; a 4xx means it's up but has
 * no vector for that movie.
 */
@Component
public class SearchServiceClient {

    private static final Logger logger = LoggerFactory.getLogger(SearchServiceClient.class);

    private final RestClient searchRestClient;
    private final Counter searchUnavailable;

    public SearchServiceClient(@Qualifier("searchRestClient") RestClient searchRestClient, MeterRegistry meterRegistry) {
        this.searchRestClient = searchRestClient;
        this.searchUnavailable = FallbackMetrics.counter(meterRegistry, FallbackMetrics.SEARCH_UNAVAILABLE);
    }

    public List<SearchSimilarResult> findSimilar(String movieId, int limit) {
        try {
            SearchSimilarResult[] results = searchRestClient.get()
                    .uri("/api/movies/search/{id}/similar?limit={limit}", movieId, limit)
                    .retrieve()
                    .body(SearchSimilarResult[].class);
            return results != null ? List.of(results) : List.of();
        } catch (Exception ex) {
            if (!(ex instanceof HttpClientErrorException)) {
                searchUnavailable.increment();
            }
            logger.warn("search-service call failed for findSimilar(movieId={}) — degrading to no "
                    + "content-based signal: {}", movieId, ex.getMessage());
            return List.of();
        }
    }
}
