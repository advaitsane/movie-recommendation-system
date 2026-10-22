package com.movies.recommendation.client;

import com.movies.recommendation.config.SearchServiceProperties;
import com.movies.recommendation.dto.SearchSimilarResult;
import com.movies.recommendation.metrics.FallbackMetrics;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    private final long deadlineMs;
    private final Counter searchUnavailable;
    // Wrapped so each call carries the request's trace context onto its own thread.
    private final ExecutorService executor = ContextExecutorService.wrap(
            Executors.newVirtualThreadPerTaskExecutor(), ContextSnapshotFactory.builder().build());

    public SearchServiceClient(@Qualifier("searchRestClient") RestClient searchRestClient,
                               SearchServiceProperties properties, MeterRegistry meterRegistry) {
        this.searchRestClient = searchRestClient;
        this.deadlineMs = properties.deadlineMs();
        this.searchUnavailable = FallbackMetrics.counter(meterRegistry, FallbackMetrics.SEARCH_UNAVAILABLE);
    }

    /**
     * Similar movies for each of {@code movieIds}, all fetched at once and bounded by one
     * deadline, so a slow search-service costs the deadline rather than one read timeout per
     * movie (ADR-0010 Update 11). A call still running at the deadline is cancelled and adds
     * nothing, as if it had failed.
     */
    public List<SearchSimilarResult> findSimilarToAny(List<String> movieIds, int limit) {
        List<Future<List<SearchSimilarResult>>> calls = movieIds.stream()
                .map(id -> executor.submit(() -> fetch(id, limit)))
                .toList();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMs);
        List<SearchSimilarResult> results = new ArrayList<>();
        int late = 0;
        for (int i = 0; i < calls.size(); i++) {
            Future<List<SearchSimilarResult>> call = calls.get(i);
            try {
                results.addAll(call.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            } catch (TimeoutException ex) {
                // Whatever the cancelled call throws next goes nowhere, so it's counted once, here.
                call.cancel(true);
                late++;
            } catch (ExecutionException ex) {
                failed(movieIds.get(i), ex.getCause());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                calls.forEach(c -> c.cancel(true));
                break;
            }
        }
        if (late > 0) {
            searchUnavailable.increment(late);
            logger.warn("{} of {} search-service calls missed the {}ms deadline — the content-based "
                    + "signal uses only the ones that answered", late, calls.size(), deadlineMs);
        }
        return results;
    }

    private List<SearchSimilarResult> fetch(String movieId, int limit) {
        SearchSimilarResult[] results = searchRestClient.get()
                .uri("/api/movies/search/{id}/similar?limit={limit}", movieId, limit)
                .retrieve()
                .body(SearchSimilarResult[].class);
        return results != null ? List.of(results) : List.of();
    }

    private void failed(String movieId, Throwable ex) {
        if (!(ex instanceof HttpClientErrorException)) {
            searchUnavailable.increment();
        }
        logger.warn("search-service call failed for findSimilar(movieId={}) — that movie adds no "
                + "content-based signal: {}", movieId, ex.getMessage());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
