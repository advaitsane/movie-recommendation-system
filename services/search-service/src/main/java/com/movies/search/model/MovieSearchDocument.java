package com.movies.search.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * search-service's own denormalized, read-optimized copy of a movie, synced via
 * movie.created/updated/deleted Kafka events — a CQRS-style read model, not a passthrough (see
 * docs/adr/0001). {@code id} matches catalog-service's Movie._id so a hit can be joined back.
 * {@code lastEventId}/{@code lastEventAt} let {@link com.movies.search.event.MovieEventConsumer}
 * reject a stale/out-of-order delivery.
 */
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Builder
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Document(collection = "movies_search")
public class MovieSearchDocument {

    public static class Fields {
        public static final String ID = "_id";
        public static final String TITLE = "title";
        public static final String YEAR = "year";
        public static final String PLOT = "plot";
        public static final String FULLPLOT = "fullplot";
        public static final String GENRES = "genres";
        public static final String DIRECTORS = "directors";
        public static final String IMDB_RATING = "imdbRating";
        public static final String PLOT_EMBEDDING = "plotEmbedding";

        private Fields() {
        }
    }

    @JsonProperty("_id")
    @Id
    @ToString.Include
    @EqualsAndHashCode.Include
    private ObjectId id;

    @ToString.Include
    private String title;

    @ToString.Include
    private Integer year;

    private String plot;
    private String fullplot;
    private String poster;
    private List<String> genres;
    private List<String> directors;
    private List<String> writers;
    private List<String> cast;
    private List<String> countries;
    private List<String> languages;
    private String rated;
    private Double imdbRating;
    private Integer imdbVotes;
    private Integer metacritic;
    private String type;

    /**
     * Embedding of {@code title + ". " + (fullplot or plot)} — see {@code EmbeddingTextBuilder}.
     * Null when no embedding provider was configured/reachable at index time; such documents
     * don't surface in $vectorSearch results but remain fully searchable via
     * text/genre/year/rating filters.
     */
    private List<Double> plotEmbedding;

    /**
     * id of the last MovieEvent applied to this document — for debugging/idempotency tracing,
     * not for dedup on its own (Mongo's upsert-by-id already makes re-applying the same event
     * a no-op; this + lastEventAt exist to reject a late, out-of-order delivery instead).
     */
    private String lastEventId;

    private Instant lastEventAt;
}
