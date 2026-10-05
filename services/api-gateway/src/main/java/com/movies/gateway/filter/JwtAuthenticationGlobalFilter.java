package com.movies.gateway.filter;

import com.movies.gateway.security.JwtValidator;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.util.List;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The single enforcement point for the JWTs user-service issues — no downstream service
 * validates a token itself, per ADR-0006/ADR-0007's centralized-gateway architecture. On
 * success, forwards the validated identity as {@code X-User-Id}/{@code X-User-Email} headers
 * rather than the raw Authorization header, so downstream can trust those over a
 * caller-supplied path parameter (user-service's activity endpoint checks X-User-Id against its
 * path id).
 *
 * <p>Any client-supplied {@code X-User-*} header is stripped on every route, public ones
 * included, so these headers can only ever reach a downstream service from a validated token.
 */
@Component
public class JwtAuthenticationGlobalFilter implements GlobalFilter, Ordered {

    static final String USER_ID_HEADER = "X-User-Id";
    static final String USER_EMAIL_HEADER = "X-User-Email";

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private static final List<String> PUBLIC_PATTERNS =
            List.of("/api/users/register", "/api/users/login", "/api/movies/**", "/fallback/**");

    private final JwtValidator jwtValidator;

    public JwtAuthenticationGlobalFilter(JwtValidator jwtValidator) {
        this.jwtValidator = jwtValidator;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        ServerHttpRequest stripped = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(USER_ID_HEADER);
                    headers.remove(USER_EMAIL_HEADER);
                })
                .build();

        if (isPublic(path)) {
            return chain.filter(exchange.mutate().request(stripped).build());
        }

        String header = stripped.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            return GatewayErrorResponses.write(
                    exchange, HttpStatus.UNAUTHORIZED, "unauthorized", "Missing or malformed Authorization header");
        }

        String token = header.substring("Bearer ".length());
        try {
            Claims claims = jwtValidator.validate(token).getPayload();
            ServerHttpRequest mutatedRequest = stripped.mutate()
                    .header(USER_ID_HEADER, claims.getSubject())
                    .header(USER_EMAIL_HEADER, claims.get("email", String.class))
                    .build();
            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        } catch (JwtException e) {
            return GatewayErrorResponses.write(
                    exchange, HttpStatus.UNAUTHORIZED, "unauthorized", "Invalid or expired token");
        }
    }

    private boolean isPublic(String path) {
        return PUBLIC_PATTERNS.stream().anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
    }

    @Override
    public int getOrder() {
        return -80;
    }
}
