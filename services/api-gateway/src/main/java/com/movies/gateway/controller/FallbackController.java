package com.movies.gateway.controller;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Target of each route's CircuitBreaker {@code fallbackUri} (see application.yml) — reached
 * when a downstream service is open-circuit or unreachable, so callers get a clear 503 instead
 * of a raw connection-refused error or a hung request.
 */
@RestController
public class FallbackController {

    @RequestMapping(
            value = "/fallback/{service}",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public Mono<ResponseEntity<Map<String, String>>> fallback(@PathVariable String service) {
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "error", "service_unavailable",
                        "service", service,
                        "message", service + "-service is temporarily unavailable, try again shortly")));
    }
}
