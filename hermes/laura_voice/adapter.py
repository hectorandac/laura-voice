"""laura-voice platform for Hermes: Laura's voice channel.

The adapter dials the voice gateway (WebSocket, bearer token) and keeps the link up with backoff. Spoken
requests the gateway hands over become ordinary turns in one Hermes session (full toolset, memory, skills,
approvals); replies, progress and approval prompts go back over the same socket. Also pushes Laura's
persona (SOUL.md, USER.md, MEMORY.md) so the gateway's fast local model speaks as her.

Env: LAURA_VOICE_URL (ws://<gateway>/laura), LAURA_VOICE_TOKEN, LAURA_VOICE_USER (default hector),
LAURA_VOICE_ALLOWED_USERS, LAURA_VOICE_HOME_CHANNEL (default voice).
"""
import asyncio
import json
import logging
import os
from pathlib import Path
from typing import Any, Optional

from gateway.config import Platform, PlatformConfig
from gateway.platforms._shared import get_scoped_secret
from gateway.platforms.base import BasePlatformAdapter, ExecApprovalPrompt, SendResult
from gateway.platforms.event import MessageEvent, MessageType, ProcessingOutcome

from .link import Outbox, Task, TaskQueue, dumps, main_model, new_id, persona_stamp, read_persona, task_prompt

try:
    import websockets
except ImportError:  # pragma: no cover
    websockets = None

logger = logging.getLogger(__name__)

PLATFORM = "laura_voice"
CHAT_ID = "voice"
BACKOFF = [2, 5, 10, 30, 60]
PERSONA_POLL_S = 30
MAX_MESSAGE_LENGTH = 8000

HINT = (
    "You are talking with Hector through laura-voice, his always-on voice channel (phone, watch, laptop). "
    "He spoke this request; a small local model already acknowledged it out loud. Your reply is shown as text "
    "on his device and a short summary of it is read aloud, so lead with the answer in one or two plain "
    "sentences, then any detail. No tables unless he asked. Dangerous commands still need his approval: it "
    "appears as buttons on his device; never treat anything he says out loud as an approval."
)


def _secret(name: str, default: str = "") -> str:
    return (get_scoped_secret(name, default) or "").strip()


def check_requirements() -> bool:
    return websockets is not None and bool(_secret("LAURA_VOICE_URL"))


def is_connected(config) -> bool:
    return bool(_secret("LAURA_VOICE_URL"))


def _hermes_home() -> Path:
    try:
        from hermes_constants import get_hermes_home
        return Path(get_hermes_home())
    except Exception:  # noqa: BLE001
        return Path(os.environ.get("HERMES_HOME", "~/.hermes")).expanduser()


