"""Laura in the middle: link between the voice gateway and Laura's Hermes gateway.

Live turns stay on the fast local model, speaking as Laura (persona pushed by the Hermes plugin). When a
request needs real work the model calls `ask_laura`: the gateway speaks a short acknowledgment, hands the
task to Hermes over the /laura WebSocket (the `laura-voice` platform plugin dials in), and reports
progress, approvals and the result to the client. Results are shown in full and spoken as a short summary.

Approvals are answered only by an explicit `approval_response` control message (a tap), never by speech.
Env: LAURA_LINK_TOKEN: the bearer the plugin must present. Unset = link disabled, gateway unchanged.
"""
import asyncio
import json
import logging
import os
import secrets
import time
import uuid

from fastapi import FastAPI, WebSocket, WebSocketDisconnect

log = logging.getLogger("laura")

TOKEN = os.environ.get("LAURA_LINK_TOKEN", "")
ENABLED = bool(TOKEN)        # without a token the gateway behaves exactly as if this module did not exist
LLM_URL = os.environ.get("LLM_URL", "http://127.0.0.1:8090/v1/chat/completions")
LLM_MODEL = os.environ.get("LLM_MODEL", "bonsai-9b-voice")
CONTROL_TYPES = {"approval_response", "task_cancel"}
CONTEXT_TURNS = 8
PENDING_MAX = 20
MODEL = os.environ.get("LAURA_MODEL", "")   # fallback name of Laura's main model when the plugin doesn't say

ASK_LAURA = {
    "type": "function",
    "function": {
        "name": "ask_laura",
        "description": (
            "Hand the request to Laura's full agent (her tools, memory, homelab access, web, calendar, files, "
            "messages, scheduling, careful reasoning). Use it whenever an answer would need any of those or "
            "would otherwise be a guess. Takes from seconds to a few minutes; the result is spoken when ready."),
        "parameters": {
            "type": "object",
            "properties": {
                "task": {"type": "string", "description": "The request, self-contained, in Hector's language."},
                "ack": {"type": "string", "description": "Very short spoken acknowledgment, e.g. 'On it.'"},
            },
            "required": ["task"],
        },
    },
}


def persona_prompt(persona: dict) -> str:
    parts = ["You are Laura, Hector's personal agent. This is her identity and what she knows about him:"]
    for key, title in (("soul", "Identity"), ("user", "About Hector"), ("memory", "Memory")):
        text = (persona.get(key) or "").strip()
        if text:
            parts.append(f"## {title}\n{text}")
    parts.append(
        "## Voice\nRight now you are Laura's quick voice: a small local model that talks with Hector out loud. "
        "Chat, answer simple things from what you know, and keep it natural. You have no tools yourself. For "
        "anything that needs Laura's tools, his data, the homelab, the internet, files, reminders, messages or "
        "careful work, call ask_laura and say a brief acknowledgment; never invent results. Never approve "
        "anything on his behalf.")
    return "\n\n".join(parts)


SUMMARY_PROMPT = (
    "Rewrite Laura's written reply as what she would say out loud to Hector: one to three short sentences, "
    "same language as the reply, no lists, markdown, URLs or code. Keep the key result and any question she asks.")


