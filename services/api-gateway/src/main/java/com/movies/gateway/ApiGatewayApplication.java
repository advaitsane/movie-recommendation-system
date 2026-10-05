package com.movies.gateway;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@SpringBootApplication
@RestController
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }

    @GetMapping("/")
    public Mono<Map<String, String>> root() {
        return Mono.just(Map.of(
                "service", "api-gateway",
                "description", "Routes /api/** to catalog/search/review/recommendation/user-service"));
    }
}
