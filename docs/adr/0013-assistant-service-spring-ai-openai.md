# ADR 0013: assistant-service as a separate service on Spring AI 2.0, OpenAI and Jackson 3

**Status:** Accepted (drafted during the Phase 0 spike, accepted in the PR that builds
assistant-service; see "Implementation findings")
**Date:** 2026-10-07 (accepted 2026-10-09)

## Context
The next step adds a conversational assistant: a user asks for movies in plain language, and an
LLM answers by calling the existing services as tools. That raises four questions before any
code is written:

1. Does it go into recommendation-service, or into a new service?
2. Which Spring AI release runs on this repo's Spring Boot 4.1.1?
3. Which LLM provider and models?
4. Which Jackson line does it use? Spring Boot 4 defaults to Jackson 3 (`tools.jackson`), but
   every existing service opts back into Jackson 2 (`com.fasterxml.jackson`), and Spring AI 2.0
   is built on Jackson 3.

## Decision
- **A separate `assistant-service` (port 8086)** behind api-gateway, rather than new endpoints in
  recommendation-service. LLM calls have completely different latency, cost and failure modes
  from the rest of the system; a slow or failing provider must not be able to take
  recommendations down with it.
- **Spring AI 2.0.1** (`spring-ai-bom`). Its autoconfigure modules depend on Spring Boot 4.1.1,
  the version every other service runs, so no service needs a different Boot line.
- **OpenAI** through `spring-ai-starter-model-openai`: `gpt-6-luna` by default
  (`OPENAI_CHAT_MODEL`), `gpt-6.1-sol` kept for quality baselines. The OpenAI account already pays
  for search-service's embeddings. Another provider can be added later behind
  `spring.ai.model.chat`. CI never calls a real model: the tests run against a
  stub of the OpenAI API (see "Implementation findings").
- **assistant-service stays on Jackson 3**, Boot 4's default, and does not add
  `spring-boot-jackson2`. The existing services stay on Jackson 2 for now (see below).

## Jackson 2 in the existing services, Jackson 3 in assistant-service

### Why the existing services opted back into Jackson 2
Until now, this was recorded only in a one-line `pom.xml` comment in each service. The reasons,
from the code:

- **Kafka serialization.** catalog-, review- and user-service produce events with spring-kafka's
  `JsonSerializer`, and search- and recommendation-service consume them with `JsonDeserializer`.
  In spring-kafka 4.x these are the Jackson 2 classes; the Jackson 3 equivalents are
  `JacksonJsonSerializer` / `JacksonJsonDeserializer`. Each `Kafka*Config` passes the service's
  own Jackson 2 `ObjectMapper` in, so events and HTTP responses serialize the same way.
