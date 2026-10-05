package com.movies.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.movies.gateway.config.GatewayJwtProperties;
import com.movies.gateway.security.JwtValidator;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class JwtAuthenticationGlobalFilterTest {

    private static final String SECRET = "test-only-jwt-signing-secret-at-least-32-bytes-long-xyz";

    private final JwtValidator jwtValidator = new JwtValidator(new GatewayJwtProperties(SECRET));
    private final JwtAuthenticationGlobalFilter filter = new JwtAuthenticationGlobalFilter(jwtValidator);

    @Test
    void allowsPublicPathWithoutAToken() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/movies/search?q=heist").build());
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        verify(chain).filter(any());
    }

    @Test
    void allowsRegisterAndLoginWithoutAToken() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.post("/api/users/login").build());
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        verify(chain).filter(any());
    }

    @Test
    void stripsClientSuppliedIdentityHeadersOnAPublicPath() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/movies/1")
                .header("X-User-Id", "999")
                .header("X-User-Email", "spoofed@example.com")
                .build());
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain).filter(captor.capture());
        assertThat(captor.getValue().getRequest().getHeaders().containsHeader("X-User-Id")).isFalse();
        assertThat(captor.getValue().getRequest().getHeaders().containsHeader("X-User-Email")).isFalse();
    }

    @Test
    void replacesClientSuppliedIdentityHeadersWithTokenClaimsOnAProtectedPath() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/reviews/1")
                .header("Authorization", "Bearer " + validToken())
                .header("X-User-Id", "999")
                .header("X-User-Email", "spoofed@example.com")
                .build());
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain).filter(captor.capture());
        assertThat(captor.getValue().getRequest().getHeaders().get("X-User-Id")).containsExactly("42");
        assertThat(captor.getValue().getRequest().getHeaders().get("X-User-Email"))
                .containsExactly("user@example.com");
    }

    @Test
    void rejectsProtectedPathWithNoAuthorizationHeader() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/reviews/1").build());
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        filter.filter(exchange, chain).block();

        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void rejectsProtectedPathWithAnInvalidToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/reviews/1")
                .header("Authorization", "Bearer not-a-real-token")
                .build());
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        filter.filter(exchange, chain).block();

        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void forwardsValidatedIdentityAsHeadersForAProtectedPath() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/reviews/1")
                .header("Authorization", "Bearer " + validToken())
                .build());
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain).filter(captor.capture());
        ServerWebExchange forwarded = captor.getValue();
        assertThat(forwarded.getRequest().getHeaders().getFirst("X-User-Id")).isEqualTo("42");
        assertThat(forwarded.getRequest().getHeaders().getFirst("X-User-Email")).isEqualTo("user@example.com");
    }

    private static String validToken() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("42")
                .claim("email", "user@example.com")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(key)
                .compact();
    }
}
