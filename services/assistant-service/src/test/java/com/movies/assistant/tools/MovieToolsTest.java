package com.movies.assistant.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.movies.assistant.config.AssistantProperties;
import com.movies.assistant.support.StubHttpServer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.web.client.RestClient;

/**
 * The tools called directly, against a stub search- and recommendation-service: the requests they
 * build, what they trim before the model sees it, and how each kind of failure is reported.
 */
@DisplayName("MovieTools")
class MovieToolsTest {

    private StubHttpServer stub;
    private MovieTools tools;

    @BeforeEach
    void setUp() throws Exception {
        stub = new StubHttpServer();
        RestClient client = RestClient.builder().baseUrl(stub.url()).build();
        tools = new MovieTools(client, client, new AssistantProperties(20, 5));
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    @Test
    @DisplayName("searchMovies sends only the filters that are set")
    void searchSendsOnlySetFilters() {
        stub.enqueueJson("/api/movies/search", 200, "{\"content\":[]}");

        assertThat(tools.searchMovies(" ", null, 1999, 7.5)).isEmpty();

        assertThat(stub.requests().getFirst().query())
                .isEqualTo("size=5&sort=imdbRating,desc&year=1999&minRating=7.5");
    }

    @Test
    @DisplayName("findSimilarMovies maps vector hits to summaries")
    void similarMoviesMapsHits() {
        stub.enqueueJson("/api/movies/search/abc/similar", 200, """
                [{"movie":{"_id":"m1","title":"Ronin","year":1998,"genres":["Action"],"plot":"Mercenaries."},
                  "score":0.91}]""");

        List<MovieSummary> movies = tools.findSimilarMovies("abc");

        assertThat(movies).containsExactly(new MovieSummary("m1", "Ronin", 1998, List.of("Action"), null, "Mercenaries."));
        assertThat(stub.requests().getFirst().query()).isEqualTo("limit=5");
    }

    @Test
    @DisplayName("getMovieDetails keeps the first six cast members and prefers the full plot")
    void detailsTrimCastAndPreferFullPlot() {
        stub.enqueueJson("/api/movies/search/m1", 200, """
                {"_id":"m1","title":"Heat","year":1995,"plot":"Short.","fullplot":"Long.",
                 "cast":["a","b","c","d","e","f","g","h"],"directors":["Michael Mann"],"rated":"R","imdbRating":8.2}""");

        MovieDetails details = tools.getMovieDetails("m1");

        assertThat(details.cast()).containsExactly("a", "b", "c", "d", "e", "f");
        assertThat(details.plot()).isEqualTo("Long.");
        assertThat(details.directors()).containsExactly("Michael Mann");
    }

    @Test
    @DisplayName("an unknown id is reported as not found")
    void unknownIdIsReportedAsNotFound() {
        stub.enqueueJson("/api/movies/search/nope", 404, "{}");

        assertThatThrownBy(() -> tools.getMovieDetails("nope"))
                .isInstanceOf(ToolUnavailableException.class)
                .hasMessage("Movie lookup found no movie with that id");
    }

    @Test
    @DisplayName("a 400 tells the model to try something else")
    void badRequestSuggestsAnotherTool() {
        stub.enqueueJson("/api/movies/search/m1/similar", 400, "{}");

        assertThatThrownBy(() -> tools.findSimilarMovies("m1"))
                .isInstanceOf(ToolUnavailableException.class)
                .hasMessageContaining("can't answer this request (400)");
    }

    @Test
    @DisplayName("an unreachable service is reported as unavailable, without the underlying error")
    void unreachableServiceIsUnavailable() {
        RestClient unreachable = RestClient.builder().baseUrl("http://localhost:1").build();
        MovieTools offline = new MovieTools(unreachable, unreachable, new AssistantProperties(20, 5));

        assertThatThrownBy(() -> offline.findMoviesByDescription("heist"))
                .isInstanceOf(ToolUnavailableException.class)
                .hasMessage("Semantic search is unavailable right now");
    }

    @Test
    @DisplayName("getMyRecommendations without a user in the tool context makes no call")
    void recommendationsNeedAUser() {
        assertThatThrownBy(() -> tools.getMyRecommendations(new ToolContext(Map.of())))
                .isInstanceOf(ToolUnavailableException.class)
                .hasMessageContaining("signed-in user");
        assertThat(stub.requests()).isEmpty();
    }
}