- **Custom mappers in every service.** Each service builds its `ObjectMapper` by hand in
  `ObjectMapperConfig`: ISO-8601 dates (`JavaTimeModule`, `WRITE_DATES_AS_TIMESTAMPS` off), a
  hex-string `ObjectId` serializer for the Mongo services, and every `Module` bean in the context
  (including Spring Data's `PageModule`, so `Page<…>` responses serialize correctly). Each
  `application.yml` also sets `spring.jackson2.default-property-inclusion: non_null`.
- **Size of the change.** About 30 main-source files import `com.fasterxml.jackson` (DTOs, event
  payloads, mappers, serializers). With Jackson 2 opted back in, all of that kept working
  unchanged during the move to Boot 4. Moving to Jackson 3 changes every service and the event
  payloads all services share, and needs its own verification.

### Why assistant-service doesn't follow them
- Spring AI 2.0 is built on Jackson 3 (`jackson-databind` 3.1.5 in the 2.0.1 BOM). Adding Jackson
  2 to assistant-service would leave it with two JSON stacks: Spring MVC converting with one
  mapper, while Spring AI uses the other for tool arguments, structured output and provider
  requests. Settings such as property inclusion or date format would then differ depending on
  which path a value takes.
- assistant-service reads the other services over HTTP and doesn't take part in the Kafka events.
  What has to match is the JSON on the wire, not the Java library behind it. The settings that
  shape that JSON (ISO dates, `non_null`, `ObjectId` as a hex string) are defaults or simple
  options in Jackson 3, and the tool clients only deserialize DTOs.
- The Phase 0 spike (Boot 4.1.1 + Spring AI 2.0.1 + the OpenAI starter, streaming
  `Flux<String>` as `text/event-stream`) ran with Jackson 3 as the only mapper Spring configures.
  Jackson 2 jars are still on the classpath, as private dependencies of two libraries: the
  official OpenAI Java SDK (`openai-java-core`, which Spring AI 2.0's OpenAI module is built on)
  and springdoc's `swagger-core`. Neither touches Spring MVC's or Spring AI's JSON, so the
  two-stacks problem above doesn't arise.

### When the existing services should move to Jackson 3
Jackson 2 support is on its way out, so the split above is temporary:

- Spring Boot's `Jackson2AutoConfiguration` (the `spring-boot-jackson2` module) is
  `@Deprecated(since = "4.0.0", forRemoval = true)`, and its Javadoc says *"for removal in 4.3.0
  in favor of Jackson 3"*.
- spring-kafka's `JsonSerializer` is `@Deprecated(forRemoval = true, since = "4.0")` *"in favor
  of JacksonJsonSerializer for Jackson 3"*. `JsonDeserializer` is the same.

Move the existing services **before upgrading to Spring Boot 4.3**, one service at a time:

- Swap the Kafka serializer classes, and port `ObjectMapperConfig` and the `ObjectId`
  serializer.
- Switch the imports. Most annotations (`@JsonProperty`, `@JsonInclude`,
  `@JsonIgnoreProperties`) stay in `com.fasterxml.jackson.annotation` in Jackson 3. `databind`
  and `core` move to `tools.jackson`.
- Remove `spring-boot-jackson2` and rename the `spring.jackson2.*` properties to `spring.jackson.*`.
- Producers should move before or together with their consumers. Check that the event JSON is
  identical before and after (a captured payload compared in a test is enough).

## Alternatives considered
- **Endpoints inside recommendation-service.** Rejected: it would couple recommendations'
  latency and availability to an LLM provider, and the two have different scaling and cost
  profiles.
- **Run assistant-service on Spring Boot 3.x with Spring AI 1.x.** The fallback if no Spring AI
  release supported Boot 4.1. It isn't needed, since 2.0.1 does, and it would have left one
  service on a different Boot line.
- **assistant-service on Jackson 2, matching the other services.** Rejected for the two-JSON-stacks
  reason above. Removing it would also be the first job of the Boot 4.3 migration.
- **Migrate every service to Jackson 3 first, then add the assistant.** Rejected for now: it
  touches every working service and the shared event format, and delays the assistant for a
  change that has no user-visible benefit yet. It's scheduled as the pre-Boot-4.3 task above
  instead.
- **Local models through Ollama.** Rejected: the development machine (8 GB RAM, no GPU) can't run
  a model that calls tools reliably at a usable speed.

## Consequences
- An LLM outage or slowdown is limited to the assistant; the rest of the system doesn't depend
  on it.
- For a while the repo runs two Jackson lines: Jackson 2 in five services, Jackson 3 in
  assistant-service and config-server's tests. Anyone moving code between services has to watch
  the imports.
- Follow-up work this creates: the Jackson 3 migration of the existing services, required before
  Spring Boot 4.3.
- Spring AI's OpenAI client uses Chat Completions, not the Responses API: reasoning tokens are
  counted, but there's no reasoning text.
- Streamed OpenAI HTTP spans aren't nested under Spring AI's chat-model span in Jaeger. To be
  handled in the observability step. (The OpenAI SDK uses its own okhttp client, so Spring's
  `RestClient` observation doesn't apply to it.)
- Function tools need `reasoning_effort: none` on Chat Completions (see "Implementation findings"),
  so the assistant gets no model reasoning while it uses tools. Reasoning with tools needs the
  Responses API, which Spring AI's OpenAI module doesn't use yet.

## Implementation findings
Building the service (`services/assistant-service`) turned up the following.

- **Tools and reasoning don't mix on Chat Completions.** The first live request with tools failed
  with `400: Function tools with reasoning_effort are not supported for gpt-6-luna in
  /v1/chat/completions. To use function tools, use /v1/responses or set reasoning_effort to
  'none'`. The spike never sent tools, so it never hit this. `spring.ai.openai.chat.reasoning-effort`
  is set to `none`, and a test asserts that every request sends it.
- **The model can loop on tools.** On a cold stack, semantic search timed out (search-service's
  first `$vectorSearch` took 7.1s, past the tool's 6s read timeout). The model then called
  `searchMovies` five times with small variations: six tool calls, seven model calls and 18s
  before the first token, all visible as `execute_tool` and `chat` spans in one Jaeger trace. Spring AI's tool-call limits now
  cap this at 3 calls per tool and 6 per answer, with `on-limit-exceeded: return-error-response`,
  so the model gets an error as the tool result and answers with what it has. The system prompt
  also asks it to stop once a search returns usable results. A test covers the per-tool limit.
- **Stream errors.** The spike's bare HTTP 500 is fixed. The response is a fixed sequence of
  server-sent events (`conversation`, `token`..., then `done` or `error`). A failure after the
  stream starts ends it with an `error` event, since the status is already 200. A failing *tool*
  doesn't fail the stream: its message goes back to the model as the tool result
  (`spring.ai.tools.throw-exception-on-error: false`), and the model says which part is missing.
- **Tokens travel as JSON** (`{"text": ...}`). SSE parsers strip one leading space from each data
  line, and model tokens usually start with one.
- **No circuit breaker on the gateway route.** Every other route has one, but its time limiter
  bounds the whole response (4s by default), and a healthy stream runs past that.
  assistant-service reports its own failures in the stream instead.
- **The user id is never a tool argument.** `getMyRecommendations` reads it from Spring AI's tool
  context, filled from the gateway's `X-User-Id` header. Chat memory is keyed by user and
  conversation id, so a leaked conversation id doesn't expose another user's conversation. Both
  are covered by tests.
- **Memory.** The container used 296MiB of a 300MiB limit after four chats (256MiB anonymous
  memory, from the cgroup's `memory.stat`). The OpenAI SDK brings okhttp and the Kotlin runtime on
  top of Spring AI and springdoc. The limit is 384MiB, as for search-service; it sat at 67% after
  more chats. Not load tested.
- **Chat memory lives in the instance's heap** (Spring AI's in-memory repository, a window of 20
  messages per conversation). It is lost on restart and doesn't scale past one instance. The
  number of conversations isn't bounded, so a long-running instance grows until restarted. A
  shared store (Redis is already in the stack) is the fix when it matters.

Live, through api-gateway with a real login, on `gpt-6-luna` once the stack was warm:

| Request | Tool calls (from the Jaeger trace) | Model calls | First token | Total |
|---|---|---|---|---|
| "A tense heist movie from the 1990s, something like Heat" | `findMoviesByDescription`, `searchMovies` ×2 | 4 | 5.3s | 5.6s |
| Follow-up in the same conversation: "Who directed the first one, and who is in it?" | `searchMovies`, `getMovieDetails` | 3 | 3.6s | 3.8s |
| "What should I watch tonight?" | `getMyRecommendations` | 2 | 5.6s | 6.3s |
| "A quiet science fiction film about first contact" | `findMoviesByDescription` | 2 | 7.1s | 7.4s |

First-token time is mostly the model calls before the answer starts, about 1-3s each; the tool
calls themselves took 20-500ms once search-service was warm. The answers named only movies the
tools returned. The follow-up resolved "the first one" from chat memory.

## Evidence
- Phase 0 spike (branch `spike/spring-ai-streaming`; the throwaway `services/assistant-spike`
  module was deleted, not committed, once this ADR was written):
  - Streaming works end to end on `gpt-6-luna`: about 4–5 s to the first token, then steady
    tokens (199 chunks over about 2 s for a longer answer).
  - Jackson 3 is the only mapper Spring configures (see above for the Jackson 2 jars that
    libraries bring in).
  - A failed provider call (a 401 for a bad key) reaches the client as a bare HTTP 500, with
    `MessageAggregator: Aggregation Error` only in the server log. Error handling for streams is
    left to a later step.
- Deprecation notices, from the 4.1.1 source jars of `spring-boot-jackson2` and `spring-kafka`
  (`Jackson2AutoConfiguration`, `JsonSerializer`), quoted above.
- Version alignment: the `spring-ai-autoconfigure-model-*` 2.0.1 POMs depend on
  `spring-boot-autoconfigure` 4.1.1.
