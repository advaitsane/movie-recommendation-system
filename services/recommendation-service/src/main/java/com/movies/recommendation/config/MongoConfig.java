package com.movies.recommendation.config;

import java.util.concurrent.TimeUnit;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MongoDB timeout tuning, layered on Spring Boot's own Mongo autoconfiguration (connection,
 * repositories, and health indicator come from spring-boot-starter-data-mongodb for free). Added
 * after a real Mongo outage test found the driver's untuned defaults left failures and
 * {@code /actuator/health} hanging far too long — see the README's Resilience &amp;
 * observability section for the measured before/after numbers.
 */
@Configuration
public class MongoConfig {

    /**
     * Tunes the MongoClient Boot's autoconfiguration builds, rather than replacing it —
     * server-selection/connect timeouts aren't exposed as plain properties, but this customizer
     * gets the same Builder access a hand-built MongoClient would.
     */
    @Bean
    public MongoClientSettingsBuilderCustomizer mongoClientSettingsBuilderCustomizer() {
        return builder -> builder
                .applyToSocketSettings(socketBuilder ->
                    socketBuilder.connectTimeout(10000, TimeUnit.MILLISECONDS)  // 10s to establish connection
                )
                .applyToClusterSettings(clusterBuilder ->
                    clusterBuilder.serverSelectionTimeout(10000, TimeUnit.MILLISECONDS)  // 10s to select server
                )
                .retryWrites(true)
                .retryReads(true);
    }
}
