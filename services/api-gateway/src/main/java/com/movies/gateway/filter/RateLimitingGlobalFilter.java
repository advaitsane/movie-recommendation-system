package com.movies.gateway.filter;

import com.movies.gateway.config.RateLimitProperties;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import java.time.Duration;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * A single shared token bucket across the whole gateway, not per route or per client. The one
 * per-user, Redis-backed limit is on the assistant route (RequestRateLimiter in application.yml),
 * because each chat costs OpenAI tokens. See ADR-0007 and its 2026-10-16 update.
 */
@Component
public class RateLimitingGlobalFilter implements GlobalFilter, Ordered {

    private final RateLimiter rateLimiter;

    public RateLimitingGlobalFilter(RateLimitProperties properties) {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(properties.limitForPeriod())
                .limitRefreshPeriod(Duration.ofMillis(properties.limitRefreshPeriodMs()))
                .timeoutDuration(Duration.ZERO)
                .build();
        this.rateLimiter = RateLimiter.of("gateway", config);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (rateLimiter.acquirePermission()) {
            return chain.filter(exchange);
        }
        return GatewayErrorResponses.write(
                exchange, HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Too many requests, try again shortly");
    }

    @Override
    public int getOrder() {
        return -90;
    }
}
