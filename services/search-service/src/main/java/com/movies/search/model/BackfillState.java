package com.movies.search.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Marks a startup backfill as finished. Written only after the last page is stored, so a backfill
 * that crashed part-way leaves no marker and runs again on the next start (see docs/adr/0002).
 */
@Document(collection = "backfill_state")
public record BackfillState(@Id String id, Instant completedAt, long movies) {
}
