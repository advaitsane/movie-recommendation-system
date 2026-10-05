package com.movies.user.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Activates {@link JwtProperties} binding. Its own small config class rather than an
 * {@code @EnableConfigurationProperties} on {@code UserServiceApplication}, keeping the main
 * application class free of property-binding concerns.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {
}
