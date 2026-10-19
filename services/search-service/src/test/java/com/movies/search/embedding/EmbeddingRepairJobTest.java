package com.movies.search.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.movies.search.backfill.CatalogBackfillRunner;
import com.movies.search.model.MovieSearchDocument;
import com.movies.search.repository.BackfillStateRepository;
import com.movies.search.repository.MovieSearchRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runs {@link EmbeddingRepairJob} against an in-memory movies_search, with the repository's
 * queries answered from that map the way Mongo would answer them.
 */
@DisplayName("EmbeddingRepairJob Unit Tests")
class EmbeddingRepairJobTest {

    private static final List<Double> VECTOR = List.of(0.1, 0.2, 0.3);

    private final TreeMap<ObjectId, MovieSearchDocument> stored = new TreeMap<>();
    private final List<List<String>> embeddedBatches = new ArrayList<>();
    private MovieSearchRepository repository;
    private BackfillStateRepository stateRepository;
    private EmbeddingService embeddingService;
    private SimpleMeterRegistry meterRegistry;
    private EmbeddingRepairJob job;

    @BeforeEach
    void setUp() {
        repository = mock(MovieSearchRepository.class);
        when(repository.countByEmbeddingMissingTrue()).thenAnswer(invocation ->
                stored.values().stream().filter(document -> Boolean.TRUE.equals(document.getEmbeddingMissing())).count());
        when(repository.findTop100ByEmbeddingMissingTrueAndIdGreaterThanOrderByIdAsc(any())).thenAnswer(invocation ->
                stored.tailMap(invocation.getArgument(0), false).values().stream()
                        .filter(document -> Boolean.TRUE.equals(document.getEmbeddingMissing()))
                        .limit(100)
                        .toList());
        when(repository.setEmbeddingIfUnchanged(any(), anyList())).thenAnswer(invocation -> {
            MovieSearchDocument document = stored.get(invocation.<MovieSearchDocument>getArgument(0).getId());
            document.setPlotEmbedding(invocation.getArgument(1));
            return true;
        });

        stateRepository = mock(BackfillStateRepository.class);
        when(stateRepository.existsById(CatalogBackfillRunner.STATE_ID)).thenReturn(true);

        embeddingService = mock(EmbeddingService.class);
        when(embeddingService.isEnabled()).thenReturn(true);
        when(embeddingService.embedDocuments(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            embeddedBatches.add(texts);
            return texts.stream().map(text -> VECTOR).toList();
        });

        meterRegistry = new SimpleMeterRegistry();
        job = new EmbeddingRepairJob(repository, stateRepository, embeddingService, meterRegistry);
    }

    @Test
    @DisplayName("Embeds only the movies without a vector, and the gauge drops to zero")
    void repairsOnlyMissingEmbeddings() {
        store("The Matrix", "A hacker learns the truth.", List.of(9.0));
        store("Heat", "A crew of thieves.", null);
        store("Alien", "A crew meets a creature.", null);

        job.repair();

        assertThat(embeddedBatches).containsExactly(List.of("Heat. A crew of thieves.", "Alien. A crew meets a creature."));
        assertThat(stored.values()).allSatisfy(document -> assertThat(document.getPlotEmbedding()).isNotNull());
        assertThat(stored.firstEntry().getValue().getPlotEmbedding()).containsExactly(9.0);
        assertThat(gauge()).isZero();
    }

    @Test
    @DisplayName("Provider disabled: no embedding calls, and the gauge still reports the gap")
    void providerDisabledOnlyReports() {
        when(embeddingService.isEnabled()).thenReturn(false);
        store("Heat", "A crew of thieves.", null);
        store("Alien", "A crew meets a creature.", null);

        job.repair();

        verify(embeddingService, never()).embedDocuments(anyList());
        assertThat(gauge()).isEqualTo(2);
    }

    @Test
    @DisplayName("Waits for the catalog backfill to complete")
    void skipsUntilBackfillCompleted() {
        when(stateRepository.existsById(CatalogBackfillRunner.STATE_ID)).thenReturn(false);
        store("Heat", "A crew of thieves.", null);

        job.repair();

        verify(repository, never()).countByEmbeddingMissingTrue();
        verify(embeddingService, never()).embedDocuments(anyList());
    }

    @Test
    @DisplayName("A batch with no embeddings at all stops the run instead of trying every batch")
    void stopsWhenProviderFailsAWholeBatch() {
        when(embeddingService.embedDocuments(anyList())).thenAnswer(invocation ->
                Collections.nCopies(invocation.<List<String>>getArgument(0).size(), null));
        for (int i = 0; i < 150; i++) {
            store("Movie " + i, "Plot " + i, null);
        }

        job.repair();

        verify(embeddingService, times(1)).embedDocuments(anyList());
        assertThat(gauge()).isEqualTo(150);
    }

    @Test
    @DisplayName("Pages past movies with nothing to embed, so the run still ends and reaches later batches")
    void skipsMoviesWithNoText() {
        for (int i = 0; i < 100; i++) {
            store(null, null, null);
        }
        store("Heat", "A crew of thieves.", null);

        job.repair();

        assertThat(embeddedBatches).containsExactly(List.of("Heat. A crew of thieves."));
        assertThat(gauge()).isEqualTo(100);
    }

    @Test
    @DisplayName("A movie a Kafka update changed in the meantime isn't counted as repaired")
    void lostRaceIsNotCounted() {
        store("Heat", "A crew of thieves.", null);
        doReturn(false).when(repository).setEmbeddingIfUnchanged(any(), anyList());

        job.repair();

        verify(repository).setEmbeddingIfUnchanged(any(), anyList());
        assertThat(gauge()).isEqualTo(1);
    }

    private void store(String title, String plot, List<Double> embedding) {
        ObjectId id = new ObjectId();
        stored.put(id, MovieSearchDocument.builder().id(id).title(title).plot(plot).plotEmbedding(embedding).build());
    }

    private double gauge() {
        return meterRegistry.get("search.movies.without.embedding").gauge().value();
    }
}
