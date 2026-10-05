package com.movies.recommendation.repository;

import com.movies.recommendation.model.UserRating;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface UserRatingRepository extends MongoRepository<UserRating, String> {

    List<UserRating> findByUserId(String userId);

    Optional<UserRating> findByUserIdAndMovieId(String userId, String movieId);

    List<UserRating> findByUserIdInAndRatingGreaterThanEqual(List<String> userIds, Integer rating);

    List<UserRating> findByRatingGreaterThanEqual(Integer rating);
}
