# assistant-service (:8086)

A conversational movie assistant. The user asks in plain language ("a tense heist movie from the
1990s, something like Heat"), and an LLM answers by calling search-service and
recommendation-service as tools, streaming the answer back as server-sent events. It has no
database and doesn't use Kafka. See
[ADR-0013](../../docs/adr/0013-assistant-service-spring-ai-openai.md) for why it is a separate
service and why it runs on Spring AI 2.0, OpenAI and Jackson 3.

## Overview

- **Exposes:** `POST /api/assistant/chat`, streamed as `text/event-stream`. See
  `AssistantController` or `/swagger-ui.html`.
- **Tools the model can call** (`MovieTools`, all read-only):

  | Tool | Calls | Used for |
  |---|---|---|
  | `searchMovies` | search-service `GET /api/movies/search` | titles, keywords, genre/year/rating filters |
  | `findMoviesByDescription` | search-service `GET /api/movies/search/vector` | a described story, mood or theme |
  | `findSimilarMovies` | search-service `GET /api/movies/search/{id}/similar` | "more like this one" |
  | `getMovieDetails` | search-service `GET /api/movies/search/{id}` | cast, directors, full plot |
  | `getMyRecommendations` | recommendation-service `GET /api/recommendations/{userId}` | "what should I watch?" |

  Each tool returns a trimmed view of the movie (`MovieSummary`, `MovieDetails`,
  `Recommendation`), since every field is paid for as input tokens.
- **Chat memory:** the last 20 messages of each conversation are resent to the model on every
  turn. Memory is keyed by user and conversation id, and kept in this instance's heap, so it is
  lost on restart.
- **Does not do:** write anything, or let the model choose whose recommendations to read. The user
  id comes from the `X-User-Id` header that api-gateway sets from the JWT, and reaches
  `getMyRecommendations` through Spring AI's tool context, not as a tool argument.

## The stream

```
event:conversation
data:{"conversationId":"c653d237-4223-4fc7-a539-723bcd8f520e"}

event:token
data:{"text":"**Reservoir Dogs (1992)** is"}

event:token
data:{"text":" the best match: ..."}

event:done
data:{}
```

- Send `conversationId` back with the next message to continue the conversation. Omit it to
  start a new one.
- Tokens are JSON (`{"text": ...}`), not raw text: SSE parsers strip one leading space from each
  data line, which would glue words together.
- If the model call fails after the stream has started, the stream ends with
  `event:error` / `data:{"error":"assistant_unavailable","message":"..."}` instead of `done`. The
  HTTP status is already 200 by then. The provider's own error is only logged.
- If a **tool** fails (its service is down, times out or rejects the arguments), the model is told
  so as the tool's result and answers with what the other tools returned. The stream still ends
  with `done`.

Requests rejected before the stream starts get a JSON error: 401 without `X-User-Id`, 400 for a
blank message, a message over 2,000 characters, or a malformed `conversationId`.

## How to run

### Locally (Maven)

```bash
OPENAI_API_KEY=sk-... ./mvnw spring-boot:run
```

Needs search-service and recommendation-service reachable at the defaults below, or overridden
via env vars. Without a key the service still starts, and every answer ends in an `error` event.

| Env var | Default | Notes |
|---|---|---|
| `OPENAI_API_KEY` | (empty) | or `spring.ai.openai.api-key` in a gitignored `application-local.yml` with `SPRING_PROFILES_ACTIVE=local` |
| `OPENAI_CHAT_MODEL` | `gpt-6-luna` | |
| `OPENAI_REASONING_EFFORT` | `none` | must stay `none` for tool calling through Chat Completions (ADR-0013) |
| `SEARCH_SERVICE_URL` | `http://localhost:8082` | |
| `RECOMMENDATION_SERVICE_URL` | `http://localhost:8084` | |
| `SERVER_PORT` | `8086` | |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `http://localhost:4318/v1/traces` | trace export target (Jaeger) |
| `LOG_LEVEL` | `INFO` | |

Limits in `application.yml`:

| Property | Value | Why |
|---|---|---|
| `spring.ai.tools.limits.max-calls-per-tool-default` / `max-total-tool-calls` | 3 / 6 | each tool call is another model round trip; over the limit the model must answer with what it has |
| `spring.ai.openai.timeout` / `max-retries` | 60s / 1 | one model call, and the SDK's own retries |
| `assistant.downstream.read-timeout-ms` | 6000 | one tool call; above recommendation-service's own 5s worst case |
| `assistant.chat.memory-max-messages` | 20 | history resent on each turn |
| `assistant.chat.tool-result-limit` | 8 | movies one tool call returns |
| `spring.mvc.async.request-timeout` | 120s | a streamed answer is an async request; Tomcat's default is 30s |

### Docker / docker-compose

```bash
docker compose up -d --build assistant-service
```

compose passes `OPENAI_API_KEY` from your shell and activates the `local` profile, so either works.
Through the gateway, log in first and send the token:

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/users/login -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"your-password"}' | jq -r .token)
curl -N -X POST localhost:8080/api/assistant/chat \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"A quiet science fiction film about first contact"}'
```

The gateway route has no circuit breaker, unlike the others: its time limiter bounds the whole
response, and a healthy stream runs past it (ADR-0013).

## Observability

Spring AI adds its own observations on top of the usual HTTP ones. In Jaeger, one chat request
shows a `chat gpt-6-luna` span per model call and an `execute_tool <name>` span per tool call, with
the downstream service's span under it. In Prometheus:

- `gen_ai_client_token_usage_total{gen_ai_token_type="input|output"}`: tokens used, per model.
- `gen_ai_client_operation_seconds`: model call latency.

## Tests

```bash
./mvnw verify
```

No test calls OpenAI. `StubHttpServer` stands in for the OpenAI API, search-service and
recommendation-service, and the model's replies are scripted (`OpenAiStream`), so the tests check
what the service sends and how it streams, not what a real model would say.

- `AssistantChatIntegrationTest`: the full path through Spring AI. The event sequence, the system
  prompt, tool definitions and `reasoning_effort` sent to the model, a tool call and its trimmed
  result, the user id taken from the header and not the model's arguments, a failing tool, the
  per-tool call limit, a failing model call, memory per user and conversation, and request
  validation.
- `MovieToolsTest`: the tools on their own. Query parameters, trimming, and the message the model
  gets for a 404, a 400 and an unreachable service.
- `ConfigurationPropertiesValidationTest`: invalid settings fail startup.

The Postman collection in `postman/` covers the request checks and one streamed answer through the
gateway.
