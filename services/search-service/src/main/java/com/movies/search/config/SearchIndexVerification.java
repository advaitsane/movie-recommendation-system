package com.movies.search.config;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.movies.search.model.MovieSearchDocument;
import jakarta.annotation.PostConstruct;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Startup creation of the compound text index (title/plot/fullplot), year index, genres index and
 * embedding-missing index that this service's queries depend on. The Atlas Vector Search index
 * is handled separately by {@link VectorSearchIndexVerification} (a different, mongot-backed
 * index type). Non-blocking: failures are logged, not thrown, so a misconfigured Mongo doesn't
 * block startup.
 */
@Component
public class SearchIndexVerification {

    private static final Logger logger = LoggerFactory.getLogger(SearchIndexVerification.class);

    private static final String COLLECTION = "movies_search";
    private static final String TEXT_INDEX_NAME = "movie_text_index";
    private static final String YEAR_INDEX_NAME = "year_index";
    private static final String GENRES_INDEX_NAME = "genres_index";
    private static final String EMBEDDING_MISSING_INDEX_NAME = "embedding_missing_index";

    private final MongoDatabase database;

    public SearchIndexVerification(MongoDatabase database) {
        this.database = database;
    }

    @PostConstruct
    public void verifyIndexes() {
        logger.info("Verifying indexes on '{}.{}'...", database.getName(), COLLECTION);

        try {
            MongoCollection<Document> collection = database.getCollection(COLLECTION);
            createTextIndex(collection);
            createYearIndex(collection);
            createGenresIndex(collection);
            createEmbeddingMissingIndex(collection);
            logger.info("Index verification completed successfully");
        } catch (Exception e) {
            logger.error("Index verification failed: {}", e.getMessage(), e);
        }
    }

    private void createTextIndex(MongoCollection<Document> collection) {
        if (indexExists(collection, TEXT_INDEX_NAME)) {
            logger.info("Text index '{}' already exists", TEXT_INDEX_NAME);
            return;
        }

        Bson keys = Indexes.compoundIndex(
                Indexes.text(MovieSearchDocument.Fields.TITLE),
                Indexes.text(MovieSearchDocument.Fields.PLOT),
                Indexes.text(MovieSearchDocument.Fields.FULLPLOT)
        );
        collection.createIndex(keys, new IndexOptions().name(TEXT_INDEX_NAME).background(true));
        logger.info("Text index '{}' created on title/plot/fullplot", TEXT_INDEX_NAME);
    }

    private void createYearIndex(MongoCollection<Document> collection) {
        if (indexExists(collection, YEAR_INDEX_NAME)) {
            logger.info("Year index '{}' already exists", YEAR_INDEX_NAME);
            return;
        }

        collection.createIndex(
                Indexes.ascending(MovieSearchDocument.Fields.YEAR),
                new IndexOptions().name(YEAR_INDEX_NAME).background(true));
        logger.info("Year index '{}' created", YEAR_INDEX_NAME);
    }

    /**
     * Without it, the genre filter and the distinct genres behind it read every document,
     * embeddings included: 37s for the distinct on a starved host, enough to page out the vector
     * index (ADR-0002 update, 2026-10-21). With it they read index keys only.
     */
    private void createGenresIndex(MongoCollection<Document> collection) {
        if (indexExists(collection, GENRES_INDEX_NAME)) {
            logger.info("Genres index '{}' already exists", GENRES_INDEX_NAME);
            return;
        }

        collection.createIndex(
                Indexes.ascending(MovieSearchDocument.Fields.GENRES),
                new IndexOptions().name(GENRES_INDEX_NAME).background(true));
        logger.info("Genres index '{}' created", GENRES_INDEX_NAME);
    }

    /**
     * Partial, so it holds only the movies without an embedding: EmbeddingRepairJob's periodic
     * count stays an index read instead of a scan that evicts the vector index from memory.
     */
    private void createEmbeddingMissingIndex(MongoCollection<Document> collection) {
        if (indexExists(collection, EMBEDDING_MISSING_INDEX_NAME)) {
            logger.info("Index '{}' already exists", EMBEDDING_MISSING_INDEX_NAME);
            return;
        }

        String field = MovieSearchDocument.Fields.EMBEDDING_MISSING;
        collection.createIndex(
                Indexes.ascending(field, MovieSearchDocument.Fields.ID),
                new IndexOptions().name(EMBEDDING_MISSING_INDEX_NAME)
                        .partialFilterExpression(Filters.eq(field, true))
                        .background(true));
        logger.info("Index '{}' created", EMBEDDING_MISSING_INDEX_NAME);
    }

    private boolean indexExists(MongoCollection<Document> collection, String name) {
        for (Document index : collection.listIndexes()) {
            if (name.equals(index.getString("name"))) {
                return true;
            }
        }
        return false;
    }
}
