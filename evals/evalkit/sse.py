"""A minimal server-sent-events client: POSTs JSON, collects events, times the first token."""

import json
import time
import urllib.request
from dataclasses import dataclass, field
from typing import Optional


@dataclass
class Stream:
    events: list = field(default_factory=list)  # (event name, parsed data)
    first_token_s: Optional[float] = None
    total_s: float = 0.0

    def text(self, event: str = "token", key: str = "text") -> str:
        return "".join(d.get(key, "") for name, d in self.events if name == event and isinstance(d, dict))

    def first(self, event: str):
        return next((d for name, d in self.events if name == event), None)


def post(url: str, body: dict, headers: dict, timeout: float, token_event: str = "token") -> Stream:
    request = urllib.request.Request(
        url, data=json.dumps(body).encode(), method="POST",
        headers={"Content-Type": "application/json", "Accept": "text/event-stream", **headers})
    stream = Stream()
    started = time.monotonic()
    name, data = "message", []
    with urllib.request.urlopen(request, timeout=timeout) as response:
        for raw in response:
            line = raw.decode().rstrip("\r\n")
            if line.startswith("event:"):
                name = line[6:].strip()
            elif line.startswith("data:"):
                data.append(line[5:].lstrip())
            elif line == "" and data:
                payload = "\n".join(data)
                try:
                    parsed = json.loads(payload)
                except json.JSONDecodeError:
                    parsed = payload
                if name == token_event and stream.first_token_s is None:
                    stream.first_token_s = time.monotonic() - started
                stream.events.append((name, parsed))
                name, data = "message", []
    stream.total_s = time.monotonic() - started
    return stream
