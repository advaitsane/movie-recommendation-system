package com.movies.recommendation.repository;

import com.movies.recommendation.model.UserProfile;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface UserProfileRepository extends MongoRepository<UserProfile, String> {

    List<UserProfile> findByUserIdNot(String userId);
}
