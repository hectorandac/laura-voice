import asyncio
import json
from contextlib import asynccontextmanager

import laura_link
import pytest
from laura_link import ASK_LAURA, Link, persona_prompt


@pytest.fixture(autouse=True)
def link_enabled(monkeypatch):
    monkeypatch.setattr(laura_link, "ENABLED", True)


class FakeResp:
    def __init__(self, lines):
        self.lines = lines

    def raise_for_status(self):
        pass

    async def aiter_lines(self):
        for line in self.lines:
            yield line


class FakeHttp:
    def __init__(self, lines):
        self.lines, self.bodies = lines, []

    @asynccontextmanager
    async def stream(self, method, url, json=None):
        self.bodies.append(json)
        yield FakeResp(self.lines)


class FakeWs:
    def __init__(self):
        self.sent = []

    async def send_text(self, raw):
        self.sent.append(json.loads(raw))


class FakeSession:
    def __init__(self):
        self.out, self.history = [], [{"role": "user", "content": "hi"}, {"role": "assistant", "content": "Hey."}]

    async def send(self, **msg):
        self.out.append(msg)


def sse(delta):
    return "data: " + json.dumps({"choices": [{"delta": delta}]})


def run(coro):
    return asyncio.run(coro)


async def collect(gen):
    return [x async for x in gen]


def linked():
    link = Link()
    link.ws, link.persona = FakeWs(), {"soul": "# Laura", "user": "Hector lives in Daegu.", "memory": ""}
    return link


def test_persona_prompt_sections():
    p = persona_prompt({"soul": "# Laura", "user": "Hector", "memory": ""})
    assert "## Identity\n# Laura" in p and "## About Hector\nHector" in p and "## Memory" not in p
    assert "ask_laura" in p


def test_plain_reply_without_link_has_no_tools():
    link, s = Link(), FakeSession()
    http = FakeHttp([sse({"content": "Hello"}), sse({"content": " there."}), "data: [DONE]"])
    out = run(collect(link.stream(s, http, "u", {"model": "m"}, "hi", "en")))
    assert out == ["Hello", " there."]
    assert "tools" not in http.bodies[0]


def test_tool_call_hands_task_to_laura():
    link, s = linked(), FakeSession()
    args = json.dumps({"task": "check free disk on the server", "ack": "On it."})
    http = FakeHttp([
        sse({"tool_calls": [{"index": 0, "function": {"name": "ask_laura", "arguments": args[:10]}}]}),
        sse({"tool_calls": [{"index": 0, "function": {"arguments": args[10:]}}]}),
        "data: [DONE]"])

    async def go():
        link.session = s
        return await collect(link.stream(s, http, "u", {"model": "m"}, "how much disk is left?", "en"))

    out = run(go())
    assert out == [" On it."]
    assert http.bodies[0]["tools"] == [ASK_LAURA]
    task = link.ws.sent[0]
    assert task["type"] == "task" and task["text"] == "check free disk on the server"
    assert task["context"] == s.history
    assert s.out[0]["type"] == "task" and s.out[0]["state"] == "queued"


def test_handoff_fails_gracefully_when_unlinked():
    link, s = linked(), FakeSession()
    http = FakeHttp([sse({"tool_calls": [{"index": 0, "function": {"name": "ask_laura", "arguments": "{}"}}]})])

    async def go():
        link.ws = None
        return await collect(link.stream(s, http, "u", {"model": "m"}, "do it", "ko"))

    assert run(go()) == [" 지금은 라우라 본체에 연결할 수 없어요."]


def test_messages_from_hermes_reach_client_or_wait():
    link, s = linked(), FakeSession()

    async def go():
        link.speak_q = asyncio.Queue()
        await link._from_hermes({"type": "approval_request", "approval_id": "a1", "task_id": "t1", "text": "rm -rf /tmp/x",
                                 "command": "rm -rf /tmp/x", "description": "delete", "choices": ["once", "deny"]})
        await link._from_hermes({"type": "message", "task_id": "t1", "text": "Done: 40% free.", "final": True})
        assert [k for k, _ in link.pending] == ["speak"]
        link.session = s
        await link.on_hello(s)
        await link._from_hermes({"type": "message", "task_id": "t1", "text": "checking df", "final": False})
        await link.on_control(s, {"type": "approval_response", "approval_id": "a1", "choice": "deny"})
        await link.on_control(s, {"type": "approval_response", "approval_id": "nope", "choice": "once"})
        await link._from_hermes({"type": "approval_closed", "approval_id": "a1", "choice": "deny"})
        return link.speak_q.qsize()

    assert run(go()) == 1
    types = [m["type"] for m in s.out]
    assert types == ["laura_status", "approval_request", "laura_message", "task_progress", "approval_closed"]
    assert link.ws.sent == [{"type": "approval_response", "approval_id": "a1", "choice": "deny"}]
    assert not link.approvals


def test_link_rejects_bad_token(monkeypatch):
    monkeypatch.setattr(laura_link, "TOKEN", "secret")
    closed = []

    class Ws:
        headers = {"authorization": "Bearer wrong"}

        async def close(self, code):
            closed.append(code)

    run(Link().serve(Ws()))
    assert closed == [4401]


def test_disabled_link_is_inert(monkeypatch):
    monkeypatch.setattr(laura_link, "ENABLED", False)
    link, s = Link(), FakeSession()

    async def go():
        await link.attach(s)
        await link.on_hello(s)
        await link.on_control(s, {"type": "task_cancel", "task_id": "x"})

    run(go())
    assert link.session is None and link.speak_q is None and s.out == []
    msgs = link.messages(s, "SYS", [{"role": "user", "content": "a"}], "b")
    assert msgs == [{"role": "system", "content": "SYS"}, {"role": "user", "content": "a"}, {"role": "user", "content": "b"}]


def test_queued_is_announced_once():
    link, s = linked(), FakeSession()

    async def go():
        link.session = s
        task_id = await link.submit(s, "check disk", "en")
        await link._from_hermes({"type": "task_update", "task_id": task_id, "state": "queued"})
        await link._from_hermes({"type": "task_update", "task_id": task_id, "state": "running"})

    run(go())
    assert [m["state"] for m in s.out if m["type"] == "task"] == ["queued", "running"]


def test_hermes_messages_carry_source(monkeypatch):
    monkeypatch.setattr(laura_link, "MODEL", "fallback-model")
    link, s = linked(), FakeSession()
    link.session = s

    async def go():
        link.speak_q = asyncio.Queue()
        await link._from_hermes({"type": "message", "task_id": "t0", "text": "x", "final": False})
        await link._from_hermes({"type": "persona", "soul": "x", "user": "", "memory": "", "model": "claude-sonnet-5-5"})
        await link._from_hermes({"type": "message", "task_id": "t1", "text": "working", "final": False})
        await link._from_hermes({"type": "message", "task_id": "t1", "text": "Done.", "final": True,
                                 "model": "claude-opus-5-5"})

    run(go())
    models = [m["source"]["model"] for m in s.out if m["type"] in ("task_progress", "laura_message")]
    assert models == ["fallback-model", "claude-sonnet-5-5", "claude-opus-5-5"]
    assert all(m["source"]["engine"] == "hermes" and m["source"]["lane"] == "task"
               for m in s.out if m["type"] in ("task_progress", "laura_message"))
