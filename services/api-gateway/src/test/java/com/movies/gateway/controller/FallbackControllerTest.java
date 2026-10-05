package com.movies.gateway.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(FallbackController.class)
class FallbackControllerTest {

    @org.springframework.beans.factory.annotation.Autowired
    private WebTestClient webTestClient;

    @Test
    void returnsServiceUnavailableWithTheServiceNameForGet() {
        webTestClient
                .get()
                .uri("/fallback/catalog")
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .jsonPath("$.service")
                .isEqualTo("catalog")
                .jsonPath("$.error")
                .isEqualTo("service_unavailable");
    }

    @Test
    void returnsServiceUnavailableForPost() {
        webTestClient
                .post()
                .uri("/fallback/users")
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .jsonPath("$.service")
                .isEqualTo("users");
    }
}
