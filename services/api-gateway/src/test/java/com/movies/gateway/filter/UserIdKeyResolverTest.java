package com.movies.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class UserIdKeyResolverTest {

    private final UserIdKeyResolver resolver = new UserIdKeyResolver();

    @Test
    void keysOnTheForwardedUserId() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/assistant/chat").header("X-User-Id", "59").build());

        assertThat(resolver.resolve(exchange).block()).isEqualTo("59");
    }

    @Test
    void noUserIdGivesAnEmptyKey() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.post("/api/assistant/chat").build());

        assertThat(resolver.resolve(exchange).blockOptional()).isEmpty();
    }
}
