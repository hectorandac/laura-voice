"""Hermes-independent pieces of the voice link: wire messages, task queue, outbox, persona snapshot.

The adapter (adapter.py) owns the Hermes side; everything here is plain Python so it can be unit-tested
without a Hermes install.
"""
import json
import time
import uuid
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path

PERSONA_FILES = {"soul": "SOUL.md", "user": "memories/USER.md", "memory": "memories/MEMORY.md"}
PERSONA_LIMIT = 6000          # chars per file sent to the fast voice model
OUTBOX_MAX = 50
OUTBOX_TTL_S = 24 * 3600


def dumps(**msg) -> str:
    return json.dumps(msg, ensure_ascii=False)


def new_id() -> str:
    return uuid.uuid4().hex[:12]


def read_persona(home: Path) -> dict:
    """SOUL.md + memory files from HERMES_HOME, trimmed for a small model's context."""
    out = {}
    for key, rel in PERSONA_FILES.items():
        try:
            out[key] = (home / rel).read_text(encoding="utf-8")[:PERSONA_LIMIT]
        except OSError:
            out[key] = ""
    return out


def main_model(home: Path) -> str:
    """Laura's main model (model.default), shown to Hector as who answered. Hermes' merged config first: the
    managed scope (/etc/hermes-managed) wins over HERMES_HOME/config.yaml, and the image has no PyYAML."""
    cfg = None
    try:
        from hermes_cli.config import load_config_readonly
        cfg = load_config_readonly() or {}
    except Exception:  # noqa: BLE001
        try:
            import yaml
            cfg = yaml.safe_load((home / "config.yaml").read_text(encoding="utf-8")) or {}
        except Exception:  # noqa: BLE001
            return ""
    m = cfg.get("model")
    return str((m.get("default") if isinstance(m, dict) else m) or "")


def persona_stamp(home: Path) -> tuple:
    stamp = []
    for rel in PERSONA_FILES.values():
        try:
            stamp.append((home / rel).stat().st_mtime_ns)
        except OSError:
            stamp.append(0)
    return tuple(stamp)


def task_prompt(text: str, context: list, lang: str = "") -> str:
    """What Laura's main brain receives for a spoken request: the request plus the live exchange before it."""
    lines = []
    if context:
        lines.append("[Voice conversation so far, transcribed; Laura's quick voice model answered these]")
        for m in context[-8:]:
            who = "Hector" if m.get("role") == "user" else "Laura (voice)"
            lines.append(f"{who}: {str(m.get('content', '')).strip()[:500]}")
        lines.append("")
    lines.append("[Spoken request from Hector, via the voice channel. Your reply is shown on his phone and read "
                 "aloud in summary, so lead with the answer.]")
    if lang:
        lines.append(f"[Language: {lang}]")
    lines.append(text.strip())
    return "\n".join(lines)


@dataclass
class Task:
    task_id: str
    text: str
    state: str = "queued"
    last_message: str = ""
    model: str = ""
    created: float = field(default_factory=time.time)


class TaskQueue:
    """Voice tasks run one at a time in Laura's voice session (Hermes' default busy mode would interrupt)."""

    def __init__(self):
        self.pending: deque[Task] = deque()
        self.running: Task | None = None

    def add(self, task: Task) -> None:
        self.pending.append(task)

    def cancel(self, task_id: str) -> Task | None:
        for t in list(self.pending):
            if t.task_id == task_id:
                self.pending.remove(t)
                t.state = "cancelled"
                return t
        return None

    def next(self) -> Task | None:
        if self.running or not self.pending:
            return None
        self.running = self.pending.popleft()
        self.running.state = "running"
        return self.running

    def finish(self, state: str) -> Task | None:
        task, self.running = self.running, None
        if task:
            task.state = state
        return task


class Outbox:
    """Messages for the voice gateway kept while it is unreachable (laptop asleep or travelling)."""

    def __init__(self, maxlen: int = OUTBOX_MAX, ttl: float = OUTBOX_TTL_S):
        self.items: deque[tuple[float, str]] = deque(maxlen=maxlen)
        self.ttl = ttl

    def put(self, raw: str) -> None:
        self.items.append((time.time(), raw))

    def drain(self) -> list[str]:
        now = time.time()
        out = [raw for t, raw in self.items if now - t < self.ttl]
        self.items.clear()
        return out
