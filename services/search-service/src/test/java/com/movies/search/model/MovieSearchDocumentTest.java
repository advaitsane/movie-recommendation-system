package com.movies.search.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MovieSearchDocument Unit Tests")
class MovieSearchDocumentTest {

    @Test
    @DisplayName("Built without an embedding: marked missing, whether or not plotEmbedding was called")
    void builtWithoutEmbeddingIsMarked() {
        assertThat(MovieSearchDocument.builder().title("Heat").build().getEmbeddingMissing()).isTrue();
        assertThat(MovieSearchDocument.builder().plotEmbedding(null).build().getEmbeddingMissing()).isTrue();
    }

    @Test
    @DisplayName("Built with an embedding: no marker, so the partial index leaves it out")
    void builtWithEmbeddingIsNotMarked() {
        assertThat(MovieSearchDocument.builder().plotEmbedding(List.of(0.1)).build().getEmbeddingMissing()).isNull();
    }

    @Test
    @DisplayName("Setting or clearing the embedding updates the marker")
    void setterKeepsMarkerInStep() {
        MovieSearchDocument document = MovieSearchDocument.builder().build();

        document.setPlotEmbedding(List.of(0.1));
        assertThat(document.getEmbeddingMissing()).isNull();

        document.setPlotEmbedding(null);
        assertThat(document.getEmbeddingMissing()).isTrue();
    }
}
