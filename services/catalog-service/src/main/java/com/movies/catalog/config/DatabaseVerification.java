package com.movies.catalog.config;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.movies.catalog.model.Movie;
import jakarta.annotation.PostConstruct;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Startup check that confirms the movies collection is reachable and populated, and
 * creates the year/genre indexes used by GET /api/movies filters. Non-blocking — the
 * app still starts even if verification fails, with warnings logged for troubleshooting.
 */
@Component
public class DatabaseVerification {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseVerification.class);

    private static final String MOVIES_COLLECTION = "movies";
    private static final String YEAR_INDEX_NAME = "year_index";
    private static final String GENRE_INDEX_NAME = "genre_index";

    private final MongoDatabase database;

    public DatabaseVerification(MongoDatabase database) {
        this.database = database;
    }

    @PostConstruct
    public void verifyDatabase() {
        logger.info("Starting database verification for '{}'...", database.getName());

        try {
            verifyMoviesCollection();

            logger.info("Database verification completed successfully");

        } catch (Exception e) {
            logger.error("Database verification failed: {}", e.getMessage(), e);
            // Don't throw exception - allow application to start even if verification fails
            // This allows developers to troubleshoot connection issues without preventing startup
        }
    }

    /**
     * Verifies the movies collection exists, contains data, and has required indexes.
     */
    private void verifyMoviesCollection() {
        MongoCollection<Document> moviesCollection = database.getCollection(MOVIES_COLLECTION);

        // Using estimatedDocumentCount() for better performance (doesn't scan all documents)
        long count = moviesCollection.estimatedDocumentCount();

        logger.info("Movies collection found with {} documents", count);

        if (count == 0) {
            logger.warn(
                "Movies collection is empty. Please ensure sample_mflix data is loaded. " +
                "Visit https://www.mongodb.com/docs/atlas/sample-data/ for instructions."
            );
        }

        // No index on imdb.rating (the range filter) — index-worthiness here is a talking
        // point, not a real need at this dataset's ~21k-document scale.
        createIndexIfMissing(moviesCollection, YEAR_INDEX_NAME, Movie.Fields.YEAR);
        createIndexIfMissing(moviesCollection, GENRE_INDEX_NAME, Movie.Fields.GENRES);
    }

    /**
     * Creates an ascending index on the given field for the movies collection if it doesn't
     * already exist.
     */
    private void createIndexIfMissing(MongoCollection<Document> moviesCollection, String indexName, String field) {
        try {
            boolean indexExists = false;
            for (Document index : moviesCollection.listIndexes()) {
                if (indexName.equals(index.getString("name"))) {
                    indexExists = true;
                    logger.info("Index '{}' already exists", indexName);
                    break;
                }
            }

            if (!indexExists) {
                IndexOptions indexOptions = new IndexOptions()
                        .name(indexName)
                        .background(true);

                moviesCollection.createIndex(Indexes.ascending(field), indexOptions);

                logger.info("Index '{}' created successfully for movies collection", indexName);
            }

        } catch (Exception e) {
            logger.error("Could not create index '{}': {}", indexName, e.getMessage());
        }
    }

}
