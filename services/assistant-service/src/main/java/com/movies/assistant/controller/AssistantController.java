package com.movies.assistant.controller;

import com.movies.assistant.dto.ChatRequest;
import com.movies.assistant.dto.StreamError;
import com.movies.assistant.dto.TokenChunk;
import com.movies.assistant.exception.GlobalExceptionHandler;
import com.movies.assistant.memory.ConversationStore;
import com.movies.assistant.tools.MovieTools;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * {@code POST /api/assistant/chat}: streams the answer as server-sent events, always in the order
 * {@code conversation}, {@code token}s ({@link TokenChunk}), then {@code done} or {@code error}
 * ({@link StreamError}). Requests rejected before the stream starts get a normal 4xx from
 * {@link GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/assistant")
@Tag(name = "Assistant", description = "Conversational movie assistant backed by an LLM with tool calling")
public class AssistantController {

    /** Set by api-gateway from a validated JWT; see JwtAuthenticationGlobalFilter. */
    public static final String USER_ID_HEADER = "X-User-Id";

    private static final Logger logger = LoggerFactory.getLogger(AssistantController.class);

    private final ChatClient chatClient;
    private final ConversationStore conversationStore;

    public AssistantController(ChatClient chatClient, ConversationStore conversationStore) {
        this.chatClient = chatClient;
        this.conversationStore = conversationStore;
    }

    @Operation(
            summary = "Ask the movie assistant",
            description = "Streams the answer as server-sent events: one `conversation` event with the "
                    + "conversation id, `token` events with the answer, then `done`, or `error` if the "
                    + "model call failed. Send the conversation id back to continue the conversation; "
                    + "an id that isn't one of the caller's conversations gets 404. "
                    + "Requires the X-User-Id header that api-gateway sets from the bearer token."
    )
    @PostMapping(path = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> chat(
            @Parameter(hidden = true) @RequestHeader(USER_ID_HEADER) String userId,
            @Valid @RequestBody ChatRequest request) {
        // Before the stream starts, so a foreign or unknown id gets a plain 404.
        UUID conversationId;
        if (request.conversationId() == null) {
            conversationId = conversationStore.start(userId);
        } else {
            conversationId = UUID.fromString(request.conversationId());
            conversationStore.requireOwnedBy(conversationId, userId);
        }

        Flux<ServerSentEvent<Object>> answer = chatClient.prompt()
                .user(request.message())
                .advisors(advisors -> advisors.param(ChatMemory.CONVERSATION_ID, conversationId.toString()))
                .toolContext(Map.of(MovieTools.USER_ID, userId))
                .stream()
                .content()
                .map(text -> event("token", new TokenChunk(text)));

        return Flux.concat(
                        Flux.just(event("conversation", Map.of("conversationId", conversationId))),
                        answer,
                        Flux.just(event("done", Map.of())))
                // The client went away before the answer finished.
                .doOnCancel(() -> removeUnansweredMessage(conversationId, request.message()))
                .onErrorResume(ex -> {
                    // The full error stays in the log: a provider error can carry request details
                    // that shouldn't reach the client.
                    logger.error("Assistant stream failed for conversation {}", conversationId, ex);
                    removeUnansweredMessage(conversationId, request.message());
                    return Flux.just(event("error", new StreamError("assistant_unavailable",
                            "The assistant couldn't finish this answer. Try again shortly.")));
                });
    }

    /**
     * Spring AI saves the question before the model call and the answer only on completion, so a
     * failed or cancelled answer leaves a lone question. Best effort: the stream must still end
     * with its error event if the database is what failed.
     */
    private void removeUnansweredMessage(UUID conversationId, String message) {
        try {
            if (conversationStore.removeUnansweredMessage(conversationId, message)) {
                logger.info("Removed the unanswered message from conversation {}", conversationId);
            }
        } catch (RuntimeException ex) {
            logger.warn("Couldn't remove the unanswered message from conversation {}: {}",
                    conversationId, ex.getMessage());
        }
    }

    private static ServerSentEvent<Object> event(String name, Object data) {
        return ServerSentEvent.builder(data).event(name).build();
    }
}
