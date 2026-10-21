#!/usr/bin/env python3
"""Runs the golden scenarios against a running assistant-service and scores each answer against
the catalog. Standard library only. Start the services first (make eval-up).

    python3 evals/run.py                      # every scenario, once
    python3 evals/run.py --only f-90s-comedies --repeat 3
    python3 evals/run.py --tag filters --fail-under 0.8
    python3 evals/run.py --rescore evals/results/<file>.json   # new scorers, saved answers, no model calls
"""

import argparse
import json
import statistics
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

import movies
from evalkit import metrics, scorers, sse

HERE = Path(__file__).resolve().parent


def parse_args():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--assistant", default="http://localhost:8086")
    p.add_argument("--search", default="http://localhost:8082")
    p.add_argument("--recs", default="http://localhost:8084")
    p.add_argument("--scenarios", default=str(HERE / "scenarios" / "assistant.json"))
    p.add_argument("--prices", default=str(HERE / "prices.json"))
    p.add_argument("--out", default=str(HERE / "results"))
    p.add_argument("--only", help="comma-separated scenario ids")
    p.add_argument("--tag", help="run only scenarios with this tag")
    p.add_argument("--repeat", type=int, default=1, help="runs per scenario, to see how stable it is")
    p.add_argument("--timeout", type=float, default=120)
    p.add_argument("--no-warmup", action="store_true")
    p.add_argument("--fail-under", type=float, help="exit 1 if the pass rate is below this (0-1)")
    p.add_argument("--label", default="", help="added to the results file name, e.g. the change under test")
    p.add_argument("--rescore", metavar="RESULTS", help="score the answers saved in a results file again")
    return p.parse_args()


def warm_up(args):
    """A cold stack (after a restart, or a laptop back from sleep) makes the first vector searches
    exceed the tools' 6s timeout, which scores the infrastructure instead of the assistant. One
    repeated query only warms part of the index, so this varies them until a round is fast."""
    queries = ["an astronaut stranded in space", "a heist that goes wrong", "first contact with aliens",
               "a courtroom drama", "a feel-good family comedy", "a detective hunting a serial killer"]
    for round_ in range(1, 6):
        slowest = 0.0
        for q in queries:
            url = f"{args.search}/api/movies/search/vector?" + urllib.parse.urlencode({"q": q, "limit": 8})
            started = time.monotonic()
            try:
                urllib.request.urlopen(url, timeout=30).read()
            except OSError as ex:
                print(f"  warm-up failed: {q}: {ex}", file=sys.stderr)
                slowest = float("inf")
                continue
            slowest = max(slowest, time.monotonic() - started)
        print(f"  warm-up round {round_}: slowest vector search {slowest:.2f}s", file=sys.stderr)
        if slowest < 2.0:
            return
    print("  warm-up: vector search is still slow; tool timeouts may fail scenarios", file=sys.stderr)


def ask(args, scenario):
    """Sends one scenario to the assistant: the answer, its timing, and its tokens and tool calls."""
    user = scenario.get("user", "eval-anonymous")
    before = metrics.scrape(f"{args.assistant}/actuator/prometheus")
    result = {"id": scenario["id"], "tags": scenario.get("tags", []), "message": scenario["message"]}
    try:
        stream = sse.post(f"{args.assistant}/api/assistant/chat", {"message": scenario["message"]},
                          {"X-User-Id": user}, args.timeout)
        answer = stream.text()
        error = stream.first("error")
        result.update(answer=answer, first_token_s=stream.first_token_s, total_s=round(stream.total_s, 2),
                      error=error)
    except OSError as ex:
        result.update(answer="", first_token_s=None, total_s=None, error={"code": "client", "message": str(ex)})
    # Spring AI records the last model call's usage once the stream completes.
    time.sleep(0.5)
    used = metrics.delta(before, metrics.scrape(f"{args.assistant}/actuator/prometheus"))
    tokens = metrics.sum_by(used, "gen_ai_client_token_usage_total", "gen_ai_token_type")
    result["model"] = next(iter(metrics.sum_by(used, "gen_ai_client_token_usage_total", "gen_ai_response_model")), None)
    result["tokens"] = {"input": int(tokens.get("input", 0)), "output": int(tokens.get("output", 0))}
    result["model_calls"] = int(sum(metrics.sum_by(used, "gen_ai_client_operation_seconds_count", "error").values()))
    tool_calls = metrics.sum_by(used, "spring_ai_tool_seconds_count", "spring_ai_tool_definition_name")
    result["tool_calls"] = {k: int(v) for k, v in sorted(tool_calls.items())}
    result["tool_errors"] = int(sum(v for k, v in metrics.sum_by(
        used, "spring_ai_tool_seconds_count", "error").items() if k != "none"))
    return result


def score(args, scenario, result, catalog):
    """Adds the checks to an answer from ask(), or to one saved by an earlier run."""
    answer, error, user = result["answer"], result["error"], scenario.get("user", "eval-anonymous")
    expect = scenario.get("expect", {})
    # A title the user asked about can be named, typically to say it isn't in the catalog.
    ignored = {movies.normalize(c) for c in expect.get("ignore_claims", [])}
    claims = [c for c in movies.extract_claims(answer) if movies.normalize(f"{c[0]} ({c[1]})") not in ignored]
    grounding = scorers.grounding(claims, catalog.resolve)
    items = [record for _, record in grounding.grounded]
    checks = [scorers.Check("answered", error is None, json.dumps(error) if error else ""), grounding.check]
    if "min_items" in expect or "max_items" in expect:
        checks.append(scorers.count(claims, expect.get("min_items"), expect.get("max_items")))
    constraint_result = None
    if expect.get("constraints"):
        constraint_result = scorers.constraints(items, expect["constraints"], key="title")
        checks.append(constraint_result.check)
    if expect.get("from_recommendations"):
        reference = movies.recommendations(args.recs, user, limit=8)
        checks.append(scorers.subset(items, reference, key="ref"))
    if expect.get("contains_any") or expect.get("excludes"):
        checks.append(scorers.text(answer, expect.get("contains_any"), expect.get("excludes")))

    result["claims"] = [f"{t} ({y})" for t, y in claims]
    result["ungrounded"] = [{"claim": f"{t} ({y})", "reason": r} for (t, y), r in grounding.ungrounded]
    result["constraint_items"] = constraint_result.total if constraint_result else 0
    result["constraint_satisfied"] = constraint_result.satisfied if constraint_result else 0
    result["checks"] = [vars(c) for c in checks]
    result["passed"] = all(c.passed for c in checks)
    return result


