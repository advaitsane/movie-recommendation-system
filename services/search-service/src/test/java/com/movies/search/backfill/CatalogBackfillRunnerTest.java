package com.movies.search.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.movies.search.embedding.EmbeddingService;
import com.movies.search.model.MovieSearchDocument;
import com.movies.search.repository.MovieSearchRepository;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClient;

/**
 * Runs {@link CatalogBackfillRunner} against a stub catalog-service that serves
 * {@code GET /api/movies} pages in Spring Data's VIA_DTO shape, so the request parameters and
 * the response parsing are both checked against what catalog-service really does.
 */
@DisplayName("CatalogBackfillRunner Unit Tests")
class CatalogBackfillRunnerTest {

    private static final String MATRIX_ID = new ObjectId().toHexString();
    private static final String HEAT_ID = new ObjectId().toHexString();
    private static final String ALIEN_ID = new ObjectId().toHexString();

    /** Two pages, as catalog-service would serve three movies at a page size of 2. */
    private static final Map<String, String> PAGES = Map.of(
            "0", page(List.of(movie(MATRIX_ID, "The Matrix"), movie(HEAT_ID, "Heat")), 0),
            "1", page(List.of(movie(ALIEN_ID, "Alien")), 1));

    private MovieSearchRepository repository;
    private EmbeddingService embeddingService;
    private HttpServer catalogStub;
    private final List<String> requestedQueries = new CopyOnWriteArrayList<>();
    private CatalogBackfillRunner runner;

    @BeforeEach
    void setUp() throws IOException {
        repository = mock(MovieSearchRepository.class);
        embeddingService = mock(EmbeddingService.class);
        when(embeddingService.embedDocuments(anyList())).thenAnswer(invocation ->
                Collections.nCopies(invocation.<List<String>>getArgument(0).size(), List.of(0.1, 0.2)));

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
        runner = new CatalogBackfillRunner(repository, catalogRestClient, embeddingService);
    }

    @AfterEach
    void tearDown() {
        catalogStub.stop(0);
    }

    @Test
    @DisplayName("an empty index is filled from every catalog page")
    void emptyIndex_isFilledFromEveryPage() {
        when(repository.count()).thenReturn(0L);

        runner.run(null);

        ArgumentCaptor<MovieSearchDocument> saved = ArgumentCaptor.forClass(MovieSearchDocument.class);
        verify(repository, times(3)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(MovieSearchDocument::getTitle)
                .containsExactly("The Matrix", "Heat", "Alien");
        assertThat(saved.getAllValues().getFirst().getPlotEmbedding()).containsExactly(0.1, 0.2);
        assertThat(requestedQueries).containsExactly(
                "page=0&size=" + CatalogBackfillRunner.PAGE_SIZE + "&sort=_id",
                "page=1&size=" + CatalogBackfillRunner.PAGE_SIZE + "&sort=_id");
    }

    @Test
    @DisplayName("a non-empty index is left alone")
    void nonEmptyIndex_isSkipped() {
        when(repository.count()).thenReturn(5L);

        runner.run(null);

        assertThat(requestedQueries).isEmpty();
        verify(repository, never()).save(any());
    }

    private static String movie(String id, String title) {
        return """
                {"_id":"%s","title":"%s","year":1999,"plot":"A plot","genres":["Action"],"imdb":{"rating":8.0}}"""
                .formatted(id, title);
    }

    private static String page(List<String> movies, int number) {
        return """
                {"content":[%s],"page":{"size":2,"number":%d,"totalElements":3,"totalPages":2}}"""
                .formatted(String.join(",", movies), number);
    }
}
