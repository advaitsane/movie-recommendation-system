package com.movies.assistant.support;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds OpenAI Chat Completions streaming responses (the {@code text/event-stream} body of
 * {@code POST /v1/chat/completions} with {@code stream: true}) for {@link StubHttpServer}.
 */
public final class OpenAiStream {

    private OpenAiStream() {
    }

    /** A streamed text answer, one chunk per piece. */
    public static StubHttpServer.Response text(String... pieces) {
        List<String> chunks = new ArrayList<>();
        chunks.add(chunk("{\"role\":\"assistant\",\"content\":\"\"}", null));
        for (String piece : pieces) {
            chunks.add(chunk("{\"content\":\"" + escape(piece) + "\"}", null));
        }
        chunks.add(chunk("{}", "stop"));
        return sse(chunks);
    }

    /** A streamed request to call one tool with the given JSON arguments. */
    public static StubHttpServer.Response toolCall(String callId, String toolName, String argumentsJson) {
        String delta = "{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"" + callId
                + "\",\"type\":\"function\",\"function\":{\"name\":\"" + toolName
                + "\",\"arguments\":\"" + escape(argumentsJson) + "\"}}]}";
        return sse(List.of(chunk(delta, null), chunk("{}", "tool_calls")));
    }

    private static String chunk(String delta, String finishReason) {
        String finish = finishReason == null ? "null" : "\"" + finishReason + "\"";
        return "{\"id\":\"chatcmpl-test\",\"object\":\"chat.completion.chunk\",\"created\":1760000000,"
                + "\"model\":\"gpt-test\",\"choices\":[{\"index\":0,\"delta\":" + delta
                + ",\"finish_reason\":" + finish + "}]}";
    }

    private static StubHttpServer.Response sse(List<String> chunks) {
        StringBuilder body = new StringBuilder();
        for (String chunk : chunks) {
            body.append("data: ").append(chunk).append("\n\n");
        }
        body.append("data: [DONE]\n\n");
        return new StubHttpServer.Response(200, "text/event-stream", body.toString());
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
