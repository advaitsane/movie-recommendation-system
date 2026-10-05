package com.movies.recommendation.model;

import java.time.Instant;
import java.util.Map;
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
 * A user's genre-weighted taste profile, the basis for the collaborative-filtering signal
 * (cosine similarity between these vectors finds "similar users"). {@code genreWeights} is
 * recomputed from scratch — not incrementally adjusted — on every rating event, simpler and far
 * less bug-prone than tracking deltas, and cheap at this project's data scale.
 */
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Builder
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Document(collection = "user_profiles")
public class UserProfile {

    @Id
    @ToString.Include
    @EqualsAndHashCode.Include
    private String userId;

    /**
     * Genre name -> weight, the sum of this user's ratings for every movie in that genre. Raw,
     * unnormalized magnitudes are fine here: cosine similarity (used to compare two profiles)
     * already divides by each vector's own norm, so a user who's rated more movies doesn't get
     * an artificially higher or lower similarity score purely from vector length.
     */
    private Map<String, Double> genreWeights;

    private Instant updatedAt;
}
