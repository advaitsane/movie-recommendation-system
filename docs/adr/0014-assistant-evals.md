# ADR 0014: Assistant evals scored against the catalog, from outside the service

**Status:** Accepted (deterministic scorers, and a CI gate on gpt-6-luna since Update 1; the LLM
judge is a follow-up)
**Date:** 2026-10-21

## Context
assistant-service (ADR-0013) answers in free text, so its tests only prove the wiring: they run
against a stub of the OpenAI API, and they say nothing about whether the answers are any good. Three
changes are waiting on that:
- a grounding guard against invented titles;
- the decision on structured `MovieQuery` extraction;
- any prompt or model change.

Each one needs a before and after number. Without them, a prompt edit that breaks the year filter
looks like an improvement.

Most of what makes an answer wrong here can be checked without a model:
- **Grounding:** an invented title isn't in the catalog.
- **Constraints:** a "90s comedy" that isn't from the 90s, or isn't a comedy, contradicts the
  catalog's own record.
- **Count:** an off-topic request that gets answered anyway returns titles when it should return none.

The constraint here is the 8 GB laptop. The full stack swaps on a 4 GB Docker VM (ADR-0010
Update 9), so the evals run on `make eval-up`: the three services the tools reach, about 2.2 GB.

## Decision
**A black-box harness in Python (standard library only, `evals/`) that sends each golden scenario
to `POST /api/assistant/chat` and scores the answer against search-service's catalog.**

- **The catalog is the ground truth, not the model.** The harness pulls the recommended
  `(title, year)` pairs out of the answer, using the format the system prompt asks for. It looks
  each one up in the catalog and checks the constraints on the catalog's record. A model that
  writes "1994" next to a 1996 movie fails, whatever it claims.
- **Deterministic scorers first.** They cover grounding, constraints, count, a subset of the
  user's recommendations, and required or forbidden text. They are free, they give the same
  verdict on the same answer, and `--rescore` reapplies them to saved answers. A scorer fix is
  then measured without the model's run-to-run variance. The LLM judge comes later, for the one
  thing they can't check (relevance), calibrated against hand labels.
- **The scorers know nothing about movies.** `evalkit/` takes dicts and expectations. `movies.py`
  is the only domain code: claim extraction and catalog lookup. The incident copilot gets the same
  scorers with its own adapter.
- **Tokens, cost and tool calls come from the service's existing Prometheus counters.** The
  harness takes a delta around each scenario, with scenarios run one at a time. The service has
  no eval-only code path.
- **`gpt-6-luna` for development runs** (about $0.015 for the 34 scenarios). `gpt-6.1-sol` was to
  be for baselines and the CI gate; it can't call tools yet (Update 1).

## Alternatives considered
- **JUnit evals inside assistant-service**, calling `ChatClient` with instrumented tools. This
  would see the tool results directly, so grounding could mean "returned by a tool" rather than
  "in the catalog". Rejected:
  - it couples the evals to Spring and to this service's internals;
  - it can't score a copilot written in Python, which is still an open question before Phase 4.

  An HTTP harness scores any assistant that streams SSE.
- **Grounding against tool results**, through an SSE event carrying them or through traces.
  Stricter: a real catalog movie that no tool returned would also fail. Rejected for now:
  - an SSE event would change the API for the evals' sake;
  - traces make the harness depend on Jaeger.

  The step 4 grounding guard runs inside the service, where tool results are available.
- **Keyword search to look titles up.** Rejected after it failed in testing:
  - "Up" is a stopword and returns nothing;
  - "A Fish Tale" falls outside the first page.

  The harness lists a year's movies once (at most about 1,100, in pages of 100), caches them and
  matches the exact normalized title.
- **An eval framework** (promptfoo, DeepEval, Ragas). Each one brings its own runner and config
  format. Most of them centre on an LLM judge, and what this needs first is catalog lookups. The
  harness is about 630 lines including the tests, with no dependencies, and runs anywhere
  `python3` does.
- **An LLM judge from the start.** Rejected for now:
  - it costs money on every run;
  - it needs calibration before its scores mean anything;
  - it's non-deterministic, so it hides the difference between a scorer change and a model change.

## Consequences
- Prompt and model changes now come with a number. See Evidence.
- **The claim extractor depends on the answer's format.** An answer that lists movies without
  years scores as recommending nothing. Scenarios with `min_items` catch that, and the extractor
  has unit tests built from real answers.
- **Expectations were adjusted after seeing answers**, each change recorded in the Evidence
  section: `Marooned` was added as a valid stranded-astronaut film, and `ignore_claims` was
  added. A golden set needs this, but each adjustment has to be justified by the catalog, not by
  wanting a pass.
- **Cost is an upper bound.** The token metric doesn't separate cached input.
- **Tool failures are invisible in the metrics.** A `ToolCallException` goes back to the model as
  the tool result, and `spring_ai_tool_seconds_count` still says `error="none"`. That matters for
  step 8's dashboards too.
