package com.movies.recommendation.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** {@code recommendation.fallback}: recommendations served without one of their signals. */
public final class FallbackMetrics {

    public static final String COLD_START = "cold_start";
    public static final String NO_CANDIDATES = "no_candidates";
    public static final String SEARCH_UNAVAILABLE = "search_unavailable";

    private FallbackMetrics() {
    }

    /** Registered up front, so each reason shows as 0 on a dashboard rather than no data. */
    public static Counter counter(MeterRegistry meterRegistry, String reason) {
        return Counter.builder("recommendation.fallback")
                .description("Recommendations served without one of their signals, by reason")
                .tag("reason", reason)
                .register(meterRegistry);
    }
}
