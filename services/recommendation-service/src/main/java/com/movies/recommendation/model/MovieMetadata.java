package com.movies.recommendation.model;

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
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * recommendation-service's own denormalized copy of just enough of a movie to compute
 * genre-vector profiles and display a recommendation, synced via catalog-service's
 * movie.created/updated/deleted Kafka events. {@code lastEventAt} is read-model bookkeeping —
 * rejects a stale/out-of-order delivery instead of overwriting newer data.
 */
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Builder
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Document(collection = "movie_metadata")
public class MovieMetadata {

    @JsonProperty("_id")
    @Id
    @ToString.Include
    @EqualsAndHashCode.Include
    private String id;

    @ToString.Include
    private String title;

    private Integer year;
    private String poster;
    private List<String> genres;

    private String lastEventId;
    private Instant lastEventAt;
}
