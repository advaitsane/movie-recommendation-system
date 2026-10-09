package com.movies.recommendation.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import com.movies.recommendation.RecommendationServiceApplication;
import com.movies.recommendation.integration.MongoDBTestContainersConfig;
import com.movies.recommendation.model.MovieMetadata;
import com.movies.recommendation.repository.MovieMetadataRepository;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.client.RestClient;

/**
 * Runs {@link CatalogBackfillRunner} against a real Mongo (Testcontainers) and a stub
 * catalog-service that serves {@code GET /api/movies} pages in Spring Data's VIA_DTO shape.
 * The application context's own runner is off ({@code catalog.backfill.enabled=false} in the
 * test profile); each test builds one pointed at the stub and calls {@code backfill()} directly.
 * Same context annotations as {@code RecommendationIntegrationTest}, so both share one cached
 * context and one Mongo container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(classes = RecommendationServiceApplication.class)
@Import(MongoDBTestContainersConfig.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("CatalogBackfillRunner")
class CatalogBackfillRunnerTest {

    private static final String MATRIX_ID = new ObjectId().toHexString();
    private static final String HEAT_ID = new ObjectId().toHexString();
    private static final String ALIEN_ID = new ObjectId().toHexString();

    /** Two pages, as catalog-service would serve three movies at a page size of 2. */
    private static final Map<String, String> PAGES = Map.of(
            "0", page(List.of(
                    movie(MATRIX_ID, "The Matrix", 1999, "[\"Action\",\"Sci-Fi\"]"),
                    movie(HEAT_ID, "Heat", 1995, "[\"Crime\",\"Drama\"]")), 0),
            "1", page(List.of(
                    movie(ALIEN_ID, "Alien", 1979, "[\"Horror\",\"Sci-Fi\"]")), 1));

    @Autowired
    private MovieMetadataRepository repository;

    @Autowired
    private MongoTemplate mongoTemplate;

    private HttpServer catalogStub;
    private final List<String> requestedQueries = new CopyOnWriteArrayList<>();
    private CatalogBackfillRunner runner;

    @BeforeEach
    void setUp() throws IOException {
        repository.deleteAll();

        catalogStub = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        catalogStub.createContext("/api/movies", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            requestedQueries.add(query);
            String pageNumber = query.replaceAll(".*\\bpage=(\\d+).*", "$1");
            byte[] body = PAGES.get(pageNumber).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        catalogStub.start();

        RestClient catalogRestClient = RestClient.create(
                "http://localhost:" + catalogStub.getAddress().getPort());
        runner = new CatalogBackfillRunner(repository, mongoTemplate, catalogRestClient);
    }

    @AfterEach
    void tearDown() {
        catalogStub.stop(0);
    }

    @Test
    @DisplayName("an empty movie_metadata is filled from every catalog page")
    void emptyCollection_isFilledFromEveryPage() {
        runner.backfill();

        assertThat(repository.count()).isEqualTo(3);
        MovieMetadata matrix = repository.findById(MATRIX_ID).orElseThrow();
        assertThat(matrix.getTitle()).isEqualTo("The Matrix");
        assertThat(matrix.getYear()).isEqualTo(1999);
        assertThat(matrix.getGenres()).containsExactly("Action", "Sci-Fi");
        assertThat(matrix.getLastEventAt()).isNull();
        assertThat(repository.findById(ALIEN_ID)).isPresent();
        assertThat(requestedQueries).allSatisfy(query ->
                assertThat(query).contains("size=" + CatalogBackfillRunner.PAGE_SIZE).contains("sort=_id"));
    }

    @Test
    @DisplayName("a movie already written from a Kafka event is not overwritten")
    void eventWrittenMovie_isNotOverwritten() {
        Instant eventTime = Instant.parse("2026-10-01T12:00:00Z");
        repository.save(MovieMetadata.builder()
                .id(HEAT_ID).title("Heat (edited)").year(1995).genres(List.of("Thriller"))
                .lastEventId("event-1").lastEventAt(eventTime).build());

        runner.backfill();

        assertThat(repository.count()).isEqualTo(3);
        MovieMetadata heat = repository.findById(HEAT_ID).orElseThrow();
        assertThat(heat.getTitle()).isEqualTo("Heat (edited)");
        assertThat(heat.getGenres()).containsExactly("Thriller");
        assertThat(heat.getLastEventAt()).isEqualTo(eventTime);
    }

    @Test
    @DisplayName("a collection already as large as the catalog is skipped after the first page")
    void fullCollection_isSkipped() {
        for (String id : List.of(MATRIX_ID, HEAT_ID, ALIEN_ID)) {
            repository.save(MovieMetadata.builder().id(id).title("existing").build());
        }

        runner.backfill();

        assertThat(requestedQueries).hasSize(1);
        assertThat(repository.findById(MATRIX_ID).orElseThrow().getTitle()).isEqualTo("existing");
    }

    private static String movie(String id, String title, int year, String genresJson) {
        return """
                {"_id":"%s","title":"%s","year":%d,"genres":%s,"plot":"ignored","imdb":{"rating":8.0}}"""
                .formatted(id, title, year, genresJson);
    }

    private static String page(List<String> movies, int number) {
        return """
                {"content":[%s],"page":{"size":2,"number":%d,"totalElements":3,"totalPages":2}}"""
                .formatted(String.join(",", movies), number);
    }
}
