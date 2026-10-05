package com.movies.gateway.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "gateway.rate-limit")
public record RateLimitProperties(@Positive int limitForPeriod, @Positive long limitRefreshPeriodMs) {}
