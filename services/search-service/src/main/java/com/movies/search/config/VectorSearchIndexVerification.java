package com.movies.search.config;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.SearchIndexDefinition;
import com.mongodb.client.model.SearchIndexModel;
import com.mongodb.client.model.SearchIndexType;
import com.mongodb.client.model.VectorSearchIndexFields;
import com.movies.search.embedding.EmbeddingService;
import com.movies.search.model.MovieSearchDocument;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Startup creation of the Atlas Vector Search index on {@code movies_search.plotEmbedding} —
 * the mongot-backed counterpart to {@link SearchIndexVerification}. Index builds are
 * asynchronous, so this only issues the create call; until it finishes, {@code $vectorSearch}
 * returns no results, not an error. {@code numDimensions} comes from
 * {@link EmbeddingService#getDimensions()} so it can never drift from what's actually stored.
 */
@Component
public class VectorSearchIndexVerification {

    private static final Logger logger = LoggerFactory.getLogger(VectorSearchIndexVerification.class);

    private static final String COLLECTION = "movies_search";

    private final MongoDatabase database;
    private final String indexName;
    private final EmbeddingService embeddingService;

    public VectorSearchIndexVerification(
            MongoDatabase database,
            VectorSearchProperties properties,
            EmbeddingService embeddingService) {
        this.database = database;
        this.indexName = properties.indexName();
        this.embeddingService = embeddingService;
    }

    @PostConstruct
    public void verifyVectorIndex() {
        logger.info("Verifying vector search index '{}' on '{}.{}'...", indexName, database.getName(), COLLECTION);

        try {
            MongoCollection<Document> collection = database.getCollection(COLLECTION);
            if (vectorIndexExists(collection)) {
                logger.info("Vector search index '{}' already exists", indexName);
                return;
            }

            int embeddingDimensions = embeddingService.getDimensions();
            SearchIndexDefinition definition = SearchIndexDefinition.vectorSearch(
                    VectorSearchIndexFields.vectorField(MovieSearchDocument.Fields.PLOT_EMBEDDING)
                            .numDimensions(embeddingDimensions)
                            .similarity("cosine"));

            // The 2-arg SearchIndexModel(name, VectorSearchIndexDefinition) constructor doesn't
            // stamp `type: "vectorSearch"` into the wire command against this mongot version —
            // it comes back rejected as a malformed plain "search" index ("mappings" required).
            // Passing SearchIndexType.vectorSearch() explicitly via the 3-arg constructor works.
            collection.createSearchIndexes(
                    List.of(new SearchIndexModel(indexName, definition, SearchIndexType.vectorSearch())));
            logger.info(
                    "Vector search index '{}' creation submitted ({} dimensions, cosine similarity) — "
                            + "building asynchronously, $vectorSearch queries return no results until it's ready",
                    indexName, embeddingDimensions);
        } catch (Exception e) {
            logger.error(
                    "Vector search index verification failed (does this Mongo deployment support Atlas "
                            + "Search / mongot?): {}",
                    e.getMessage(), e);
        }
    }

    private boolean vectorIndexExists(MongoCollection<Document> collection) {
        for (Document index : collection.listSearchIndexes()) {
            if (indexName.equals(index.getString("name"))) {
                return true;
            }
        }
        return false;
    }
}
