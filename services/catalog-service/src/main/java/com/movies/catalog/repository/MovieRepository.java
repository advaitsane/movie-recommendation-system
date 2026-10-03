package com.movies.catalog.repository;

import com.movies.catalog.model.Movie;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data MongoDB repository for movie data access.
 */
@Repository
public interface MovieRepository extends MongoRepository<Movie, ObjectId> {

}
