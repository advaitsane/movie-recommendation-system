package com.movies.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.movies.gateway.config.RateLimitProperties;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class RateLimitingGlobalFilterTest {

    @Test
    void allowsRequestsWithinTheLimit() {
        RateLimitingGlobalFilter filter = new RateLimitingGlobalFilter(new RateLimitProperties(5, 1000));
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        for (int i = 0; i < 5; i++) {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/movies").build());
            filter.filter(exchange, chain).block();
        }

        verify(chain, times(5)).filter(any());
    }

    @Test
    void rejectsRequestsOnceTheLimitIsExhausted() {
        RateLimitingGlobalFilter filter = new RateLimitingGlobalFilter(new RateLimitProperties(2, 60_000));
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        for (int i = 0; i < 2; i++) {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/movies").build());
            filter.filter(exchange, chain).block();
        }

        MockServerWebExchange rejected = MockServerWebExchange.from(MockServerHttpRequest.get("/api/movies").build());
        filter.filter(rejected, chain).block();

        verify(chain, times(2)).filter(any());
        assertThat(rejected.getResponse().getStatusCode().value()).isEqualTo(429);
    }
}