- **Runs are sequential and need a dedicated stack.** Parallel runs would mix up the metric deltas.
- Follow-ups:
  1. the LLM judge for relevance, calibrated on a hand-labelled subset;
  2. the CI gate, with a threshold set from repeated runs, not one (done on `gpt-6-luna`,
     Update 1);
  3. a tool-error count from a source that sees the exception;
  4. multi-turn scenarios.

## Evidence
All runs on `gpt-6-luna`, 34 scenarios, scored by the final scorers (`--rescore`). Files are in
`evals/results/`.

| | Baseline | Prompt v2 |
|---|---|---|
| Passed | 31/34 (91.2%) | **33/34 (97.1%)** |
| Grounding | 98.9% (86/87) | **100% (89/89)** |
| Constraints (recommended movies matching the request) | 98.3% (58/59) | 98.3% (57/58) |
| Cost of the run | $0.0127 | $0.0146 |
| Answer time p50 / p95 | 4.5s / 7.5s | 4.1s / 10.8s |
| Model calls / tool calls | 89 / 51 | 95 / 58 |

**Triage of the baseline's four failures:**

| Scenario | Cause | Repeats | Outcome |
|---|---|---|---|
| `f-thriller-2005-7` | **Scorer.** It read "**V for Vendetta** (2005, 8.2)" as no claim. All five movies were valid. | — | Extractor fixed, with a test |
| `x-code` | **Prompt.** Asked for a Python function, the model wrote one. | 3/3 failed | Prompt v2: 4/4 pass |
| `l-french-90s-comedy` | **Prompt.** It translated the catalog's "Rendezvous in Paris" to "Rendez-vous à Paris". | failed 2 of 4 | Prompt v2: 4/4 pass |
| `d-first-contact` | **Retrieval and model.** Vector search ranks *The Visit* (2015, Documentary) above seven sci-fi films. The model sees the genre and picks it anyway, often alone. | 2 of 3 still fail | A genre filter on vector search: 4/4 pass (Update 2) |

Prompt v2 added two rules to `prompts/system.st`:
- "never translate a title";
- for a non-movie request, "do no part of it, not even a short example".

**What the harness itself got wrong, and how it was fixed:**
- **Apostrophes.** "Herod’s Law" (curly) didn't match "Herod's Law" (straight). The normalizer
  now drops both before comparing.
- **The rating inside the brackets**, as in "(2005, 8.2)". Fixed above.
- **Cold vector index.** After a Docker restart, vector searches took 12.4s and 5.7s against the
  tools' 6s timeout. One run's `d-first-contact` failed on "semantic search is unavailable", which
  scored the infrastructure. The warm-up now varies its queries until a round is under 2s; the
  next run's first round was 1.36s.
- **Naming the title the user asked about.** The answer said "I couldn't find **Oppenheimer
  (2023)** in the catalog", which is correct, but was scored as an invented recommendation (1 of
  4 runs). Not-in-catalog scenarios now list that title in `ignore_claims`.
- **Language when tools fail.** On a cold stack where both searches it tried timed out, one English
  question got its answer in Spanish. It hasn't happened on a warm stack. It's worth a scenario once tool
  failures can be injected.

## Update 1 (2026-10-21): a CI gate on gpt-6-luna, because gpt-6.1-sol can't call tools

**The first runs on `gpt-6.1-sol` failed all 34 scenarios**, each one in under a second with
`assistant_unavailable`. They spent nothing, because every request was rejected:

- assistant-service sends `reasoning_effort: none`, since `gpt-6-luna` refuses function tools
  with reasoning on Chat Completions (ADR-0013). `gpt-6.1-sol` rejects `none`: "Supported values
  are: 'low', 'medium', 'high', and 'xhigh'".
- With `low`, or with the field left out, it gives the error `gpt-6-luna` gives: "Function tools
  with reasoning_effort are not supported for gpt-6.1-sol in /v1/chat/completions. To use
  function tools, use /v1/responses".
- The same request with a tool through `/v1/responses` worked.

Spring AI 2.0.1's OpenAI module only uses Chat Completions. So no setting makes `gpt-6.1-sol` work
with this service; it needs a chat model built on the Responses API. The run is kept as
`results/2026-10-21T155822Z-gpt-6.1-sol-tools-rejected.json`.

The same runs turned up a setup bug: `docker-compose.yml` didn't pass `OPENAI_CHAT_MODEL` to the
container, so the README's way to switch models had no effect. It's passed now.

**Decision: gate on `gpt-6-luna` now** (`.github/workflows/assistant-evals.yml`), and move the gate
to `gpt-6.1-sol` once assistant-service is on the Responses API. A gate on the development model
still catches what it's for: a prompt, tool or retrieval change that breaks grounding or the
filters. It costs about $0.015 a run instead of about $0.30.

**Threshold: 91%** (`--fail-under 0.91`), so 31/34 passes and 30/34 fails. It's the lowest of four
runs on prompt v2, less one scenario:

