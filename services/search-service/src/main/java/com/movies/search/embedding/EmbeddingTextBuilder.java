package com.movies.search.embedding;

import com.movies.search.event.MovieEventPayload;

/**
 * Builds the text handed to Voyage AI for embedding a movie. Shared by {@code
 * CatalogBackfillRunner} and {@code MovieEventConsumer} so a movie embeds identically
 * regardless of which path (backfill vs. Kafka event) produced it.
 */
public final class EmbeddingTextBuilder {

    private EmbeddingTextBuilder() {
    }

    /**
     * Title plus the richest available plot text (fullplot, falling back to plot) — this is
     * what "similar movies" and free-text vector search ultimately compare against.
     */
    public static String forMovie(String title, String plot, String fullplot) {
        String body = fullplot != null && !fullplot.isBlank() ? fullplot : plot;
        StringBuilder text = new StringBuilder();
        if (title != null && !title.isBlank()) {
            text.append(title);
        }
        if (body != null && !body.isBlank()) {
            if (!text.isEmpty()) {
                text.append(". ");
            }
            text.append(body);
        }
        return text.toString();
    }

    public static String forPayload(MovieEventPayload movie) {
        return forMovie(movie.title(), movie.plot(), movie.fullplot());
    }
}
