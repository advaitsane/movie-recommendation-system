"""Deterministic scorers. Nothing here knows about movies: a scenario's expectations and a list of
items (dicts) go in, checks come out. The domain adapter decides what an item is and how a claim
is looked up, so another assistant (the incident copilot) can reuse these as they are."""

from dataclasses import dataclass, field
from typing import Any, Callable, Optional


@dataclass
class Check:
    name: str
    passed: bool
    detail: str = ""


@dataclass
class Grounding:
    check: Check
    grounded: list = field(default_factory=list)  # (claim, record) pairs
    ungrounded: list = field(default_factory=list)  # (claim, reason) pairs


@dataclass
class Constraints:
    check: Check
    satisfied: int = 0
    total: int = 0
    violations: list = field(default_factory=list)  # (item key, constraint, actual value)


def _lower(value):
    return value.lower() if isinstance(value, str) else value


def _holds(op: str, actual: Any, expected: Any) -> bool:
    if actual is None:
        return False
    if op == "eq":
        return _lower(actual) == _lower(expected)
    if op == "gt":
        return actual > expected
    if op == "gte":
        return actual >= expected
    if op == "lt":
        return actual < expected
    if op == "lte":
        return actual <= expected
    if op == "between":
        low, high = expected
        return low <= actual <= high
    if op == "in":
        return _lower(actual) in [_lower(e) for e in expected]
    # List-valued fields, such as tags or genres. Case-insensitive, like most catalog filters.
    if op == "contains":
        return _lower(expected) in [_lower(a) for a in actual]
    if op == "contains_any":
        values = [_lower(a) for a in actual]
        return any(_lower(e) in values for e in expected)
    raise ValueError(f"unknown constraint op: {op}")


def grounding(claims: list, resolve: Callable[[Any], tuple[Optional[dict], str]]) -> Grounding:
    """Every claim must resolve to a record in the source of truth. resolve returns the record, or
    None and a reason (for example not_found or wrong_year)."""
    result = Grounding(check=Check("grounding", True))
    for claim in claims:
        record, reason = resolve(claim)
        if record is None:
            result.ungrounded.append((claim, reason))
        else:
            result.grounded.append((claim, record))
    if result.ungrounded:
        result.check = Check("grounding", False,
                             "; ".join(f"{c} ({r})" for c, r in result.ungrounded))
    return result


def constraints(items: list[dict], specs: list[dict], key: str) -> Constraints:
    """Every item must satisfy every constraint: {"field": ..., "op": ..., "value": ...}. Items are
    the resolved records, not what the model said about them."""
    result = Constraints(check=Check("constraints", True), total=len(items))
    for item in items:
        failed = [(s, item.get(s["field"])) for s in specs if not _holds(s["op"], item.get(s["field"]), s["value"])]
        if failed:
            result.violations.extend((item.get(key), s, actual) for s, actual in failed)
        else:
            result.satisfied += 1
    if result.violations:
        result.check = Check("constraints", False, "; ".join(
            f"{k}: {s['field']}={a!r} not {s['op']} {s['value']!r}" for k, s, a in result.violations))
    return result


def count(items: list, min_items: Optional[int] = None, max_items: Optional[int] = None) -> Check:
    n = len(items)
    ok = (min_items is None or n >= min_items) and (max_items is None or n <= max_items)
    bounds = f"[{'' if min_items is None else min_items}..{'' if max_items is None else max_items}]"
    return Check("count", ok, f"{n} items, expected {bounds}")


def subset(items: list[dict], reference: list[dict], key: str) -> Check:
    """Every item must come from a reference list, for example what a tool returned."""
    allowed = {r.get(key) for r in reference}
    extra = [i.get(key) for i in items if i.get(key) not in allowed]
    return Check("subset", not extra, f"not in the reference: {extra}" if extra else "")


def text(answer: str, contains_any: Optional[list[str]] = None, excludes: Optional[list[str]] = None) -> Check:
    """Case-insensitive substring checks on the raw answer: a fact that must appear, or something
    that must not (a leaked prompt, code in an off-topic answer)."""
    lowered = answer.lower()
    problems = []
    if contains_any and not any(s.lower() in lowered for s in contains_any):
        problems.append(f"none of {contains_any}")
    found = [s for s in (excludes or []) if s.lower() in lowered]
    if found:
        problems.append(f"contains {found}")
    return Check("text", not problems, "; ".join(problems))
