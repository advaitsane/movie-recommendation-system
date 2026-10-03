package com.movies.search.config;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
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
 * Startup creation of the compound text index (title/plot/fullplot) and year index that this
 * service's queries depend on. The Atlas Vector Search index is handled separately by
 * {@link VectorSearchIndexVerification} (a different, mongot-backed index type). Non-blocking:
 * failures are logged, not thrown, so a misconfigured Mongo doesn't block startup.
 */
@Component
public class SearchIndexVerification {

    private static final Logger logger = LoggerFactory.getLogger(SearchIndexVerification.class);

    private static final String COLLECTION = "movies_search";
    private static final String TEXT_INDEX_NAME = "movie_text_index";
    private static final String YEAR_INDEX_NAME = "year_index";

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

    private boolean indexExists(MongoCollection<Document> collection, String name) {
        for (Document index : collection.listIndexes()) {
            if (name.equals(index.getString("name"))) {
                return true;
            }
        }
        return false;
    }
}