| Run (gpt-6-luna, prompt v2) | Passed | Grounding | Constraints | Cost | p50 / p95 |
|---|---|---|---|---|---|
| Prompt v2 (the Evidence above) | 33/34 | 100% (89/89) | 98.3% | $0.0146 | 4.1s / 10.8s |
| Gate run 1 | 33/34 | 100% (93/93) | 98.4% | $0.0135 | 3.8s / 10.2s |
| Gate run 2 | 33/34 | 100% (93/93) | 98.4% | $0.0144 | 3.9s / 7.6s |
| Gate run 3 | 32/34 | 100% (89/89) | 98.4% | $0.0140 | 4.6s / 9.1s |

- `d-first-contact` failed in all four, on *The Visit* as before. It stays in the set: the
  threshold already counts it, and it starts passing when retrieval is fixed.
- Run 3's other failure was `p-scifi-fan`. Its one `getMyRecommendations` call failed with "Error
  while extracting response", and the model answered that it couldn't reach the recommendations.
  The log has no cause; at 9.2s for the answer, a read timeout is likely. The metrics recorded it
  as `error="none"` (follow-up 3).

One flaky scenario is the margin. Four runs are few: if the gate fails on a change that shouldn't
move the score, rerun it, and widen the margin only if that keeps happening.

**What the CI job does differently from `make eval-up`:** a fresh runner has no data volumes, so it
starts the whole stack with `make up`. That means the ~400 MB download, and embedding about 21k
plots, which costs a few cents of the embedding API. It then seeds the demo fans and waits until
users 1 and 7 get personal recommendations instead of the popular fallback. It runs on pull requests
that touch assistant-, search- or recommendation-service, `evals/` or the compose files, and by
hand with a choice of model. It needs the `OPENAI_API_KEY` repository secret; pull requests from
forks don't get secrets, so it skips them. The pass rate, the failed checks and the results file
go on the run's page.

## Update 2 (2026-10-21): a genre filter on vector search fixes `d-first-contact`

`d-first-contact` failed in every run on *The Visit* (2015, Documentary), which vector search
ranked first. `findMoviesByDescription` now takes an optional `genre`, which search-service
applies as a `$vectorSearch` pre-filter (ADR-0002, update of the same date). The tool's
description says to set it when the user names a genre; the prompt is unchanged.

| Run (gpt-6-luna) | Passed | Grounding | Constraints | Cost | p50 / p95 |
|---|---|---|---|---|---|
| Gate runs 1–3 (Update 1) | 33, 33, 32 /34 | 100% | 98.4% | $0.014 | 3.8–4.6s / 7.6–10.2s |
| Genre filter | 33/34 | 100% (89/89) | 100% (62/62) | $0.0139 | 4.3s / 8.4s |

- `d-first-contact` passed 4 of 4: 3 alone, 1 in the gate run. The traces show the model sending
  `genre=Sci-Fi` without being asked.
- The scenario passes, but the answers are thin: three of the four recommend only *The Day the
  Earth Stood Still* (1951). The scenario asks for at least one movie, so the scorers can't see
  that. Whether one is enough is a relevance question, for the judge (follow-up 1).
- The one failure was `p-scifi-fan` again, as in gate run 3: its `getMyRecommendations` call to
  recommendation-service was cancelled ("Request cancelled", the read timeout), and nothing in
  this change touches that path. Two of five runs now, so it is the next thing to look at
  rather than noise.
- This is the evidence step 3 asked for. With the filter in, `MovieQuery` has one reason left,
  year ranges ("90s"), and those scenarios pass today.


## Update 3 (2026-10-22): `p-scifi-fan` was a timeout budget, now fixed

`p-scifi-fan` failed in 2 of 6 runs, with `getMyRecommendations` cancelled at the assistant's 6s
tool timeout. recommendation-service looked up its three seed movies in search-service one after
another, each with a 3s read timeout: up to 9s. It now looks them up in parallel under one 3s
deadline (ADR-0010 Update 11, with the latency measurements).

| Run (gpt-6-luna) | Passed | Constraints | Cost | p50 / p95 |
|---|---|---|---|---|
| `p-scifi-fan` alone, search-service delayed 1s, cold cache | 3/3 | — | — | — |
| Gate, local | 33/34 | 98.3% | $0.0142 | 4.5s / 9.2s |
| Gate, CI | 34/34 | 100% | $0.0140 | — |

- `p-scifi-fan` passed in all five. Before, a 1s delay meant a 9.1s answer from
  recommendation-service, past the tool timeout.
- An earlier local gate run on this change scored 25/34, at p95 23s. That was the host, not the
  change: Mongo stalled for minutes at a time, out of memory, and scenarios that never call
  recommendation-service failed too. Part of the load was the unindexed `distinct('genres')`
  that ADR-0002's genre-filter update now fixes. A rerun on a freshly started stack is the one
  above.
- `d-first-contact` failed in both gate runs with the genres index (local and CI, on #38),
  because the model left out the genre and got *The Visit* back. Update 2's 4 of 4 was a small
  sample: the filter works when it's used, but the model doesn't always use it. The CI gate on
  this change passed it without the filter at all. Telling these apart needs the tool calls
  across repeats, not the pass count.
- One more failure in the local run with the genres index was the scorer:
  `f-70s-crime-above-8` answered with two valid movies, written `- *Title* (1972)`, and the
  extractor doesn't read single italics.