class LauraVoiceAdapter(BasePlatformAdapter):
    MAX_MESSAGE_LENGTH = MAX_MESSAGE_LENGTH

    def __init__(self, config: PlatformConfig):
        super().__init__(config=config, platform=Platform(PLATFORM))
        self._url = _secret("LAURA_VOICE_URL")
        self._token = _secret("LAURA_VOICE_TOKEN")
        self._user = _secret("LAURA_VOICE_USER", "hector") or "hector"
        self._ws = None
        self._task: Optional[asyncio.Task] = None
        self._queue = TaskQueue()
        self._outbox = Outbox()
        self._approvals: dict[str, str] = {}      # approval_id -> session_key
        self._persona_stamp = None

    # ---- lifecycle -------------------------------------------------------
    async def connect(self, *, is_reconnect: bool = False) -> bool:
        if not check_requirements():
            logger.warning("[%s] LAURA_VOICE_URL not set or websockets missing", self.name)
            return False
        self._mark_connected()
        self._task = asyncio.create_task(self._run())
        self._wire_plugin_handlers(None)
        return True

    async def disconnect(self) -> None:
        self._running = False
        self._mark_disconnected()
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except (asyncio.CancelledError, Exception):  # noqa: BLE001
                pass
            self._task = None

    async def _run(self) -> None:
        attempt = 0
        headers = {"Authorization": f"Bearer {self._token}"} if self._token else {}
        while self._running:
            try:
                async with websockets.connect(self._url, additional_headers=headers, ping_interval=20,
                                              ping_timeout=20, max_size=2 ** 22) as ws:
                    self._ws, attempt = ws, 0
                    logger.info("[%s] linked to voice gateway", self.name)
                    await self._on_link_up()
                    poll = asyncio.create_task(self._poll_persona())
                    try:
                        async for raw in ws:
                            await self._on_frame(raw)
                    finally:
                        poll.cancel()
            except asyncio.CancelledError:
                return
            except Exception as e:  # noqa: BLE001
                logger.info("[%s] voice gateway unreachable: %s", self.name, str(e)[:120])
            self._ws = None
            await asyncio.sleep(BACKOFF[min(attempt, len(BACKOFF) - 1)])
            attempt += 1

    async def _on_link_up(self) -> None:
        self._persona_stamp = None
        await self._push_persona()
        for raw in self._outbox.drain():
            await self._ws.send(raw)
        for t in ([self._queue.running] if self._queue.running else []) + list(self._queue.pending):
            await self._emit(type="task_update", task_id=t.task_id, state=t.state)

    async def _poll_persona(self) -> None:
        while True:
            await asyncio.sleep(PERSONA_POLL_S)
            await self._push_persona()

    async def _push_persona(self) -> None:
        home = _hermes_home()
        stamp = await asyncio.to_thread(persona_stamp, home)
        if stamp == self._persona_stamp:
            return
        self._persona_stamp = stamp
        persona = await asyncio.to_thread(read_persona, home)
        model = await asyncio.to_thread(main_model, home)
        await self._emit(type="persona", **persona, model=model, keep=False)

    async def _emit(self, keep: bool = True, **msg) -> None:
        """Send to the gateway; if it is away, keep the message (unless keep=False) for the next link."""
        raw = dumps(**msg)
        if self._ws is not None:
            try:
                await self._ws.send(raw)
                return
            except Exception:  # noqa: BLE001
                self._ws = None
        if keep:
            self._outbox.put(raw)

    # ---- gateway -> Hermes ----------------------------------------------
    async def _on_frame(self, raw) -> None:
        try:
            msg = json.loads(raw)
        except (TypeError, ValueError):
            return
        t = msg.get("type")
        if t == "task":
            text = str(msg.get("text") or "").strip()
            if not text:
                return
            task = Task(task_id=str(msg.get("task_id") or new_id()),
                        text=task_prompt(text, msg.get("context") or [], msg.get("lang") or ""))
            self._queue.add(task)
            await self._emit(type="task_update", task_id=task.task_id, state="queued")
            await self._pump()
        elif t == "cancel":
            task_id = str(msg.get("task_id") or "")
            if self._queue.cancel(task_id):
                await self._emit(type="task_update", task_id=task_id, state="cancelled")
            elif self._queue.running and self._queue.running.task_id == task_id:
                await self._dispatch("/stop", new_id())
        elif t == "approval_response":
            await self._resolve_approval(str(msg.get("approval_id") or ""), str(msg.get("choice") or ""))

    async def _pump(self) -> None:
        task = self._queue.next()
        if task:
            task.model = self._turn_model(self._source(task.task_id))
            await self._emit(type="task_update", task_id=task.task_id, state="running", model=task.model)
            await self._dispatch(task.text, task.task_id)

    def _source(self, message_id: str):
        return self.build_source(chat_id=CHAT_ID, chat_name="Voice", chat_type="dm", user_id=self._user,
                                 user_name="Hector", message_id=message_id)

    def _turn_model(self, source) -> str:
        """The model this voice session's turn runs on: a /model override for the session, else the default."""
        runner = getattr(self, "gateway_runner", None)
        try:
            key = runner._session_key_for_source(source)
            override = ((getattr(runner, "_session_model_overrides", None) or {}).get(key) or {}).get("model")
            if override:
                return str(override)
        except Exception:  # noqa: BLE001
            pass
        return main_model(_hermes_home())

    async def _dispatch(self, text: str, message_id: str) -> None:
        source = self._source(message_id)
        await self.handle_message(MessageEvent(text=text, message_type=MessageType.TEXT, source=source,
                                               message_id=message_id))

    async def on_processing_complete(self, event: MessageEvent, outcome: ProcessingOutcome) -> None:
        task = self._queue.running
        if task and event.message_id == task.task_id:
            state = {ProcessingOutcome.SUCCESS: "done", ProcessingOutcome.CANCELLED: "cancelled"}.get(outcome, "failed")
            self._queue.finish(state)
            if task.last_message:
                await self._emit(type="message", task_id=task.task_id, text=task.last_message, final=True,
                                 model=task.model)
            await self._emit(type="task_update", task_id=task.task_id, state=state, model=task.model)
            await self._pump()

    async def _resolve_approval(self, approval_id: str, choice: str) -> None:
        session_key = self._approvals.pop(approval_id, None)
        if not session_key or choice not in ("once", "session", "always", "deny"):
            return
        from tools.approval import resolve_gateway_approval
        count = resolve_gateway_approval(session_key, choice)
        logger.info("[%s] approval %s -> %s (%d resolved)", self.name, approval_id, choice, count)
        await self._emit(type="approval_closed", approval_id=approval_id, choice=choice if count else "expired")

    # ---- Hermes -> gateway ----------------------------------------------
    async def send(self, chat_id: str, content: str, reply_to: Optional[str] = None,
                   metadata: Optional[dict[str, Any]] = None) -> SendResult:
        content = (content or "")[:MAX_MESSAGE_LENGTH]
        task = self._queue.running
        if task:
            # The reply is only known to be final when the turn ends; until then the previous one is progress.
            if task.last_message:
                await self._emit(type="message", task_id=task.task_id, text=task.last_message, final=False,
                                 model=task.model)
            task.last_message = content
        else:
            await self._emit(type="message", task_id=None, text=content, final=True,
                             model=self._turn_model(self._source(new_id())))
        return SendResult(success=True, message_id=new_id())

    async def send_typing(self, chat_id: str, metadata=None) -> None:
        return None

    async def _send_exec_approval_prompt(self, prompt: ExecApprovalPrompt) -> SendResult:
        if self._ws is None:
            return SendResult(success=False, error="voice gateway not connected")
        approval_id = new_id()
        self._approvals[approval_id] = prompt.session_key
        task = self._queue.running
        await self._emit(type="approval_request", approval_id=approval_id,
                         task_id=task.task_id if task else None, text=prompt.text, command=prompt.command,
                         description=prompt.description, choices=prompt.choices, keep=False)
        return SendResult(success=True, message_id=approval_id)

    async def get_chat_info(self, chat_id: str) -> dict[str, Any]:
        return {"name": "Voice", "type": "dm", "chat_id": chat_id}


def _env_enablement() -> dict | None:
    if not _secret("LAURA_VOICE_URL"):
        return None
    home = _secret("LAURA_VOICE_HOME_CHANNEL", CHAT_ID) or CHAT_ID
    return {"home_channel": {"chat_id": home, "name": "Voice"}}


def register(ctx) -> None:
    ctx.register_platform(
        name=PLATFORM, label="Laura voice", adapter_factory=lambda cfg: LauraVoiceAdapter(cfg),
        check_fn=check_requirements, is_connected=is_connected, required_env=["LAURA_VOICE_URL"],
        install_hint="websockets is a Hermes dependency; set LAURA_VOICE_URL and LAURA_VOICE_TOKEN",
        env_enablement_fn=_env_enablement, cron_deliver_env_var="LAURA_VOICE_HOME_CHANNEL",
        allowed_users_env="LAURA_VOICE_ALLOWED_USERS", allow_all_env="LAURA_VOICE_ALLOW_ALL_USERS",
        max_message_length=MAX_MESSAGE_LENGTH, pii_safe=True, platform_hint=HINT,
    )
