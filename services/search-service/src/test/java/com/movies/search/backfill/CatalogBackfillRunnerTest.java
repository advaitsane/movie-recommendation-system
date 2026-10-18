package com.movies.search.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.movies.search.embedding.EmbeddingService;
import com.movies.search.model.BackfillState;
import com.movies.search.model.MovieSearchDocument;
import com.movies.search.repository.BackfillStateRepository;
import com.movies.search.repository.MovieSearchRepository;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * the response parsing are both checked against what catalog-service really does. movies_search
 * is an in-memory map, so a crashed run followed by a restart can be replayed.
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
    private BackfillStateRepository stateRepository;
    private EmbeddingService embeddingService;
    private HttpServer catalogStub;
    private final List<String> requestedQueries = new CopyOnWriteArrayList<>();
    private final Map<ObjectId, MovieSearchDocument> stored = new LinkedHashMap<>();
    private final List<List<String>> embeddedBatches = new ArrayList<>();
    private final AtomicBoolean failSecondPage = new AtomicBoolean();
    private CatalogBackfillRunner runner;

    @BeforeEach
    void setUp() throws IOException {
        repository = mock(MovieSearchRepository.class);
        when(repository.count()).thenAnswer(invocation -> (long) stored.size());
        when(repository.save(any())).thenAnswer(invocation -> {
            MovieSearchDocument document = invocation.getArgument(0);
            stored.put(document.getId(), document);
            return document;
        });
        when(repository.findAllById(anyIterable())).thenAnswer(invocation -> {
            List<MovieSearchDocument> found = new ArrayList<>();
            invocation.<Iterable<ObjectId>>getArgument(0).forEach(id -> {
                if (stored.containsKey(id)) {
                    found.add(stored.get(id));
                }
            });
            return found;
        });

        stateRepository = mock(BackfillStateRepository.class);
        when(stateRepository.existsById(anyString())).thenReturn(false);

        embeddingService = mock(EmbeddingService.class);
        when(embeddingService.embedDocuments(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            embeddedBatches.add(texts);
            return Collections.nCopies(texts.size(), List.of(0.1, 0.2));
        });

        catalogStub = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        catalogStub.createContext("/api/movies", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            requestedQueries.add(query);
            String pageNumber = query.replaceAll(".*\\bpage=(\\d+).*", "$1");
            if (pageNumber.equals("1") && failSecondPage.get()) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
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
        runner = new CatalogBackfillRunner(repository, stateRepository, catalogRestClient, embeddingService);
    }

    @AfterEach
    void tearDown() {
        catalogStub.stop(0);
    }

    @Test
    @DisplayName("an empty index is filled from every catalog page, then marked complete")
    void emptyIndex_isFilledFromEveryPage() {
        runner.run(null);

        assertThat(stored.values()).extracting(MovieSearchDocument::getTitle)
                .containsExactly("The Matrix", "Heat", "Alien");
        assertThat(stored.values().iterator().next().getPlotEmbedding()).containsExactly(0.1, 0.2);
        assertThat(requestedQueries).containsExactly(
                "page=0&size=" + CatalogBackfillRunner.PAGE_SIZE + "&sort=_id",
                "page=1&size=" + CatalogBackfillRunner.PAGE_SIZE + "&sort=_id");
        ArgumentCaptor<BackfillState> state = ArgumentCaptor.forClass(BackfillState.class);
        verify(stateRepository).save(state.capture());
        assertThat(state.getValue().id()).isEqualTo(CatalogBackfillRunner.STATE_ID);
        assertThat(state.getValue().movies()).isEqualTo(3);
    }

    @Test
    @DisplayName("a completed backfill is not run again")
    void completedBackfill_isSkipped() {
        when(stateRepository.existsById(CatalogBackfillRunner.STATE_ID)).thenReturn(true);

        runner.run(null);

        assertThat(requestedQueries).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a backfill that failed part-way leaves no marker, and the next start adds only the missing movies")
    void partialBackfill_isCompletedOnTheNextStart() {
        failSecondPage.set(true);
        runner.run(null);

        assertThat(stored.values()).extracting(MovieSearchDocument::getTitle)
                .containsExactly("The Matrix", "Heat");
        verify(stateRepository, never()).save(any());

        failSecondPage.set(false);
        embeddedBatches.clear();
        runner.run(null);

        assertThat(stored.values()).extracting(MovieSearchDocument::getTitle)
                .containsExactly("The Matrix", "Heat", "Alien");
        // The two movies stored by the first run are neither embedded nor written again.
        assertThat(embeddedBatches).hasSize(1);
        assertThat(embeddedBatches.getFirst()).hasSize(1);
        verify(repository, times(3)).save(any());
        verify(stateRepository).save(any());
    }

    @Test
    @DisplayName("a movie that Kafka already stored is not overwritten by the older catalog page")
    void movieStoredByKafka_isNotOverwritten() {
        MovieSearchDocument fromKafka = MovieSearchDocument.builder()
                .id(new ObjectId(HEAT_ID))
                .title("Heat (from a newer event)")
                .build();
        stored.put(fromKafka.getId(), fromKafka);

        runner.run(null);

        assertThat(stored.get(new ObjectId(HEAT_ID)).getTitle()).isEqualTo("Heat (from a newer event)");
        assertThat(stored).hasSize(3);
        verify(stateRepository).save(any());
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
