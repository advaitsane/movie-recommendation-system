package com.movies.assistant.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.movies.assistant.support.OpenAiStream;
import com.movies.assistant.support.StubHttpServer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The whole request path, from the HTTP endpoint through Spring AI's ChatClient, chat memory and
 * tool calling, to the OpenAI client and the downstream services. The OpenAI API and both services
 * are a {@link StubHttpServer}: the model's replies are scripted, so these tests check what the
 * service sends and how it streams, not what a real model would say.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("POST /api/assistant/chat")
class AssistantChatIntegrationTest {

    private static final String COMPLETIONS = "/v1/chat/completions";
    private static final String SEARCH = "/api/movies/search";
    private static final String RECOMMENDATIONS = "/api/recommendations";

    private static final StubHttpServer stub = createStub();
    private static final ObjectMapper json = new ObjectMapper();

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    record Event(String name, String data) {
    }

    @DynamicPropertySource
    static void stubUrls(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", () -> stub.url() + "/v1");
        registry.add("assistant.downstream.search-service-url", stub::url);
        registry.add("assistant.downstream.recommendation-service-url", stub::url);
    }

    @BeforeEach
    void resetStub() {
        stub.reset();
    }

    @AfterAll
    static void stopStub() {
        stub.close();
    }

    @Test
    @DisplayName("streams the answer as conversation, token and done events")
    void streamsAnswerAsEvents() throws Exception {
        stub.enqueue(COMPLETIONS, OpenAiStream.text("Try ", "Heat (1995)."));

        List<Event> events = chat("user-1", "{\"message\":\"Something tense\"}");

        // OpenAI's first chunk carries only the role and an empty content; it produces no event.
        assertThat(events).extracting(Event::name)
                .containsExactly("conversation", "token", "token", "done");
        assertThat(answerText(events)).isEqualTo("Try Heat (1995).");
        assertThat(json.readTree(events.getFirst().data()).get("conversationId").asText()).isNotBlank();

        JsonNode request = completionRequest(0);
        assertThat(request.get("model").asText()).isEqualTo("gpt-test");
        assertThat(request.get("stream").asBoolean()).isTrue();
        assertThat(request.get("reasoning_effort").asText()).isEqualTo("none");
        assertThat(request.get("messages").get(0).get("role").asText()).isEqualTo("system");
        assertThat(request.get("messages").get(0).get("content").asText()).contains("Only recommend movies that a tool returned");
        assertThat(toolNames(request)).containsExactlyInAnyOrder(
                "searchMovies", "findMoviesByDescription", "findSimilarMovies", "getMovieDetails",
                "getMyRecommendations");
    }

    @Test
    @DisplayName("calls a tool the model asks for and sends the trimmed result back to the model")
    void callsToolAndSendsResultBack() throws Exception {
        stub.enqueue(COMPLETIONS, OpenAiStream.toolCall("call_1", "searchMovies",
                "{\"query\":\"heist\",\"genre\":\"Crime\"}"));
        stub.enqueueJson(SEARCH, 200, """
                {"content":[{"_id":"573a1398f29313caabcea974","title":"Heat","year":1995,
                  "plot":"A group of professional bank robbers...","fullplot":"Long plot",
                  "genres":["Action","Crime"],"cast":["Al Pacino"],"imdbRating":8.2}],
                 "page":{"size":8,"number":0,"totalElements":1,"totalPages":1}}""");
        stub.enqueue(COMPLETIONS, OpenAiStream.text("Heat is a classic heist movie."));

        List<Event> events = chat("user-1", "{\"message\":\"A good heist movie\"}");

        assertThat(answerText(events)).isEqualTo("Heat is a classic heist movie.");
        assertThat(events.getLast().name()).isEqualTo("done");

        StubHttpServer.Recorded search = stub.requestsTo(SEARCH).getFirst();
        assertThat(search.query()).contains("q=heist", "genre=Crime", "size=8", "sort=imdbRating,desc")
                .doesNotContain("year=", "minRating=");

        JsonNode toolMessage = lastMessage(completionRequest(1), "tool");
        assertThat(toolMessage.get("tool_call_id").asText()).isEqualTo("call_1");
        // The summary keeps the short plot and drops cast and the full plot.
        assertThat(toolMessage.get("content").asText())
                .contains("Heat", "573a1398f29313caabcea974", "A group of professional bank robbers")
                .doesNotContain("Al Pacino", "Long plot");
    }

    @Test
    @DisplayName("fetches personal recommendations for the user in X-User-Id, not one the model names")
    void recommendationsUseTheCallersUserId() throws Exception {
        stub.enqueue(COMPLETIONS, OpenAiStream.toolCall("call_1", "getMyRecommendations", "{\"userId\":\"someone-else\"}"));
        stub.enqueueJson(RECOMMENDATIONS, 200, """
                [{"movieId":"m1","title":"Arrival","year":2016,"genres":["Sci-Fi"],"score":0.9,"source":"CONTENT"}]""");
        stub.enqueue(COMPLETIONS, OpenAiStream.text("Arrival."));

        chat("user-42", "{\"message\":\"What should I watch?\"}");

        StubHttpServer.Recorded recommendations = stub.requestsTo(RECOMMENDATIONS).getFirst();
        assertThat(recommendations.path()).isEqualTo("/api/recommendations/user-42");
        assertThat(lastMessage(completionRequest(1), "tool").get("content").asText()).contains("Arrival", "CONTENT");
    }

