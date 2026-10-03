package com.movies.catalog.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.result.UpdateResult;
import com.movies.catalog.dto.CreateMovieRequest;
import com.movies.catalog.dto.DeleteResponse;
import com.movies.catalog.dto.MovieFilterQuery;
import com.movies.catalog.dto.UpdateMovieRequest;
import com.movies.catalog.event.MovieEventPublisher;
import com.movies.catalog.exception.DatabaseOperationException;
import com.movies.catalog.exception.ResourceNotFoundException;
import com.movies.catalog.exception.ValidationException;
import com.movies.catalog.model.Movie;
import com.movies.catalog.repository.MovieRepository;
import com.movies.catalog.service.IMovieService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Service;

/**
 * Service layer for movie business logic using Spring Data MongoDB. Mongo calls run through
 * mongoCircuitBreaker (see MongoCircuitBreakerConfig) so a database outage fails fast instead
 * of blocking every request for MongoConfig's serverSelectionTimeout.
 */
@Service
public class MovieServiceImpl implements IMovieService {

    private static final Logger logger = LoggerFactory.getLogger(MovieServiceImpl.class);

    private final MovieRepository movieRepository;
    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper;
    private final MovieEventPublisher eventPublisher;
    private final CircuitBreaker mongoCircuitBreaker;

    public MovieServiceImpl(
            MovieRepository movieRepository,
            MongoTemplate mongoTemplate,
            ObjectMapper objectMapper,
            MovieEventPublisher eventPublisher,
            CircuitBreaker mongoCircuitBreaker) {
        this.movieRepository = movieRepository;
        this.mongoTemplate = mongoTemplate;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.mongoCircuitBreaker = mongoCircuitBreaker;
    }

    @Override
    public Page<Movie> getAllMovies(MovieFilterQuery query, Pageable pageable) {
        return mongoCircuitBreaker.executeSupplier(() -> {
            Query mongoQuery = buildQuery(query).with(pageable);

            // Read raw documents and convert one at a time (rather than mongoTemplate.find(..., Movie.class),
            // which maps the whole page in one pass) so a single malformed document — e.g. a legacy
            // sample_mflix record with a corrupted string "year" like "1986è" instead of an int — doesn't
            // fail the entire page. We skip and log just that document instead.
            List<Document> rawDocuments = mongoTemplate.find(
                    mongoQuery, Document.class, mongoTemplate.getCollectionName(Movie.class));

            List<Movie> movies = new ArrayList<>(rawDocuments.size());
            for (Document doc : rawDocuments) {
                try {
                    movies.add(mongoTemplate.getConverter().read(Movie.class, doc));
                } catch (RuntimeException e) {
                    logger.warn("Skipping malformed movie document {} — failed to map to Movie: {}",
                            doc.get(Movie.Fields.ID), e.getMessage());
                }
            }

            // Count query built fresh (filter criteria only, no skip/limit/sort) rather than reusing
            // mongoQuery — MongoTemplate.count() doesn't expect a paginated Query.
            return PageableExecutionUtils.getPage(
                    movies, pageable, () -> mongoTemplate.count(buildQuery(query), Movie.class));
        });
    }

    @Override
    public Movie getMovieById(String id) {
        if (!ObjectId.isValid(id)) {
            throw new ValidationException("Invalid movie ID format");
        }

        return mongoCircuitBreaker.executeSupplier(() -> movieRepository.findById(new ObjectId(id))
                .orElseThrow(() -> new ResourceNotFoundException("Movie not found")));
    }

    @Override
    public Movie createMovie(CreateMovieRequest request) {
        if (request.title() == null || request.title().trim().isEmpty()) {
            throw new ValidationException("Title is required");
        }

        Movie movie = Movie.builder()
                .title(request.title())
                .year(request.year())
                .plot(request.plot())
                .fullplot(request.fullplot())
                .genres(request.genres())
                .directors(request.directors())
                .writers(request.writers())
                .cast(request.cast())
                .countries(request.countries())
                .languages(request.languages())
                .rated(request.rated())
                .runtime(request.runtime())
                .poster(request.poster())
                .build();

        // Spring Data MongoDB's save() method inserts or updates
        Movie savedMovie = mongoCircuitBreaker.executeSupplier(() -> movieRepository.save(movie));
        eventPublisher.publishCreated(savedMovie);
        return savedMovie;
    }

