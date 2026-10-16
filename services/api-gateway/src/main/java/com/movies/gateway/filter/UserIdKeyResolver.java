package com.movies.gateway.filter;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Rate-limit key for the assistant route: the X-User-Id that JwtAuthenticationGlobalFilter set
 * from a validated token (it strips any client-supplied one first). With no header the key is
 * empty, and RequestRateLimiter rejects the request rather than sharing one anonymous bucket.
 */
@Component("userIdKeyResolver")
public class UserIdKeyResolver implements KeyResolver {

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        return Mono.justOrEmpty(exchange.getRequest().getHeaders().getFirst(JwtAuthenticationGlobalFilter.USER_ID_HEADER));
    }
}
