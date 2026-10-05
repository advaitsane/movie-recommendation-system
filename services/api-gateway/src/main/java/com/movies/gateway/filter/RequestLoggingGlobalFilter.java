package com.movies.gateway.filter;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Runs first among the custom global filters, so every request is logged even if a later filter rejects it. */
@Component
public class RequestLoggingGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingGlobalFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        Instant start = Instant.now();
        return chain.filter(exchange)
                .doFinally(signal -> {
                    long elapsedMs = Duration.between(start, Instant.now()).toMillis();
                    log.info(
                            "{} {} -> {} ({} ms)",
                            request.getMethod(),
                            request.getPath(),
                            exchange.getResponse().getStatusCode(),
                            elapsedMs);
                });
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