def percentile(values, q):
    values = sorted(v for v in values if v is not None)
    if not values:
        return None
    return round(values[min(len(values) - 1, int(q * len(values)))], 2)


def summarize(results, prices):
    claims = sum(len(r["claims"]) for r in results)
    ungrounded = sum(len(r["ungrounded"]) for r in results)
    items = sum(r["constraint_items"] for r in results)
    satisfied = sum(r["constraint_satisfied"] for r in results)
    tokens_in = sum(r["tokens"]["input"] for r in results)
    tokens_out = sum(r["tokens"]["output"] for r in results)
    models = sorted({r["model"] for r in results if r["model"]})
    # Upper bound: the token metric doesn't split out cached input, which is billed lower.
    cost = 0.0
    for r in results:
        price = prices.get(r["model"] or "", {})
        cost += r["tokens"]["input"] / 1e6 * price.get("input", 0) + r["tokens"]["output"] / 1e6 * price.get("output", 0)
    return {
        "scenarios": len(results),
        "passed": sum(r["passed"] for r in results),
        "pass_rate": round(sum(r["passed"] for r in results) / len(results), 3),
        "grounding_rate": round((claims - ungrounded) / claims, 3) if claims else None,
        "claims": claims,
        "ungrounded": ungrounded,
        "constraint_rate": round(satisfied / items, 3) if items else None,
        "constraint_items": items,
        "errors": sum(r["error"] is not None for r in results),
        "models": models,
        "tokens": {"input": tokens_in, "output": tokens_out},
        "cost_usd": round(cost, 4),
        "cost_is_upper_bound": True,
        "model_calls": sum(r["model_calls"] for r in results),
        "tool_calls": sum(sum(r["tool_calls"].values()) for r in results),
        "tool_errors": sum(r["tool_errors"] for r in results),
        "first_token_s": {"p50": percentile([r["first_token_s"] for r in results], 0.5),
                          "p95": percentile([r["first_token_s"] for r in results], 0.95)},
        "total_s": {"p50": percentile([r["total_s"] for r in results], 0.5),
                    "p95": percentile([r["total_s"] for r in results], 0.95)},
    }


def main():
    args = parse_args()
    scenarios = json.loads(Path(args.scenarios).read_text())["scenarios"]
    if args.only:
        wanted = set(args.only.split(","))
        scenarios = [s for s in scenarios if s["id"] in wanted]
    if args.tag:
        scenarios = [s for s in scenarios if args.tag in s.get("tags", [])]
    if not scenarios:
        sys.exit("no scenarios selected")
    prices = json.loads(Path(args.prices).read_text())["per_million_tokens"]

    catalog = movies.Catalog(args.search)
    if args.rescore:
        by_id = {s["id"]: s for s in scenarios}
        saved = [r for r in json.loads(Path(args.rescore).read_text())["results"] if r["id"] in by_id]
        args.label = args.label or "rescored"
        pending = [(by_id[r["id"]], r) for r in saved]
    else:
        if not args.no_warmup:
            warm_up(args)
        pending = [(s, None) for s in scenarios for _ in range(args.repeat)]
    results, attempts, aborted = [], {}, None
    # One at a time: the metric deltas are only per scenario if nothing else runs in between.
    for scenario, saved_result in pending:
        try:
            r = saved_result if saved_result is not None else ask(args, scenario)
        except OSError as ex:
            # The assistant's metrics endpoint is gone, so the stack is down, not the answer wrong:
            # stop, and keep what was scored so far.
            aborted = f"stopped at {scenario['id']}: {ex}"
            print(f"ABORT {aborted}", file=sys.stderr)
            break
        r = score(args, scenario, r, catalog)
        attempts[r["id"]] = attempts.get(r["id"], 0) + 1
        r["attempt"] = attempts[r["id"]]
        results.append(r)
        failed = [f"{c['name']}: {c['detail']}" for c in r["checks"] if not c["passed"]]
        print(f"{'PASS' if r['passed'] else 'FAIL'}  {r['id']:<28} {r['total_s'] or 0:6.1f}s  "
              f"tools={sum(r['tool_calls'].values())}  {' | '.join(failed)}", file=sys.stderr)

    if not results:
        sys.exit(aborted or "no results")
    summary = summarize(results, prices)
    summary["aborted"] = aborted
    started = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H%M%SZ")
    name = "-".join(filter(None, [started, "+".join(summary["models"]) or "unknown", args.label]))
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    path = out / f"{name}.json"
    path.write_text(json.dumps({"summary": summary, "results": results}, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps(summary, indent=2))
    print(f"results: {path}", file=sys.stderr)
    if aborted or (args.fail_under is not None and summary["pass_rate"] < args.fail_under):
        sys.exit(1)


if __name__ == "__main__":
    main()
