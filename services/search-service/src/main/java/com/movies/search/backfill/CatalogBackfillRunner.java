package com.movies.search.backfill;

import com.movies.search.embedding.EmbeddingService;
import com.movies.search.embedding.EmbeddingTextBuilder;
import com.movies.search.event.MovieEventPayload;
import com.movies.search.model.MovieSearchDocument;
import com.movies.search.repository.MovieSearchRepository;
import java.util.List;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * One-time startup sync from catalog-service's REST API into movies_search when it's empty, so
 * search-service doesn't wait for Kafka's full retained history to replay. Runs after the Kafka
 * listener subscribes, so a mid-backfill event is at worst double-applied, never missed (both
 * paths upsert by id). Non-blocking: a failure is logged, not thrown, so the service still
 * starts and keeps consuming Kafka.
 */
@Component
public class CatalogBackfillRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(CatalogBackfillRunner.class);
    private static final int PAGE_SIZE = 100;

    private final MovieSearchRepository repository;
    private final RestClient catalogRestClient;
    private final EmbeddingService embeddingService;

    public CatalogBackfillRunner(
            MovieSearchRepository repository,
            RestClient catalogRestClient,
            EmbeddingService embeddingService) {
        this.repository = repository;
        this.catalogRestClient = catalogRestClient;
        this.embeddingService = embeddingService;
    }

    @Override
    public void run(ApplicationArguments args) {
        long existingCount = repository.count();
        if (existingCount > 0) {
            logger.info("movies_search already has {} documents — skipping catalog-service backfill", existingCount);
            return;
        }

        logger.info("movies_search is empty — backfilling from catalog-service...");
        try {
            int skip = 0;
            int totalIndexed = 0;
            List<MovieEventPayload> page;

            do {
                page = fetchPage(skip);
                List<String> embeddingTexts = page.stream().map(EmbeddingTextBuilder::forPayload).toList();
                List<List<Double>> embeddings = embeddingService.embedDocuments(embeddingTexts);
                for (int i = 0; i < page.size(); i++) {
                    save(page.get(i), embeddings.get(i));
                    totalIndexed++;
                }
                skip += PAGE_SIZE;
            } while (page.size() == PAGE_SIZE);

            logger.info("Backfill complete: indexed {} movies from catalog-service", totalIndexed);
        } catch (Exception e) {
            logger.error(
                    "Catalog-service backfill failed: {}. search-service will keep consuming the Kafka " +
                    "stream, but pre-existing catalog data won't be indexed until this succeeds.",
                    e.getMessage(), e);
        }
    }

    private List<MovieEventPayload> fetchPage(int skip) {
        List<MovieEventPayload> page = catalogRestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/movies")
                        .queryParam("limit", PAGE_SIZE)
                        .queryParam("skip", skip)
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<List<MovieEventPayload>>() {
                });
        return page != null ? page : List.of();
    }

    private void save(MovieEventPayload movie, List<Double> embedding) {
        if (movie.id() == null || !ObjectId.isValid(movie.id())) {
            logger.warn("Skipping catalog movie with missing/invalid id during backfill: {}", movie.title());
            return;
        }

        MovieEventPayload.Imdb imdb = movie.imdb();

        MovieSearchDocument document = MovieSearchDocument.builder()
                .id(new ObjectId(movie.id()))
                .title(movie.title())
                .year(movie.year())
                .plot(movie.plot())
                .fullplot(movie.fullplot())
                .poster(movie.poster())
                .genres(movie.genres())
                .directors(movie.directors())
                .writers(movie.writers())
                .cast(movie.cast())
                .countries(movie.countries())
                .languages(movie.languages())
                .rated(movie.rated())
                .imdbRating(imdb != null ? imdb.rating() : null)
                .imdbVotes(imdb != null ? imdb.votes() : null)
                .metacritic(movie.metacritic())
                .type(movie.type())
                .plotEmbedding(embedding)
                .build();

        repository.save(document);
    }
}
