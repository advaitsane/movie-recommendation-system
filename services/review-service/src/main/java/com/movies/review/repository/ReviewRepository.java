package com.movies.review.repository;

import com.movies.review.model.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    boolean existsByUserIdAndMovieId(String userId, String movieId);

    Page<Review> findByMovieId(String movieId, Pageable pageable);

    Page<Review> findByUserId(String userId, Pageable pageable);

    Page<Review> findByMovieIdAndUserId(String movieId, String userId, Pageable pageable);
}