class Link:
    def __init__(self):
        self.ws: WebSocket | None = None
        self.persona: dict | None = None
        self.session = None                  # the active client Session (assistant_server.Session)
        self.pending: list[tuple[str, dict]] = []   # client messages kept while no client is connected
        self.tasks: dict[str, dict] = {}
        self.approvals: dict[str, dict] = {}
        self.speak_q: asyncio.Queue | None = None
        self.speaker: asyncio.Task | None = None
        self.model = ""

    def source(self, model: str = "", lane: str = "task") -> dict:
        """Which model answered: the plugin's per-turn label, else her default from the persona, else LAURA_MODEL."""
        return {"engine": "hermes", "model": model or self.model or MODEL, "lane": lane}

    @property
    def up(self) -> bool:
        return self.ws is not None and self.persona is not None

    # ---- client side ------------------------------------------------------
    async def attach(self, session) -> None:
        if not ENABLED:
            return
        self.session = session
        if self.speak_q is None:
            self.speak_q = asyncio.Queue()
            self.speaker = asyncio.create_task(self._speaker())

    async def on_hello(self, session) -> None:
        if not ENABLED:
            return
        await self._client(type="laura_status", connected=self.up)
        for a in self.approvals.values():
            await self._client(**a)
        pending, self.pending = self.pending, []
        for kind, msg in pending:
            await self._client(**msg)
            if kind == "speak":
                await self.speak_q.put(msg)

    def detach(self, session) -> None:
        if self.session is session:
            self.session = None

    async def _client(self, **msg) -> bool:
        s = self.session
        if s is None:
            return False
        try:
            await s.send(**msg)
            return True
        except Exception:  # noqa: BLE001
            return False

    async def on_control(self, session, msg: dict) -> None:
        if not ENABLED:
            return
        t = msg.get("type")
        if t == "approval_response":
            approval_id = str(msg.get("approval_id") or "")
            if approval_id in self.approvals:
                await self._hermes(type="approval_response", approval_id=approval_id,
                                   choice=str(msg.get("choice") or "deny"))
        elif t == "task_cancel":
            await self._hermes(type="cancel", task_id=str(msg.get("task_id") or ""))

    # ---- fast lane --------------------------------------------------------
    def messages(self, session, system: str, history: list, text: str) -> list:
        if not self.up:
            return [{"role": "system", "content": system}] + history + [{"role": "user", "content": text}]
        return ([{"role": "system", "content": persona_prompt(self.persona) + "\n\n" + system}]
                + history + [{"role": "user", "content": text}])

    async def stream(self, session, http, url: str, body: dict, text: str, lang: str):
        """Stream the fast model's reply as text deltas; an ask_laura call becomes a spoken ack + a task."""
        body = dict(body)
        if self.up:
            body["tools"] = [ASK_LAURA]
        calls: dict[int, dict] = {}
        async with http.stream("POST", url, json=body) as resp:
            resp.raise_for_status()
            async for line in resp.aiter_lines():
                if not line.startswith("data: ") or line.endswith("[DONE]"):
                    continue
                delta = json.loads(line[6:])["choices"][0]["delta"]
                for tc in delta.get("tool_calls") or []:
                    c = calls.setdefault(tc.get("index", 0), {"name": "", "args": ""})
                    fn = tc.get("function") or {}
                    c["name"] += fn.get("name") or ""
                    c["args"] += fn.get("arguments") or ""
                if delta.get("content"):
                    yield delta["content"]
        for c in calls.values():
            if c["name"] != "ask_laura":
                continue
            try:
                args = json.loads(c["args"] or "{}")
            except ValueError:
                args = {}
            task = str(args.get("task") or text).strip()
            ack = str(args.get("ack") or "").strip() or ("알겠어요, 확인해 볼게요." if lang == "ko" else "On it.")
            if await self.submit(session, task, lang):
                yield " " + ack
            else:
                yield " " + ("지금은 라우라 본체에 연결할 수 없어요." if lang == "ko"
                             else "I can't reach my main brain right now.")

    async def submit(self, session, text: str, lang: str) -> str | None:
        if self.ws is None:
            return None
        task_id = uuid.uuid4().hex[:12]
        context = [m for m in (session.history or [])][-CONTEXT_TURNS:]
        self.tasks[task_id] = {"task_id": task_id, "title": text[:120], "state": "queued", "lang": lang,
                               "t0": time.time()}
        await self._client(type="task", task_id=task_id, state="queued", title=text[:120], source=self.source())
        if not await self._hermes(type="task", task_id=task_id, text=text, context=context, lang=lang):
            self.tasks.pop(task_id, None)
            return None
        log.info("task %s handed to Laura: %s", task_id, text[:80])
        return task_id

    # ---- Hermes side ------------------------------------------------------
    async def _hermes(self, **msg) -> bool:
        if self.ws is None:
            return False
        try:
            await self.ws.send_text(json.dumps(msg, ensure_ascii=False))
            return True
        except Exception:  # noqa: BLE001
            return False

    async def serve(self, ws: WebSocket) -> None:
        auth = ws.headers.get("authorization", "")
        if not TOKEN or not secrets.compare_digest(auth, f"Bearer {TOKEN}"):
            await ws.close(code=4401)
            return
        await ws.accept()
        if self.ws is not None:
            try:
                await self.ws.close(code=4000)
            except Exception:  # noqa: BLE001
                pass
        self.ws = ws
        log.info("Hermes linked")
        try:
            while True:
                await self._from_hermes(json.loads(await ws.receive_text()))
        except (WebSocketDisconnect, RuntimeError):
            pass
        finally:
            if self.ws is ws:
                self.ws, self.persona = None, None
                await self._client(type="laura_status", connected=False)
            log.info("Hermes link closed")

    async def _from_hermes(self, msg: dict) -> None:
        t = msg.get("type")
        if t == "persona":
            first = self.persona is None
            self.persona = {k: msg.get(k) or "" for k in ("soul", "user", "memory")}
            self.model = str(msg.get("model") or "")
            if first:
                await self._client(type="laura_status", connected=True)
        elif t == "task_update":
            task = self.tasks.get(msg.get("task_id"))
            if task and task["state"] == msg.get("state"):
                return                       # the gateway already announced this state (queued on submit)
            if task:
                task["state"] = msg.get("state")
            await self._client(type="task", task_id=msg.get("task_id"), state=msg.get("state"),
                               title=(task or {}).get("title", ""), source=self.source(str(msg.get("model") or "")))
            if msg.get("state") in ("done", "failed", "cancelled"):
                self.tasks.pop(msg.get("task_id"), None)
        elif t == "message":
            out = {"type": "laura_message", "task_id": msg.get("task_id"), "text": msg.get("text") or "",
                   "final": bool(msg.get("final")), "source": self.source(str(msg.get("model") or ""))}
            if not out["final"]:
                await self._client(type="task_progress", task_id=out["task_id"], text=out["text"],
                                   source=out["source"])
                return
            if self.session is None:
                self._keep("speak", out)
                return
            await self._client(**out)
            await self.speak_q.put(out)
        elif t == "approval_request":
            a = {"type": "approval_request", **{k: msg.get(k) for k in (
                "approval_id", "task_id", "text", "command", "description", "choices")}}
            self.approvals[a["approval_id"]] = a      # replayed after the next hello if nobody sees it now
            await self._client(**a)
        elif t == "approval_closed":
            self.approvals.pop(msg.get("approval_id"), None)
            await self._client(type="approval_closed", approval_id=msg.get("approval_id"), choice=msg.get("choice"))

    def _keep(self, kind: str, msg: dict) -> None:
        self.pending = (self.pending + [(kind, msg)])[-PENDING_MAX:]

    # ---- speaking results -------------------------------------------------
    async def _speaker(self) -> None:
        while True:
            msg = await self.speak_q.get()
            try:
                await self._speak_result(msg)
            except Exception:  # noqa: BLE001
                log.exception("speaking Laura's result failed")

    async def _speak_result(self, msg: dict) -> None:
        s = self.session
        if s is None or not msg.get("text"):
            return
        for _ in range(1200):   # wait for a quiet moment (not mid-reply, not while he talks); else card only
            if not s.speaking and not s.in_speech and (s.reply_task is None or s.reply_task.done()):
                break
            await asyncio.sleep(0.1)
        else:
            return
        spoken = await self._summarize(s, msg["text"])
        if spoken and self.session is s:
            await s.announce(spoken, source=msg.get("source") or self.source())
            s.history.append({"role": "assistant", "content": f"[Laura's full agent finished] {spoken}"})

    async def _summarize(self, s, text: str) -> str:
        if len(text) < 200 and "\n" not in text.strip():
            return text.strip()
        body = {"model": LLM_MODEL, "stream": False, "max_tokens": 160, "temperature": 0.3,
                "chat_template_kwargs": {"enable_thinking": False},
                "messages": [{"role": "system", "content": SUMMARY_PROMPT}, {"role": "user", "content": text[:6000]}]}
        try:
            r = await s.http.post(LLM_URL, json=body)
            r.raise_for_status()
            return r.json()["choices"][0]["message"]["content"].strip()
        except Exception:  # noqa: BLE001
            log.exception("summary failed")
            return text.split("\n")[0][:300]


LINK = Link()


def mount(app: FastAPI) -> None:
    @app.websocket("/laura")
    async def laura_ws(ws: WebSocket):
        await LINK.serve(ws)
