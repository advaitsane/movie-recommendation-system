package com.movies.review.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * A single user's review/rating of a movie. One row per (user_id, movie_id) — enforced by
 * a unique constraint at the database level (see {@code V1__create_reviews_and_outbox.sql});
 * the service layer pre-checks this too, so a duplicate POST returns a clean 409 rather than
 * a raw constraint-violation error.
 */
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Builder
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "reviews")
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @ToString.Include
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "user_id", nullable = false)
    @ToString.Include
    private String userId;

    @Column(name = "movie_id", nullable = false)
    @ToString.Include
    private String movieId;

    @Column(nullable = false)
    @ToString.Include
    private Integer rating;

    @Column(name = "review_text")
    private String reviewText;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
