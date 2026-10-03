package com.movies.search.service.impl;

import static com.mongodb.client.model.search.SearchPath.fieldPath;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Field;
import com.mongodb.client.model.search.VectorSearchOptions;
import com.movies.search.config.VectorSearchProperties;
import com.movies.search.dto.MovieSearchQuery;
import com.movies.search.dto.MovieSearchResult;
import com.movies.search.dto.VectorSearchResult;
import com.movies.search.embedding.EmbeddingService;
import com.movies.search.exception.ResourceNotFoundException;
import com.movies.search.exception.ServiceUnavailableException;
import com.movies.search.exception.ValidationException;
import com.movies.search.model.MovieSearchDocument;
import com.movies.search.repository.MovieSearchRepository;
import com.movies.search.service.ISearchService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.TextCriteria;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Service;

/**
 * Query layer over search-service's own movies_search collection. {@link #searchMovies} mirrors
 * catalog-service's {@code getAllMovies} ({@code Page}/{@code PageableExecutionUtils}) since
 * it's an exact query with a well-defined total; {@link #vectorSearch}/{@link #findSimilar} run
 * ANN {@code $vectorSearch} instead and stay {@code limit}-only (see {@code ISearchService}).
 * Every Mongo call runs inside {@code mongoCircuitBreaker} so an outage fails fast.
 */
@Service
public class SearchServiceImpl implements ISearchService {

    private static final String COLLECTION = "movies_search";

    /**
     * Atlas Vector Search is ANN (approximate nearest neighbor): numCandidates controls how many
     * candidates mongot scans before ranking down to the requested limit — more candidates means
     * better recall at some latency cost. 10x the limit, floored at 100, is Atlas's own
     * documented rule of thumb.
     */
    private static final int MIN_NUM_CANDIDATES = 100;
    private static final int NUM_CANDIDATES_MULTIPLIER = 10;

    private final MovieSearchRepository repository;
    private final MongoTemplate mongoTemplate;
    private final EmbeddingService embeddingService;
    private final String vectorIndexName;
    private final CircuitBreaker mongoCircuitBreaker;

