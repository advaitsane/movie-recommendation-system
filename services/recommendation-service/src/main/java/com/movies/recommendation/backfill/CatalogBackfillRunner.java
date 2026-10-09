package com.movies.recommendation.backfill;

import com.movies.recommendation.event.MovieMetadataPayload;
import com.movies.recommendation.model.MovieMetadata;
import com.movies.recommendation.repository.MovieMetadataRepository;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Startup sync of movie_metadata from catalog-service's REST API. Without it, this service only
 * knows movies created through catalog-service since it started: the movies seeded from
 * sample_mflix never produced a {@code movie.*} event, so they were never recommended (see
 * docs/adr/0005's "Known gap").
 *
 * <p>Unlike search-service's backfill, this runs whenever movie_metadata holds fewer movies than
 * catalog-service reports, not only when it's empty: a running stack usually already has a few
 * event-created movies here. Each movie is written with {@code $setOnInsert}, so a document the
 * Kafka consumer has already written (or writes concurrently) is never overwritten by this older
 * snapshot. A backfilled document carries no {@code lastEventAt}, so any later event still
 * applies to it.
 *
 * <p>Runs on its own virtual thread and retries the first page while catalog-service starts, so
 * neither a slow catalog-service nor a 20,000-movie scan holds up startup. Failures are logged,
 * not thrown; the next restart tries again. Known gap: a movie deleted while the scan is running
 * can be re-inserted from an already-fetched page.
 */
@Component
@ConditionalOnProperty(prefix = "catalog.backfill", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CatalogBackfillRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(CatalogBackfillRunner.class);

    /** catalog-service caps {@code size} at 100. */
    static final int PAGE_SIZE = 100;
    private static final int FIRST_PAGE_ATTEMPTS = 10;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(6);

    private final MovieMetadataRepository repository;
    private final MongoTemplate mongoTemplate;
    private final RestClient catalogRestClient;

    public CatalogBackfillRunner(
            MovieMetadataRepository repository,
            MongoTemplate mongoTemplate,
            RestClient catalogRestClient) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.catalogRestClient = catalogRestClient;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread.ofVirtual().name("catalog-backfill").start(this::backfill);
    }

    void backfill() {
        try {
            CatalogPage first = fetchFirstPageWithRetry();
            if (first == null) {
                return;
            }

            long catalogTotal = first.page().totalElements();
            long existingCount = repository.count();
            if (existingCount >= catalogTotal) {
                logger.info("movie_metadata already has {} of catalog-service's {} movies — skipping backfill",
                        existingCount, catalogTotal);
                return;
            }

            logger.info("movie_metadata has {} of catalog-service's {} movies — backfilling...",
                    existingCount, catalogTotal);
            int inserted = insertMissing(first.content());
            for (int number = 1; number < first.page().totalPages(); number++) {
                inserted += insertMissing(fetchPage(number).content());
            }
            logger.info("Backfill complete: inserted {} movies from catalog-service", inserted);
        } catch (Exception e) {
            logger.error(
                    "Catalog-service backfill failed: {}. Kafka sync continues, but movies missing from " +
                    "movie_metadata won't be recommended until a restart's backfill succeeds.",
                    e.getMessage(), e);
        }
    }

    private CatalogPage fetchFirstPageWithRetry() throws InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                return fetchPage(0);
            } catch (Exception e) {
                if (attempt == FIRST_PAGE_ATTEMPTS) {
                    logger.error("catalog-service unreachable after {} attempts ({}) — skipping backfill",
                            attempt, e.getMessage());
                    return null;
                }
                logger.info("catalog-service not reachable yet (attempt {}/{}): {} — retrying in {}s",
                        attempt, FIRST_PAGE_ATTEMPTS, e.getMessage(), RETRY_DELAY.toSeconds());
                Thread.sleep(RETRY_DELAY);
            }
        }
    }

    private CatalogPage fetchPage(int number) {
        CatalogPage page = catalogRestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/movies")
                        .queryParam("page", number)
                        .queryParam("size", PAGE_SIZE)
                        // A stable order, so pages neither overlap nor skip movies. "_id", not "id":
                        // catalog-service sorts raw Documents, so an entity property name isn't
                        // mapped to _id and "id" would sort on a field that doesn't exist.
                        .queryParam("sort", "_id")
                        .build())
                .retrieve()
                .body(CatalogPage.class);
        if (page == null || page.page() == null) {
            throw new IllegalStateException("catalog-service returned an empty body for page " + number);
        }
        return page.content() != null ? page : new CatalogPage(List.of(), page.page());
    }

    /** Inserts the movies not already in movie_metadata and returns how many were inserted. */
    private int insertMissing(List<MovieMetadataPayload> movies) {
        BulkOperations bulk = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, MovieMetadata.class);
        int queued = 0;
        for (MovieMetadataPayload movie : movies) {
            if (movie.id() == null || movie.id().isBlank()) {
                logger.warn("Skipping catalog movie with no id during backfill: {}", movie.title());
                continue;
            }
            bulk.upsert(Query.query(Criteria.where("id").is(movie.id())), setOnInsert(movie));
            queued++;
        }
        return queued == 0 ? 0 : bulk.execute().getUpserts().size();
    }

    private static Update setOnInsert(MovieMetadataPayload movie) {
        Update update = new Update();
        if (movie.title() != null) {
            update.setOnInsert("title", movie.title());
        }
        if (movie.year() != null) {
            update.setOnInsert("year", movie.year());
        }
        if (movie.poster() != null) {
            update.setOnInsert("poster", movie.poster());
        }
        if (movie.genres() != null) {
            update.setOnInsert("genres", movie.genres());
        }
        return update;
    }
}
