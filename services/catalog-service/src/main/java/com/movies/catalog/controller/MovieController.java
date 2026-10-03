package com.movies.catalog.controller;

import com.movies.catalog.dto.CreateMovieRequest;
import com.movies.catalog.dto.DeleteResponse;
import com.movies.catalog.dto.MovieFilterQuery;
import com.movies.catalog.dto.MovieResponse;
import com.movies.catalog.dto.UpdateMovieRequest;
import com.movies.catalog.model.Movie;
import com.movies.catalog.service.IMovieService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for movie catalog endpoints.
 */
@RestController
@RequestMapping("/api/movies")
@Tag(name = "Movies", description = "Movie management endpoints for CRUD operations")
public class MovieController {

    private final IMovieService iMovieService;

    public MovieController(IMovieService iMovieService) {
        this.iMovieService = iMovieService;
    }

    @Operation(
        summary = "Get all movies with optional filtering, sorting, and pagination",
        description = "Retrieve a page of movies with optional filtering by genre, year, and rating. " +
                     "Pagination and sorting use Spring Data's standard page/size/sort query params " +
                     "(e.g. ?page=0&size=20&sort=year,desc). For relevance-ranked text or semantic search, use " +
                     "search-service's GET /api/movies/search instead — this endpoint is exact-match filtering only."
    )
    @GetMapping
    public ResponseEntity<Page<MovieResponse>> getAllMovies(
            @Parameter(description = "Filter by genre (exact match, e.g. \"Action\")")
            @RequestParam(required = false) String genre,
            @Parameter(description = "Filter by exact year")
            @RequestParam(required = false) Integer year,
            @Parameter(description = "Minimum IMDB rating (inclusive)")
            @RequestParam(required = false) Double minRating,
            @Parameter(description = "Maximum IMDB rating (inclusive)")
            @RequestParam(required = false) Double maxRating,
            @PageableDefault(size = 20, sort = "title") Pageable pageable) {

        MovieFilterQuery query = MovieFilterQuery.builder()
                .genre(genre)
                .year(year)
                .minRating(minRating)
                .maxRating(maxRating)
                .build();

        Page<Movie> movies = iMovieService.getAllMovies(query, pageable);
        return ResponseEntity.ok(movies.map(MovieResponse::from));
    }

    @Operation(
        summary = "Get a single movie by ID",
        description = "Retrieve a single movie by its MongoDB ObjectId."
    )
    @GetMapping("/{id}")
    public ResponseEntity<MovieResponse> getMovieById(
            @Parameter(description = "Movie ObjectId (24-character hex string)", required = true)
            @PathVariable String id) {
        Movie movie = iMovieService.getMovieById(id);
        return ResponseEntity.ok(MovieResponse.from(movie));
    }

    @Operation(
        summary = "Create a new movie",
        description = "Create a single new movie document. Only the title field is required; all other fields are optional. " +
                     "Publishes movie.created to Kafka so search-service can build its read model."
    )
    @PostMapping
    public ResponseEntity<MovieResponse> createMovie(
            @Parameter(description = "Movie data to create", required = true)
            @Valid @RequestBody CreateMovieRequest request) {
        Movie movie = iMovieService.createMovie(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(MovieResponse.from(movie));
    }

    @Operation(
        summary = "Update a movie by ID",
        description = "Update a single movie document by its ObjectId using updateOne with $set operator. " +
                     "Publishes movie.updated to Kafka."
    )
    @PatchMapping("/{id}")
    public ResponseEntity<MovieResponse> updateMovie(
            @Parameter(description = "Movie ObjectId to update", required = true)
            @PathVariable String id,
            @Parameter(description = "Updated movie data (only provided fields will be updated)", required = true)
            @RequestBody UpdateMovieRequest request) {
        Movie movie = iMovieService.updateMovie(id, request);
        return ResponseEntity.ok(MovieResponse.from(movie));
    }

    @Operation(
        summary = "Delete a movie by ID",
        description = "Delete a single movie document by its ObjectId using deleteOne. " +
                     "Publishes movie.deleted to Kafka."
    )
    @DeleteMapping("/{id}")
    public ResponseEntity<DeleteResponse> deleteMovie(
            @Parameter(description = "Movie ObjectId to delete", required = true)
            @PathVariable String id) {
        return ResponseEntity.ok(iMovieService.deleteMovie(id));
    }
}
