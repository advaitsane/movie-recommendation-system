package com.movies.assistant.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled}, for {@code ConversationRetentionJob}. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
