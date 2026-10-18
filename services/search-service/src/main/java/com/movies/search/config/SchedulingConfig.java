package com.movies.search.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled} processing, which drives {@code EmbeddingRepairJob}. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
