package com.movies.recommendation.client;

import com.movies.recommendation.dto.SearchSimilarResult;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Wraps the one synchronous, on-request-path call to another service in this repo: fetching
 * content-based similar movies from search-service. Every failure mode (timeout, connection
 * refused, 4xx/5xx, a movie with no embedding yet) is caught and turned into an empty list,
 * since this signal is "nice to have" and its absence should never surface as an error.
 */
@Component
public class SearchServiceClient {

    private static final Logger logger = LoggerFactory.getLogger(SearchServiceClient.class);

    private final RestClient searchRestClient;

    public SearchServiceClient(RestClient searchRestClient) {
        this.searchRestClient = searchRestClient;
    }

    public List<SearchSimilarResult> findSimilar(String movieId, int limit) {
        try {
            SearchSimilarResult[] results = searchRestClient.get()
                    .uri("/api/movies/search/{id}/similar?limit={limit}", movieId, limit)
                    .retrieve()
                    .body(SearchSimilarResult[].class);
            return results != null ? List.of(results) : List.of();
        } catch (Exception ex) {
            logger.warn("search-service call failed for findSimilar(movieId={}) — degrading to no "
                    + "content-based signal: {}", movieId, ex.getMessage());
            return List.of();
        }
    }
}