    public SearchServiceImpl(
            MovieSearchRepository repository,
            MongoTemplate mongoTemplate,
            EmbeddingService embeddingService,
            VectorSearchProperties vectorSearchProperties,
            CircuitBreaker mongoCircuitBreaker) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.embeddingService = embeddingService;
        this.vectorIndexName = vectorSearchProperties.indexName();
        this.mongoCircuitBreaker = mongoCircuitBreaker;
    }

    @Override
    public Page<MovieSearchDocument> searchMovies(MovieSearchQuery query, Pageable pageable) {
        return mongoCircuitBreaker.executeSupplier(() -> {
            Query mongoQuery = buildQuery(query).with(pageable);
            List<MovieSearchDocument> results = mongoTemplate.find(mongoQuery, MovieSearchDocument.class);

            // Count query built fresh (filter criteria only, no skip/limit/sort) rather than reusing
            // mongoQuery — MongoTemplate.count() doesn't expect a paginated Query. PageableExecutionUtils
            // skips this count entirely when the result page is smaller than the page size (no next page
            // possible), avoiding a redundant Mongo round trip on the common last-page/short-result case.
            return PageableExecutionUtils.getPage(
                    results, pageable, () -> mongoTemplate.count(buildQuery(query), MovieSearchDocument.class));
        });
    }

    @Override
    public MovieSearchDocument getById(String id) {
        if (!ObjectId.isValid(id)) {
            throw new ValidationException("Invalid movie ID format");
        }

        return mongoCircuitBreaker.executeSupplier(() -> repository.findById(new ObjectId(id)))
                .orElseThrow(() -> new ResourceNotFoundException("Movie not found in search index"));
    }

    @Override
    public List<VectorSearchResult> vectorSearch(String q, Integer limit) {
        if (q == null || q.trim().isEmpty()) {
            throw new ValidationException("Query text 'q' is required for vector search");
        }
        if (!embeddingService.isEnabled()) {
            throw new ServiceUnavailableException(
                    "Vector search is unavailable: no embedding provider API key is configured");
        }

        List<Double> queryVector = embeddingService.embedQuery(q)
                .orElseThrow(() -> new ServiceUnavailableException(
                        "Vector search is unavailable: failed to generate a query embedding"));

        int clampedLimit = Math.clamp(limit != null ? limit : 10, 1, 100);
        return runVectorSearch(queryVector, clampedLimit, null);
    }

    @Override
    public List<VectorSearchResult> findSimilar(String id, Integer limit) {
        if (!ObjectId.isValid(id)) {
            throw new ValidationException("Invalid movie ID format");
        }

        ObjectId objectId = new ObjectId(id);
        MovieSearchDocument document = mongoCircuitBreaker.executeSupplier(() -> repository.findById(objectId))
                .orElseThrow(() -> new ResourceNotFoundException("Movie not found in search index"));

        List<Double> plotEmbedding = document.getPlotEmbedding();
        if (plotEmbedding == null || plotEmbedding.isEmpty()) {
            throw new ValidationException(
                    "Movie " + id + " has no stored embedding yet (it may have been indexed before "
                            + "an embedding provider was configured) — find-similar isn't available for it");
        }

        int clampedLimit = Math.clamp(limit != null ? limit : 10, 1, 100);
        return runVectorSearch(plotEmbedding, clampedLimit, objectId);
    }

    /**
     * Runs {@code $vectorSearch} for {@code queryVector} and maps the top {@code limit} hits.
     * When {@code excludeId} is set (the "find similar to this movie" case, where the movie
     * itself is always its own nearest neighbor), fetches one extra candidate so excluding it
     * still leaves {@code limit} results.
     */
    private List<VectorSearchResult> runVectorSearch(List<Double> queryVector, int limit, ObjectId excludeId) {
        long numCandidates = Math.max((long) limit * NUM_CANDIDATES_MULTIPLIER, MIN_NUM_CANDIDATES);
        long fetchLimit = excludeId != null ? limit + 1L : limit;

        Bson vectorSearchStage = Aggregates.vectorSearch(
                fieldPath(MovieSearchDocument.Fields.PLOT_EMBEDDING),
                queryVector,
                vectorIndexName,
                fetchLimit,
                VectorSearchOptions.approximateVectorSearchOptions(numCandidates));
        Bson scoreStage = Aggregates.addFields(
                new Field<>("vectorSearchScore", new Document("$meta", "vectorSearchScore")));

        MongoCollection<Document> collection = mongoTemplate.getCollection(COLLECTION);
        // Materialize into a list inside the breaker: aggregate() itself only builds a lazy
        // cursor, so a failure during iteration (e.g. Mongo going down mid-scan) would otherwise
        // happen outside executeSupplier's try/catch and never count toward tripping the breaker.
        List<Document> rawResults = mongoCircuitBreaker.executeSupplier(
                () -> collection.aggregate(List.of(vectorSearchStage, scoreStage)).into(new ArrayList<>()));

        List<VectorSearchResult> results = new ArrayList<>();
        for (Document raw : rawResults) {
            ObjectId docId = raw.getObjectId(MovieSearchDocument.Fields.ID);
            if (excludeId != null && excludeId.equals(docId)) {
                continue;
            }

            Double score = raw.getDouble("vectorSearchScore");
            MovieSearchDocument document = mongoTemplate.getConverter().read(MovieSearchDocument.class, raw);
            results.add(VectorSearchResult.builder()
                    .movie(MovieSearchResult.from(document))
                    .score(score)
                    .build());

            if (results.size() == limit) {
                break;
            }
        }
        return results;
    }

    private Query buildQuery(MovieSearchQuery query) {
        Query mongoQuery = new Query();

        if (query.q() != null && !query.q().trim().isEmpty()) {
            TextCriteria textCriteria = TextCriteria.forDefaultLanguage().matching(query.q());
            mongoQuery.addCriteria(textCriteria);
        }

        if (query.genre() != null && !query.genre().trim().isEmpty()) {
            String escapedGenre = Pattern.quote(query.genre().trim());
            mongoQuery.addCriteria(Criteria.where(MovieSearchDocument.Fields.GENRES)
                    .regex(Pattern.compile(escapedGenre, Pattern.CASE_INSENSITIVE)));
        }

        if (query.year() != null) {
            mongoQuery.addCriteria(Criteria.where(MovieSearchDocument.Fields.YEAR).is(query.year()));
        }

        if (query.minRating() != null || query.maxRating() != null) {
            Criteria ratingCriteria = Criteria.where(MovieSearchDocument.Fields.IMDB_RATING);
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
}
