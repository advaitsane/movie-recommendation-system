package com.movies.recommendation.model;

import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * recommendation-service's own local copy of "what rating did this user give this movie", built
 * entirely from review-service's Kafka events, never by querying review-service's Postgres
 * directly. Known gap inherited from review-service: no event fires on review delete, so a
 * deleted review's rating lingers here indefinitely. {@code lastEventAt} guards against a
 * stale/out-of-order redelivery overwriting a newer rating.
 */
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Builder
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Document(collection = "user_ratings")
@CompoundIndex(name = "uq_user_movie", def = "{'userId': 1, 'movieId': 1}", unique = true)
public class UserRating {

    public static class Fields {
        public static final String USER_ID = "userId";
        public static final String MOVIE_ID = "movieId";
        public static final String RATING = "rating";

        private Fields() {
        }
    }

    @Id
    private String id;

    @ToString.Include
    @EqualsAndHashCode.Include
    private String userId;

    @ToString.Include
    @EqualsAndHashCode.Include
    private String movieId;

    @ToString.Include
    private Integer rating;

    private String lastEventId;
    private Instant lastEventAt;
}
