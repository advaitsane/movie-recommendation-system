package com.movies.catalog.service;

import com.movies.catalog.dto.CreateMovieRequest;
import com.movies.catalog.dto.DeleteResponse;
import com.movies.catalog.dto.MovieFilterQuery;
import com.movies.catalog.dto.UpdateMovieRequest;
import com.movies.catalog.model.Movie;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Service interface for movie business logic.
 */
public interface IMovieService {

    Page<Movie> getAllMovies(MovieFilterQuery query, Pageable pageable);

    Movie getMovieById(String id);

    Movie createMovie(CreateMovieRequest request);

    Movie updateMovie(String id, UpdateMovieRequest request);

    DeleteResponse deleteMovie(String id);
}
