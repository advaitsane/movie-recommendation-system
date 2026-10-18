package com.movies.search.repository;

import com.movies.search.model.BackfillState;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BackfillStateRepository extends MongoRepository<BackfillState, String> {
}
