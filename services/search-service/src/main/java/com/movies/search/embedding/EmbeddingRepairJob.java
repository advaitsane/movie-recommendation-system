package com.movies.search.embedding;

import com.movies.search.backfill.CatalogBackfillRunner;
import com.movies.search.model.MovieSearchDocument;
import com.movies.search.repository.BackfillStateRepository;
import com.movies.search.repository.MovieSearchRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fills in plotEmbedding for movies that were saved without one. Embedding fails soft (no API
 * key, an outage, a 429), and both the backfill and the Kafka consumer then save the movie with
 * a null vector, which nothing else ever revisits. Each run sets only plotEmbedding, so fields
 * Kafka updated aren't touched, and a rerun is harmless.
 *
 * Waits for the backfill's marker, so the two never embed at the same time; with the backfill
 * turned off, the repair never runs either. Stops a run early when a whole batch fails, rather
 * than retrying a provider that's down; the next run tries again.
 * {@code embedding.repair.enabled=false} turns it off.
 */
@Component
@ConditionalOnProperty(prefix = "embedding.repair", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EmbeddingRepairJob {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingRepairJob.class);

    private static final ObjectId FIRST_ID = new ObjectId("000000000000000000000000");

    private final MovieSearchRepository repository;
    private final BackfillStateRepository stateRepository;
    private final EmbeddingService embeddingService;
    private final AtomicLong withoutEmbedding = new AtomicLong();

    public EmbeddingRepairJob(
            MovieSearchRepository repository,
            BackfillStateRepository stateRepository,
            EmbeddingService embeddingService,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.stateRepository = stateRepository;
        this.embeddingService = embeddingService;
        Gauge.builder("search.movies.without.embedding", withoutEmbedding, AtomicLong::doubleValue)
                .description("Movies in movies_search that vector search can't find, as of the last repair run")
                .register(meterRegistry);
    }

    @Scheduled(initialDelayString = "${embedding.repair.initial-delay:PT1M}",
            fixedDelayString = "${embedding.repair.interval:PT15M}")
    public void repair() {
        if (!stateRepository.existsById(CatalogBackfillRunner.STATE_ID)) {
            logger.debug("Catalog backfill hasn't completed yet — skipping embedding repair");
            return;
        }
        try {
            long missing = repository.countByPlotEmbeddingIsNull();
            withoutEmbedding.set(missing);
            if (missing == 0) {
                return;
            }
            if (!embeddingService.isEnabled()) {
                logger.warn("{} movies have no embedding, and the embedding provider is disabled", missing);
                return;
            }
            logger.info("{} movies have no embedding — repairing...", missing);
            long repaired = repairAll();
            withoutEmbedding.set(repository.countByPlotEmbeddingIsNull());
            logger.info("Embedding repair done: {} repaired, {} still without an embedding",
                    repaired, withoutEmbedding.get());
        } catch (Exception e) {
            logger.error("Embedding repair failed: {}. The next run will try again.", e.getMessage(), e);
        }
    }

    private long repairAll() {
        long repaired = 0;
        ObjectId after = FIRST_ID;
        List<MovieSearchDocument> batch;
        while (!(batch = repository.findTop100ByPlotEmbeddingIsNullAndIdGreaterThanOrderByIdAsc(after)).isEmpty()) {
            after = batch.getLast().getId();

            // A movie with no title or plot has nothing to embed; it stays in the gauge.
            List<MovieSearchDocument> embeddable = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            for (MovieSearchDocument document : batch) {
                String text = EmbeddingTextBuilder.forMovie(
                        document.getTitle(), document.getPlot(), document.getFullplot());
                if (!text.isBlank()) {
                    embeddable.add(document);
                    texts.add(text);
                }
            }
            if (texts.isEmpty()) {
                continue;
            }

            List<List<Double>> embeddings = embeddingService.embedDocuments(texts);
            int embedded = 0;
            for (int i = 0; i < embeddable.size(); i++) {
                List<Double> embedding = embeddings.get(i);
                if (embedding == null) {
                    continue;
                }
                embedded++;
                if (repository.setEmbeddingIfUnchanged(embeddable.get(i), embedding)) {
                    repaired++;
                }
            }
            if (embedded == 0) {
                logger.warn("No embeddings returned for a batch of {} — stopping until the next run", texts.size());
                break;
            }
        }
        return repaired;
    }
}
