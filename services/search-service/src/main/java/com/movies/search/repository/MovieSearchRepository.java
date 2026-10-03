package com.movies.search.repository;

import com.movies.search.model.MovieSearchDocument;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data MongoDB repository for the movies_search read model. Simple CRUD only — text
 * search and filtered queries need {@code TextCriteria} / aggregation and go through
 * {@code MongoTemplate} in {@code SearchServiceImpl} instead.
 */
@Repository
public interface MovieSearchRepository extends MongoRepository<MovieSearchDocument, ObjectId> {
}
