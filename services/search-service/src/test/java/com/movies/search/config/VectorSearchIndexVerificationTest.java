package com.movies.search.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.ListSearchIndexesIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.SearchIndexModel;
import com.movies.search.embedding.EmbeddingService;
import java.util.Iterator;
import java.util.List;
import org.bson.BsonDocument;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("VectorSearchIndexVerification")
class VectorSearchIndexVerificationTest {

    private static final Document VECTOR_FIELD = Document.parse(
            "{type: 'vector', path: 'plotEmbedding', numDimensions: 1536, similarity: 'cosine'}");
    private static final Document GENRE_FILTER = Document.parse("{type: 'filter', path: 'genres'}");

    private MongoCollection<Document> collection;
    private VectorSearchIndexVerification verification;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        MongoDatabase database = mock(MongoDatabase.class);
        collection = mock(MongoCollection.class);
        when(database.getCollection("movies_search")).thenReturn(collection);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        when(embeddingService.getDimensions()).thenReturn(1536);
        verification = new VectorSearchIndexVerification(
                database, new VectorSearchProperties("movie_vector_index"), embeddingService);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("an index without the genres filter field is dropped and created with it")
    void recreatesAnOldIndexWithTheGenreFilter() {
        givenIndexes(index(VECTOR_FIELD));

        verification.verifyVectorIndex();

        verify(collection).dropSearchIndex("movie_vector_index");
        ArgumentCaptor<List<SearchIndexModel>> models = ArgumentCaptor.forClass(List.class);
        verify(collection).createSearchIndexes(models.capture());
        assertThat(models.getValue().getFirst().getDefinition().toBsonDocument().getArray("fields"))
                .contains(BsonDocument.parse("{type: 'filter', path: 'genres'}"));
    }

    @Test
    @DisplayName("an index that already has the genres filter field is left alone")
    void leavesACurrentIndexAlone() {
        givenIndexes(index(VECTOR_FIELD, GENRE_FILTER));

        verification.verifyVectorIndex();

        verify(collection, never()).dropSearchIndex(any());
        verify(collection, never()).createSearchIndexes(any());
    }

    @Test
    @DisplayName("a missing index is created with the genres filter field")
    void createsTheIndexWithTheFilter() {
        givenIndexes();

        verification.verifyVectorIndex();

        verify(collection).createSearchIndexes(any());
        verify(collection, never()).dropSearchIndex(any());
    }

    private static Document index(Document... fields) {
        return new Document("name", "movie_vector_index")
                .append("latestDefinition", new Document("fields", List.of(fields)));
    }

    @SuppressWarnings("unchecked")
    private void givenIndexes(Document... indexes) {
        Iterator<Document> listed = List.of(indexes).iterator();
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(cursor.hasNext()).thenAnswer(call -> listed.hasNext());
        when(cursor.next()).thenAnswer(call -> listed.next());
        ListSearchIndexesIterable<Document> iterable = mock(ListSearchIndexesIterable.class);
        when(iterable.iterator()).thenReturn(cursor);
        when(collection.listSearchIndexes()).thenReturn(iterable);
    }
}
