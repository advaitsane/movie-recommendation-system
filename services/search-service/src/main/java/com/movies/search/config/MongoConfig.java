package com.movies.search.config;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.lang.NonNull;

/**
 * MongoDB pool/timeout/retry tuning and custom type conversion for search-service's own
 * sample_mflix_search database, layered on Spring Boot's own Mongo autoconfiguration (health
 * indicator and repositories come free). Previously hand-built the MongoClient, which required
 * manually wiring a health indicator (see ADR-0011) — autoconfig removes that risk.
 */
@Configuration
@EnableConfigurationProperties(VectorSearchProperties.class)
public class MongoConfig {

    @Value("${spring.mongodb.database}")
    private String databaseName;

    /**
     * Tunes the MongoClient Boot's autoconfiguration builds, rather than replacing it —
     * connection pool sizing, socket/server-selection timeouts, and retry behavior aren't
     * exposed as plain properties, but this customizer gets the same Builder access a
     * hand-built MongoClient would.
     */
    @Bean
    public MongoClientSettingsBuilderCustomizer mongoClientSettingsBuilderCustomizer() {
        return builder -> builder
                .applyToConnectionPoolSettings(poolBuilder ->
                    poolBuilder.maxSize(100)                                    // Maximum connections in pool
                           .minSize(5)                                          // Minimum connections to maintain
                           .maxConnectionIdleTime(60000, TimeUnit.MILLISECONDS) // Release idle connections after 60s
                           .maxWaitTime(10000, TimeUnit.MILLISECONDS)           // Wait up to 10s for available connection
                           .maintenanceInitialDelay(0, TimeUnit.MILLISECONDS)   // Start maintenance immediately
                           .maintenanceFrequency(60000, TimeUnit.MILLISECONDS)  // Run maintenance every 60s
                )
                .applyToSocketSettings(socketBuilder ->
                    socketBuilder.connectTimeout(10000, TimeUnit.MILLISECONDS)  // 10s to establish connection
                           .readTimeout(60000, TimeUnit.MILLISECONDS)           // 60s to wait for server response
                )
                .applyToClusterSettings(clusterBuilder ->
                    clusterBuilder.serverSelectionTimeout(10000, TimeUnit.MILLISECONDS)  // 10s to select server
                )
                .retryWrites(true)
                .retryReads(true);
    }

    /**
     * Direct MongoDB driver access for components that need it (e.g. SearchIndexVerification,
     * VectorSearchIndexVerification, SearchServiceImpl's raw $vectorSearch aggregation),
     * alongside Spring Data's repository layer. Reuses Boot's autoconfigured MongoClient rather
     * than building a second connection.
     */
    @Bean
    @NonNull
    public MongoDatabase mongoDatabase(MongoClient mongoClient) {
        return mongoClient.getDatabase(databaseName);
    }

    @Bean
    public MongoCustomConversions customConversions() {
        return MongoCustomConversions.create(MongoCustomConversions.MongoConverterConfigurationAdapter::useNativeDriverJavaTimeCodecs);
    }
}
