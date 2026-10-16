package com.movies.review.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} processing, which drives {@code OutboxPoller}'s fixed-delay ticks.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
