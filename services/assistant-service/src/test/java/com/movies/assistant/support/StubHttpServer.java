package com.movies.assistant.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One JDK HttpServer standing in for every service assistant-service talks to: the OpenAI API,
 * search-service and recommendation-service. Responses are queued per path prefix and served in
 * order; every request is recorded so tests can assert on what was sent.
 */
public class StubHttpServer implements AutoCloseable {

    public record Response(int status, String contentType, String body) {
    }

    public record Recorded(String method, String path, String query, String body) {
    }

    private final HttpServer server;
    private final Map<String, Queue<Response>> responses = new ConcurrentHashMap<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();

    public StubHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /** Queues a response for the next request whose path starts with {@code pathPrefix}. */
    public void enqueue(String pathPrefix, Response response) {
        responses.computeIfAbsent(pathPrefix, key -> new ArrayDeque<>()).add(response);
    }

    public void enqueueJson(String pathPrefix, int status, String json) {
        enqueue(pathPrefix, new Response(status, "application/json", json));
    }

    public List<Recorded> requests() {
        return List.copyOf(requests);
    }

    public List<Recorded> requestsTo(String pathPrefix) {
        return requests.stream().filter(request -> request.path().startsWith(pathPrefix)).toList();
    }

    public void reset() {
        responses.clear();
        requests.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Recorded(exchange.getRequestMethod(), path, exchange.getRequestURI().getRawQuery(), body));

        Response response = responses.entrySet().stream()
                .filter(entry -> path.startsWith(entry.getKey()))
                .map(entry -> entry.getValue().poll())
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(new Response(500, "application/json", "{\"error\":\"no stubbed response for " + path + "\"}"));

        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", response.contentType());
        exchange.sendResponseHeaders(response.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