    @Test
    @DisplayName("tells the model when a tool's service is down, and still finishes the answer")
    void toolFailureIsReportedToTheModel() throws Exception {
        stub.enqueue(COMPLETIONS, OpenAiStream.toolCall("call_1", "findMoviesByDescription",
                "{\"description\":\"first contact\"}"));
        stub.enqueueJson(SEARCH, 503, "{\"error\":\"down\"}");
        stub.enqueue(COMPLETIONS, OpenAiStream.text("Search is unavailable right now."));

        List<Event> events = chat("user-1", "{\"message\":\"Quiet sci-fi about first contact\"}");

        assertThat(events.getLast().name()).isEqualTo("done");
        assertThat(lastMessage(completionRequest(1), "tool").get("content").asText())
                .contains("Semantic search is unavailable right now");
    }

    @Test
    @DisplayName("stops a tool after three calls in one answer and makes the model answer instead")
    void perToolLimitStopsRepeatedCalls() throws Exception {
        for (int call = 1; call <= 4; call++) {
            stub.enqueue(COMPLETIONS, OpenAiStream.toolCall("call_" + call, "searchMovies",
                    "{\"query\":\"heist " + call + "\"}"));
        }
        for (int call = 1; call <= 3; call++) {
            stub.enqueueJson(SEARCH, 200, "{\"content\":[]}");
        }
        stub.enqueue(COMPLETIONS, OpenAiStream.text("Nothing matched."));

        List<Event> events = chat("user-1", "{\"message\":\"A heist movie\"}");

        assertThat(events.getLast().name()).isEqualTo("done");
        assertThat(answerText(events)).isEqualTo("Nothing matched.");
        assertThat(stub.requestsTo(SEARCH)).hasSize(3);
        assertThat(lastMessage(completionRequest(4), "tool").get("content").asText()).containsIgnoringCase("limit");
    }

    @Test
    @DisplayName("ends the stream with an error event when the model call fails")
    void providerFailureEndsWithErrorEvent() throws Exception {
        stub.enqueueJson(COMPLETIONS, 401,
                "{\"error\":{\"message\":\"Incorrect API key provided\",\"type\":\"invalid_request_error\"}}");

        List<Event> events = chat("user-1", "{\"message\":\"Hi\"}");

        assertThat(events).extracting(Event::name).containsExactly("conversation", "error");
        JsonNode error = json.readTree(events.getLast().data());
        assertThat(error.get("error").asText()).isEqualTo("assistant_unavailable");
        // The provider's own message stays in the server log.
        assertThat(events.getLast().data()).doesNotContain("API key");
    }

    @Test
    @DisplayName("remembers a conversation per user and conversation id")
    void remembersConversationPerUser() throws Exception {
        stub.enqueue(COMPLETIONS, OpenAiStream.text("Heat."));
        stub.enqueue(COMPLETIONS, OpenAiStream.text("Ronin."));
        stub.enqueue(COMPLETIONS, OpenAiStream.text("Hello."));

        List<Event> first = chat("user-1", "{\"message\":\"A heist movie\"}");
        String conversationId = json.readTree(first.getFirst().data()).get("conversationId").asText();
        chat("user-1", "{\"message\":\"Another one\",\"conversationId\":\"" + conversationId + "\"}");
        chat("user-2", "{\"message\":\"Hi\",\"conversationId\":\"" + conversationId + "\"}");

        String secondTurn = completionRequest(1).get("messages").toString();
        assertThat(secondTurn).contains("A heist movie", "Heat.", "Another one");
        // Same conversation id, different user: none of user-1's conversation is sent.
        String otherUser = completionRequest(2).get("messages").toString();
        assertThat(otherUser).doesNotContain("A heist movie", "Heat.");
    }

    @Test
    @DisplayName("rejects a blank message with 400 and a JSON body, even for a streaming client")
    void blankMessageIsRejected() throws Exception {
        HttpResponse<String> response = post("user-1", "{\"message\":\" \"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("message must not be blank");
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    @DisplayName("rejects a request without X-User-Id with 401")
    void missingUserIsRejected() throws Exception {
        HttpResponse<String> response = post(null, "{\"message\":\"Hi\"}");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(stub.requests()).isEmpty();
    }

    private List<Event> chat(String userId, String body) throws IOException, InterruptedException {
        HttpResponse<String> response = post(userId, body);
        assertThat(response.statusCode()).isEqualTo(200);
        return parseEvents(response.body());
    }

    private HttpResponse<String> post(String userId, String body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/assistant/chat"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (userId != null) {
            request.header(AssistantController.USER_ID_HEADER, userId);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<Event> parseEvents(String body) {
        List<Event> events = new ArrayList<>();
        for (String block : body.split("\n\n")) {
            String name = null;
            StringBuilder data = new StringBuilder();
            for (String line : block.split("\n")) {
                if (line.startsWith("event:")) {
                    name = line.substring("event:".length()).strip();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring("data:".length()));
                }
            }
            if (name != null) {
                events.add(new Event(name, data.toString()));
            }
        }
        return events;
    }

    private static String answerText(List<Event> events) {
        StringBuilder text = new StringBuilder();
        events.stream()
                .filter(event -> event.name().equals("token"))
                .forEach(event -> text.append(json.readTree(event.data()).get("text").asText()));
        return text.toString();
    }

    private static JsonNode completionRequest(int index) {
        return json.readTree(stub.requestsTo(COMPLETIONS).get(index).body());
    }

    private static List<String> toolNames(JsonNode request) {
        List<String> names = new ArrayList<>();
        request.get("tools").forEach(tool -> names.add(tool.get("function").get("name").asText()));
        return names;
    }

    private static JsonNode lastMessage(JsonNode request, String role) {
        JsonNode found = null;
        for (JsonNode message : request.get("messages")) {
            if (role.equals(message.get("role").asText())) {
                found = message;
            }
        }
        assertThat(found).as("a %s message in the request", role).isNotNull();
        return found;
    }

    private static StubHttpServer createStub() {
        try {
            return new StubHttpServer();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
