# Assistant evals

A golden set of 34 requests to assistant-service, each scored against the catalog rather than
against the model's own account of its answer. ADR-0014 explains the design.

| Run (gpt-6-luna) | Passed | Grounding | Constraints | Cost |
|---|---|---|---|---|
| Baseline, 2026-10-21 | 31/34 (91.2%) | 98.9% (86/87) | 98.3% | $0.013 |
| Prompt v2: titles never translated, stricter off-topic rule | 33/34 (97.1%) | 100% (89/89) | 98.3% | $0.015 |
| Prompt v2, three more runs to set the CI threshold | 33, 33, 32 | 100% | 98.4% | $0.014 |
| Genre filter on vector search (`d-first-contact` passes) | 33/34 | 100% (89/89) | 100% (62/62) | $0.014 |

The first two rows were rescored by the current scorers (`--rescore`). The results are in [`results/`](results/).

## Running it

```bash
make eval-up     # assistant-, search- and recommendation-service and their stores (or make up-all)
make eval        # all scenarios once: about 3 minutes and $0.015 on gpt-6-luna
make eval EVAL_ARGS="--tag filters --repeat 3"
make eval EVAL_ARGS="--rescore evals/results/<file>.json"   # new scorers on saved answers, no model calls
make eval-test   # the scorers' unit tests, offline
```

Python 3.10+ and nothing else: the harness uses only the standard library. It calls the services
directly (assistant :8086, search :8082, recommendation :8084), so the gateway isn't needed.

The model is assistant-service's setting, not the harness's: `OPENAI_CHAT_MODEL` when the stack
starts. Every run records which model answered. `gpt-6.1-sol` fails every scenario for now: it
can't call tools through Chat Completions (ADR-0014 Update 1).

The harness exits 1 if the run stopped early, or if the pass rate is below `--fail-under`.

## In CI

`.github/workflows/assistant-evals.yml` runs the set on `gpt-6-luna` for pull requests that touch
the assistant, its tools' services or `evals/`, and fails under 91%: the lowest of four runs, less
one scenario. It starts the whole stack on a fresh runner, so it takes longer than a local run. It
needs the `OPENAI_API_KEY` repository secret. Run it by hand from the Actions tab to pick another
model or pass `evals/run.py` arguments.

## What each scenario checks

Every recommended title in the answer has to exist in the catalog. A title counts as recommended
when the answer writes it as `**Title (1999)**`, `**Title** (1999)`, `**Title** (1999, 8.1)` or as a
list item: the format the system prompt asks for. A title named in passing isn't scored. On top of
that, each scenario can expect:

| Field | Passes when |
|---|---|
| `min_items`, `max_items` | the number of recommended titles is in range; `max_items: 0` for a request to decline |
| `constraints` | every recommended movie's **catalog record** matches, e.g. `{"field": "year", "op": "between", "value": [1990, 1999]}`; ops: `eq gt gte lt lte between in contains contains_any` |
| `from_recommendations` | every movie comes from the user's own recommendations (`user` sets the `X-User-Id`) |
| `contains_any`, `excludes` | the answer text contains one of these, or none of those (case-insensitive) |
| `ignore_claims` | these titles may be named without counting as recommendations, e.g. "I couldn't find **Oppenheimer (2023)**" |

Every scenario also fails if the stream ends in an `error` event.

A grounding failure says why: `not_in_catalog`, or `wrong_year` when the title exists a year off.
Titles are compared after normalizing case, accents, apostrophes and `&`. "The Arrival" and
"Arrival" stay different movies.

## Layout

- `evalkit/`: the parts that know nothing about movies, so the incident copilot can reuse them:
  - `scorers.py`: grounding, constraints, count, subset and text checks over plain dicts;
  - `sse.py`: a server-sent-events client that times the first token;
  - `metrics.py`: Prometheus counter deltas.
- `movies.py`: the movie-specific half. It pulls `(title, year)` claims out of an answer and looks
  each one up in search-service. It lists a whole year once and matches the exact title, because
  keyword search can't find a stopword title like "Up".
- `scenarios/assistant.json`: the golden set. Users `1` and `7` are seeded fans from
  `scripts/seed-demo-data.sh`.
- `prices.json`: USD per 1M tokens, for the cost of a run.
- `results/`: one JSON file per run: a summary, then every answer with its checks, tokens, tool
  calls and timings.

## How tokens, cost and tool calls are measured

The harness reads assistant-service's `/actuator/prometheus` before and after each scenario. Spring
AI's `gen_ai_client_token_usage_total` and `spring_ai_tool_seconds_count` give that scenario's
tokens and tool calls, so the service needs no eval-only code. This only works because scenarios
run one at a time on a stack nothing else is using.

## Limits

- **Cost is an upper bound.** The token metric doesn't separate cached input, which is billed at a
  tenth of the price.
- **`tool_errors` is always 0.** A tool that fails returns its error to the model as the tool
  result, and Spring AI's metric still records the call as `error="none"`.
- **A cold stack scores the infrastructure.** After a restart, the first vector searches can take
  over the tools' 6s timeout. The warm-up varies its queries until vector search is under 2s, and
  warns if it never gets there.
- **The claim extractor depends on the answer format.** An answer that names movies without a
  year scores as having no recommendations. Scenarios with `min_items` catch that.
- **No relevance score yet.** "A tense courtroom drama" passes with any real movie. The LLM judge,
  calibrated against hand labels, comes next.
