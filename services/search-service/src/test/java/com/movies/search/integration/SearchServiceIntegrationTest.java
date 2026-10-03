package com.movies.search.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.movies.search.SearchServiceApplication;
import com.movies.search.model.MovieSearchDocument;
import com.movies.search.repository.MovieSearchRepository;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end check that the full Spring context wires up correctly against a real Mongo
 * instance (an isolated Testcontainers-provisioned container) — exercising MongoConfig,
 * SearchIndexVerification, the Kafka consumer config, and a real
 * write-directly-then-read-via-REST round trip through search-service's own collection.
 *
 * <p>Doesn't exercise MovieEventConsumer end-to-end (that needs a real broker — see
 * MovieEventConsumerTest for the consumer's unit coverage); this test writes the read model
 * the same way the consumer eventually would (repository.save), then confirms the REST layer
 * serves it correctly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(classes = SearchServiceApplication.class)
@org.springframework.context.annotation.Import(MongoDBTestContainersConfig.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("search-service Integration Test")
class SearchServiceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MovieSearchRepository repository;

    @Test
    @DisplayName("Indexes a movie directly, then finds it via the REST search endpoint")
    void indexThenSearch_roundTrips() throws Exception {
        ObjectId id = new ObjectId();
        MovieSearchDocument document = MovieSearchDocument.builder()
                .id(id)
                .title("Integration Test Movie")
                .year(2026)
                .plot("A movie that only exists to prove the read-model wiring works")
                .genres(java.util.List.of("Drama"))
                .build();
        repository.save(document);

        mockMvc.perform(get("/api/movies/search").param("q", "Integration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("Integration Test Movie"));

        mockMvc.perform(get("/api/movies/search/{id}", id.toHexString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Integration Test Movie"))
                .andExpect(jsonPath("$.year").value(2026));

        assertThat(repository.findById(id)).isPresent();
    }
}
