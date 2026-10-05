package com.movies.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Kept off the main application class, matching the convention established in user-service:
 * {@code @ConfigurationProperties} activation lives in its own configuration class rather than
 * being bundled onto {@code @SpringBootApplication}, so test slices don't pick it up implicitly.
 */
@Configuration
@EnableConfigurationProperties(GatewayJwtProperties.class)
public class JwtConfig {}