    @Override
    public Movie updateMovie(String id, UpdateMovieRequest request) {
        if (!ObjectId.isValid(id)) {
            throw new ValidationException("Invalid movie ID format");
        }

        if (request == null || isUpdateRequestEmpty(request)) {
            throw new ValidationException("No update data provided");
        }

        ObjectId objectId = new ObjectId(id);

        // Build Spring Data MongoDB Update object
        Update update = buildUpdate(request);

        Movie updatedMovie = mongoCircuitBreaker.executeSupplier(() -> {
            Query query = new Query(Criteria.where("_id").is(objectId));
            UpdateResult result = mongoTemplate.updateFirst(query, update, Movie.class);

            if (result.getMatchedCount() == 0) {
                throw new ResourceNotFoundException("Movie not found");
            }

            return movieRepository.findById(objectId)
                    .orElseThrow(() -> new DatabaseOperationException("Failed to retrieve updated movie"));
        });
        eventPublisher.publishUpdated(updatedMovie);
        return updatedMovie;
    }

    @Override
    public DeleteResponse deleteMovie(String id) {
        if (!ObjectId.isValid(id)) {
            throw new ValidationException("Invalid movie ID format");
        }

        ObjectId objectId = new ObjectId(id);

        mongoCircuitBreaker.executeRunnable(() -> {
            if (!movieRepository.existsById(objectId)) {
                throw new ResourceNotFoundException("Movie not found");
            }
            movieRepository.deleteById(objectId);
        });
        eventPublisher.publishDeleted(id);

        return new DeleteResponse(1L);
    }

    /**
     * Builds a Spring Data MongoDB Query from the filter parameters.
     */
    private Query buildQuery(MovieFilterQuery query) {
        Query mongoQuery = new Query();

        // Genre filter — exact match against the genres array (sample_mflix genres are
        // consistently capitalized, e.g. "Action", "Drama"). Exact match rather than regex:
        // this endpoint is exact-match filtering only (search-service owns relevance-ranked
        // text search), and it's the form that can actually use an index on `genres`.
        if (query.genre() != null && !query.genre().trim().isEmpty()) {
            mongoQuery.addCriteria(Criteria.where(Movie.Fields.GENRES).is(query.genre().trim()));
        }

        // Year filter
        if (query.year() != null) {
            mongoQuery.addCriteria(Criteria.where(Movie.Fields.YEAR).is(query.year()));
        }

        // Rating range filter
        if (query.minRating() != null || query.maxRating() != null) {
            Criteria ratingCriteria = Criteria.where(Movie.Fields.IMDB_RATING);
            if (query.minRating() != null) {
                ratingCriteria = ratingCriteria.gte(query.minRating());
            }
            if (query.maxRating() != null) {
                ratingCriteria = ratingCriteria.lte(query.maxRating());
            }
            mongoQuery.addCriteria(ratingCriteria);
        }

        return mongoQuery;
    }

    /**
     * Checks if the update request has any non-null fields.
     */
    private boolean isUpdateRequestEmpty(UpdateMovieRequest request) {
        @SuppressWarnings("unchecked")
        Map<String, Object> requestMap = objectMapper.convertValue(request, Map.class);
        return requestMap.values().stream().allMatch(java.util.Objects::isNull);
    }

    /**
     * Builds a Spring Data MongoDB Update object from the update request.
     */
    private Update buildUpdate(UpdateMovieRequest request) {
        @SuppressWarnings("unchecked")
        Map<String, Object> requestMap = objectMapper.convertValue(request, Map.class);

        Update update = new Update();
        requestMap.forEach((key, value) -> {
            if (value != null) {
                update.set(key, value);
            }
        });

        return update;
    }
}
