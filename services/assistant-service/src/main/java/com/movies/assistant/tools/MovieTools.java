package com.movies.assistant.tools;

import com.movies.assistant.config.AssistantProperties;
import com.movies.assistant.dto.RecommendedMovie;
import com.movies.assistant.dto.SearchMovie;
import com.movies.assistant.dto.SearchPage;
import com.movies.assistant.dto.VectorHit;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

/**
 * The tools the model can call: read-only calls to search- and recommendation-service, trimmed
 * before the model sees them (every field costs input tokens). The user id comes from the
 * {@link ToolContext}, never a tool argument, so the model can't fetch another user's
 * recommendations.
 */
@Component
public class MovieTools {

    /** ToolContext key holding the id of the user the conversation belongs to. */
    public static final String USER_ID = "userId";

    private static final Logger logger = LoggerFactory.getLogger(MovieTools.class);

    private final RestClient searchRestClient;
    private final RestClient recommendationRestClient;
    private final int resultLimit;

    public MovieTools(RestClient searchRestClient, RestClient recommendationRestClient,
                      AssistantProperties properties) {
        this.searchRestClient = searchRestClient;
        this.recommendationRestClient = recommendationRestClient;
        this.resultLimit = properties.toolResultLimit();
    }

    @Tool(description = "Keyword search over movie titles and plots, with optional filters. Use it for a "
            + "known title, a keyword, or to filter by genre, release year or minimum IMDb rating. "
            + "Results are sorted by IMDb rating, best first. At least one argument should be set.")
    public List<MovieSummary> searchMovies(
            @ToolParam(required = false, description = "Words to match in the title or plot") String query,
            @ToolParam(required = false, description = "Genre, for example Drama, Comedy or Sci-Fi") String genre,
            @ToolParam(required = false, description = "Exact release year") Integer year,
            @ToolParam(required = false, description = "Minimum IMDb rating, from 0 to 10") Double minRating) {
        SearchPage page = call("Movie search", () -> searchRestClient.get()
                .uri(uri -> {
                    UriBuilder builder = uri.path("/api/movies/search")
                            .queryParam("size", resultLimit)
                            .queryParam("sort", "imdbRating,desc");
                    queryParamIfPresent(builder, "q", query);
                    queryParamIfPresent(builder, "genre", genre);
                    queryParamIfPresent(builder, "year", year);
                    queryParamIfPresent(builder, "minRating", minRating);
                    return builder.build();
                })
                .retrieve()
                .body(SearchPage.class));
        return page == null || page.content() == null
                ? List.of()
                : page.content().stream().map(MovieSummary::from).toList();
    }

    @Tool(description = "Semantic search: finds movies whose plot matches a description of what the "
            + "user wants, such as 'a heist that goes wrong' or 'quiet science fiction about "
            + "first contact'. Use it when the request describes a story, mood or theme rather than "
            + "a title or keyword.")
    public List<MovieSummary> findMoviesByDescription(
            @ToolParam(description = "A description of the story, mood or theme") String description) {
        VectorHit[] hits = call("Semantic search", () -> searchRestClient.get()
                .uri(uri -> uri.path("/api/movies/search/vector")
                        .queryParam("q", description)
                        .queryParam("limit", resultLimit)
                        .build())
                .retrieve()
                .body(VectorHit[].class));
        return toSummaries(hits);
    }

    @Tool(description = "Finds movies with a plot similar to a given movie. Needs the movie's id, "
            + "which the search tools return.")
    public List<MovieSummary> findSimilarMovies(
            @ToolParam(description = "Id of the movie to compare with") String movieId) {
        VectorHit[] hits = call("Similar-movie search", () -> searchRestClient.get()
                .uri(uri -> uri.path("/api/movies/search/{id}/similar")
                        .queryParam("limit", resultLimit)
                        .build(movieId))
                .retrieve()
                .body(VectorHit[].class));
        return toSummaries(hits);
    }

    @Tool(description = "Returns the full details of one movie: directors, main cast, age rating, "
            + "IMDb rating and the full plot. Needs the movie's id, which the search tools return.")
    public MovieDetails getMovieDetails(@ToolParam(description = "Id of the movie") String movieId) {
        SearchMovie movie = call("Movie lookup", () -> searchRestClient.get()
                .uri("/api/movies/search/{id}", movieId)
                .retrieve()
                .body(SearchMovie.class));
        if (movie == null) {
            throw new ToolUnavailableException("No movie found with id " + movieId, null);
        }
        return MovieDetails.from(movie);
    }

    @Tool(description = "Returns personal recommendations for the current user, based on the movies "
            + "they have rated. Use it when the user asks what they should watch or what they "
            + "might like, without describing anything specific.")
    public List<Recommendation> getMyRecommendations(ToolContext toolContext) {
        Object userId = toolContext.getContext().get(USER_ID);
        if (userId == null) {
            throw new ToolUnavailableException("Personal recommendations need a signed-in user", null);
        }
        RecommendedMovie[] movies = call("The recommendation service", () -> recommendationRestClient.get()
                .uri(uri -> uri.path("/api/recommendations/{userId}")
                        .queryParam("limit", resultLimit)
                        .build(userId))
                .retrieve()
                .body(RecommendedMovie[].class));
        return movies == null ? List.of() : List.of(movies).stream().map(Recommendation::from).toList();
    }

    private List<MovieSummary> toSummaries(VectorHit[] hits) {
        if (hits == null) {
            return List.of();
        }
        return List.of(hits).stream()
                .filter(hit -> hit.movie() != null)
                .map(hit -> MovieSummary.from(hit.movie()))
                .toList();
    }

    /**
     * Runs one downstream call and turns its failures into a message the model can act on. A 404
     * or 400 means the arguments were wrong (an unknown id, or a movie without a plot embedding
     * yet), so the model is told that; anything else means the service is unavailable.
     */
    private <T> T call(String what, Supplier<T> request) {
        try {
            return request.get();
        } catch (HttpClientErrorException ex) {
            logger.warn("{} rejected the request: {}", what, ex.getStatusCode());
            if (ex.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                throw new ToolUnavailableException(what + " found no movie with that id", ex);
            }
            throw new ToolUnavailableException(what + " can't answer this request (" + ex.getStatusCode().value()
                    + "); try a different tool or different arguments", ex);
        } catch (RestClientException ex) {
            logger.warn("{} call failed: {}", what, ex.getMessage());
            throw new ToolUnavailableException(what + " is unavailable right now", ex);
        }
    }

    private static void queryParamIfPresent(UriBuilder builder, String name, Object value) {
        if (value instanceof String text && text.isBlank()) {
            return;
        }
        if (value != null) {
            builder.queryParam(name, value);
        }
    }
}
