"""Reads Prometheus counters before and after a scenario. With scenarios run one at a time on a
dedicated stack, the difference is that scenario's token usage and tool calls, without changing
the service under test."""

import re
import urllib.request

_LINE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{(.*)\})?\s+(\S+)$')
_LABEL = re.compile(r'(\w+)="((?:[^"\\]|\\.)*)"')


def scrape(url: str) -> dict:
    """{(metric name, frozenset of label pairs): value}"""
    with urllib.request.urlopen(url, timeout=10) as response:
        body = response.read().decode()
    samples = {}
    for line in body.splitlines():
        match = _LINE.match(line)
        if match and not line.startswith("#"):
            name, labels, value = match.groups()
            samples[(name, frozenset(_LABEL.findall(labels or "")))] = float(value)
    return samples


def delta(before: dict, after: dict) -> dict:
    return {k: v - before.get(k, 0.0) for k, v in after.items() if v - before.get(k, 0.0)}


def sum_by(samples: dict, name: str, label: str, where: dict | None = None) -> dict:
    """Totals of one metric grouped by one label, optionally filtered on other labels."""
    totals = {}
    for (metric, labels), value in samples.items():
        pairs = dict(labels)
        if metric != name or any(pairs.get(k) != v for k, v in (where or {}).items()):
            continue
        key = pairs.get(label, "")
        totals[key] = totals.get(key, 0.0) + value
    return totals
