package com.movies.recommendation.repository;

import com.movies.recommendation.model.MovieMetadata;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface MovieMetadataRepository extends MongoRepository<MovieMetadata, String> {

    List<MovieMetadata> findByIdIn(List<String> movieIds);
}
