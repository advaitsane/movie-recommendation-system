# ADR 0014: Assistant evals scored against the catalog, from outside the service

**Status:** Accepted (deterministic scorers; the LLM judge and the CI gate are follow-ups)
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
- **`gpt-6-luna` for development runs** (about $0.015 for the 34 scenarios). `gpt-6.1-sol` is for
  baselines and the CI gate.

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
  2. the CI gate on `gpt-6.1-sol`, with a threshold set from repeated runs, not one;
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
| `d-first-contact` | **Retrieval and model.** Vector search ranks *The Visit* (2015, Documentary) above seven sci-fi films. The model sees the genre and picks it anyway, often alone. | 2 of 3 still fail | Open: a genre filter on vector search, or `MovieQuery` (step 3) |

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
