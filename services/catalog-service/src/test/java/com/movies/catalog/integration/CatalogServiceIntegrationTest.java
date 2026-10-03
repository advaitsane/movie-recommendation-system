package com.movies.catalog.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.movies.catalog.CatalogServiceApplication;
import com.movies.catalog.dto.CreateMovieRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end check that the full Spring context wires up correctly against a real Mongo
 * instance (an isolated Testcontainers-provisioned container, not whatever MONGODB_URI
 * points at locally) — exercising MongoConfig, DatabaseVerification, the Kafka producer
 * config, and a real create-then-read round trip through the REST API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(classes = CatalogServiceApplication.class)
@org.springframework.context.annotation.Import(MongoDBTestContainersConfig.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("catalog-service Integration Test")
class CatalogServiceIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private MockMvc mockMvc;

    @org.springframework.beans.factory.annotation.Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Test
    @DisplayName("Creates a movie via REST and reads it back from the real database")
    void createThenGetMovie_roundTrips() throws Exception {
        CreateMovieRequest request = CreateMovieRequest.builder()
                .title("Integration Test Movie")
                .year(2026)
                .plot("A movie that only exists to prove the wiring works")
                .build();

        String createResponse = mockMvc.perform(post("/api/movies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Integration Test Movie"))
                .andReturn().getResponse().getContentAsString();

        String movieId = objectMapper.readTree(createResponse).at("/_id").asText();
        assertThat(movieId).isNotBlank();

        mockMvc.perform(get("/api/movies/{id}", movieId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Integration Test Movie"))
                .andExpect(jsonPath("$.year").value(2026));
    }
}
