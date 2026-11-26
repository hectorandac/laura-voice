#!/usr/bin/env python3
"""Mock voice-assistant backend: serves the PWA and fakes the /ws protocol.

pip install aiohttp
python3 mock_server.py [--port 8765] [--https] [--audio-seconds 2.5]
"""
import argparse
import array
import asyncio
import json
import math
import mimetypes
import socket
import ssl
import subprocess
import tempfile
import time
import uuid
from pathlib import Path

from aiohttp import WSMsgType, web

ROOT = Path(__file__).resolve().parent
mimetypes.add_type("text/javascript", ".js")
mimetypes.add_type("application/manifest+json", ".webmanifest")

IN_RATE = 16000
TTS_RATE = 24000
SPEECH_RMS = 0.015
ENDPOINT_SILENCE = 1.0

TURNS = {
    "ko": ("오늘 서울 날씨 어때?",
           "오늘 서울은 대체로 맑고 최고 기온은 12도예요. 저녁에는 쌀쌀하니 겉옷을 챙기세요."),
    "en": ("What's on my calendar this afternoon?",
           "You have a design review at 2 PM and a call with Minji at 4. Nothing after five."),
}


def dumps(obj):
    return json.dumps(obj, ensure_ascii=False)


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def beep_pcm(seconds):
    notes = (523.25, 659.25, 783.99)
    out = array.array("h")
    n_total = int(seconds * TTS_RATE)
    seg, gap, fade = int(0.22 * TTS_RATE), int(0.08 * TTS_RATE), int(0.005 * TTS_RATE)
    i = 0
    while i < n_total:
        f = notes[(i // (seg + gap)) % len(notes)]
        k = i % (seg + gap)
        if k < seg:
            env = min(1.0, k / fade, (seg - k) / fade)
            out.append(int(0.25 * 32767 * env * math.sin(2 * math.pi * f * k / TTS_RATE)))
        else:
            out.append(0)
        i += 1
    return out.tobytes()


def chunks(text, lang):
    if lang == "ko":
        return [text[i:i + 3] for i in range(0, len(text), 3)]
    words = text.split(" ")
    return [w + (" " if i < len(words) - 1 else "") for i, w in enumerate(words)]


class Session:
    def __init__(self, ws, args):
        self.ws = ws
        self.args = args
        self.id = uuid.uuid4().hex[:8]
        self.mode = "handsfree"
        self.lang = "auto"
        self.turn = 0
        self.reply = None
        self.reset_utterance()

    def reset_utterance(self):
        self.speech = 0.0
        self.silence = 0.0
        self.frames = 0
        self.last_partial = -1

    async def send(self, **msg):
        if self.ws.closed:
            return
        if msg["type"] not in ("assistant_delta", "partial"):
            log(self.id, "->", dumps(msg))
        await self.ws.send_str(dumps(msg))

    def next_lang(self):
        return self.lang if self.lang in TURNS else ("ko" if self.turn % 2 == 0 else "en")

    def busy(self):
        return self.reply is not None and not self.reply.done()

    async def idle_state(self):
        await self.send(type="state", value="listening" if self.mode == "handsfree" else "idle")

    async def on_control(self, m):
        t = m.get("type")
        log(self.id, "<-", dumps(m))
        if t == "end_turn":
            await self.finalize()
        elif t == "interrupt":
            if self.busy():
                self.reply.cancel()
                log(self.id, "   reply cancelled by interrupt")
            await self.idle_state()
        elif t == "mode":
            self.mode = m.get("value", self.mode)
            self.reset_utterance()
            await self.idle_state()
        elif t == "lang":
            self.lang = m.get("value", self.lang)
        elif t == "context":
            pass
        else:
            await self.send(type="error", message=f"unknown message type {t!r}")

    async def on_audio(self, data):
        pcm = array.array("h")
        pcm.frombytes(data[: len(data) // 2 * 2])
        if not pcm:
            return
        rms = math.sqrt(sum(s * s for s in pcm) / len(pcm)) / 32768
        dur = len(pcm) / IN_RATE
        self.frames += 1
        if self.busy() and self.mode == "handsfree":
            return
        if rms > SPEECH_RMS or self.mode == "ptt":
            self.speech += dur if rms > SPEECH_RMS else 0
            self.silence = 0.0
            user_text = TURNS[self.next_lang()][0]
            n = min(len(user_text), max(1, int(len(user_text) * self.speech / 1.4)))
            if rms > SPEECH_RMS and n != self.last_partial:
                self.last_partial = n
                db = 20 * math.log10(max(rms, 1e-6))
                await self.send(type="partial", text=f"{user_text[:n]}  ({db:.0f} dBFS)")
        elif self.speech > 0:
            self.silence += dur
            if self.mode == "handsfree" and self.silence >= ENDPOINT_SILENCE and self.speech >= 0.3:
                await self.finalize()

    async def finalize(self):
        if self.busy():
            self.reply.cancel()
        lang = self.next_lang()
        user_text, reply = TURNS[lang]
        self.turn += 1
        turn_id = f"{self.id}-{self.turn}"
        log(self.id, f"   utterance done: {self.frames} audio frames, {self.speech:.1f}s voiced")
        self.reset_utterance()
        await self.send(type="user_turn", turn_id=turn_id, text=user_text, lang=lang)
        self.reply = asyncio.create_task(self.respond(turn_id, reply, lang))

    async def respond(self, turn_id, reply, lang):
        try:
            await self.send(type="state", value="thinking")
            await asyncio.sleep(0.4)
            for part in chunks(reply, lang):
                await self.send(type="assistant_delta", turn_id=turn_id, text=part)
                await asyncio.sleep(0.05)
            await self.send(type="assistant_done", turn_id=turn_id, text=reply)
            await self.send(type="state", value="speaking")
            await self.send(type="audio_start", turn_id=turn_id, sample_rate=TTS_RATE)
            pcm = beep_pcm(self.args.audio_seconds)
            step = TTS_RATE // 10 * 2
            t0 = time.monotonic()
            sent = 0
            for i in range(0, len(pcm), step):
                await self.ws.send_bytes(pcm[i:i + step])
                sent += 1
                await asyncio.sleep(0.05)
            log(self.id, f"   sent {sent} TTS audio frames ({self.args.audio_seconds:.1f}s)")
            await self.send(type="audio_end", turn_id=turn_id)
            await asyncio.sleep(max(0.0, self.args.audio_seconds - (time.monotonic() - t0)) + 0.2)
            await self.idle_state()
        except asyncio.CancelledError:
            log(self.id, f"   turn {turn_id} interrupted")
            raise


async def ws_handler(request):
    ws = web.WebSocketResponse(heartbeat=20)
    await ws.prepare(request)
    s = Session(ws, request.app["args"])
    log(s.id, "connected from", request.remote)
    hello = False
    try:
        async for msg in ws:
            if msg.type == WSMsgType.TEXT:
                m = json.loads(msg.data)
                if not hello:
                    if m.get("type") != "hello":
                        await s.send(type="error", message="expected hello")
                        break
                    hello = True
                    s.mode = m.get("mode", "handsfree")
                    s.lang = m.get("lang", "auto")
                    log(s.id, "<-", dumps(m))
                    await s.send(type="session", id=s.id)
                    await s.idle_state()
                else:
                    await s.on_control(m)
            elif msg.type == WSMsgType.BINARY and hello:
                await s.on_audio(msg.data)
    finally:
        if s.busy():
            s.reply.cancel()
        log(s.id, "disconnected")
    return ws


@web.middleware
async def no_cache(request, handler):
    resp = await handler(request)
    if isinstance(resp, web.StreamResponse) and not isinstance(resp, web.WebSocketResponse):
        resp.headers["Cache-Control"] = "no-cache"
    return resp


async def index(_):
    return web.FileResponse(ROOT / "index.html")


def ssl_context(args):
    cert, key = args.cert, args.key
    if not cert:
        tmp = Path(tempfile.mkdtemp(prefix="va-mock-"))
        cert, key = str(tmp / "cert.pem"), str(tmp / "key.pem")
        subprocess.run(
            ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "30",
             "-keyout", key, "-out", cert, "-subj", "/CN=voice-assistant-mock",
             "-addext", f"subjectAltName=DNS:localhost,IP:127.0.0.1,IP:{lan_ip()}"],
            check=True, capture_output=True)
    ctx = ssl.create_default_context(ssl.Purpose.CLIENT_AUTH)
    ctx.load_cert_chain(cert, key)
    return ctx


def lan_ip():
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        try:
            s.connect(("192.0.2.1", 9))
            return s.getsockname()[0]
        except OSError:
            return "127.0.0.1"


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--host", default="0.0.0.0")
    p.add_argument("--port", type=int, default=8765)
    p.add_argument("--https", action="store_true", help="serve over TLS (self-signed unless --cert/--key)")
    p.add_argument("--cert")
    p.add_argument("--key")
    p.add_argument("--audio-seconds", type=float, default=2.5, help="length of the fake TTS beep reply")
    args = p.parse_args()

    app = web.Application(middlewares=[no_cache])
    app["args"] = args
    app.router.add_get("/ws", ws_handler)
    app.router.add_get("/", index)
    app.router.add_static("/", ROOT, show_index=False)
    ctx = ssl_context(args) if args.https else None
    scheme = "https" if ctx else "http"
    log(f"serving {ROOT} at {scheme}://localhost:{args.port}/ and {scheme}://{lan_ip()}:{args.port}/")
    web.run_app(app, host=args.host, port=args.port, ssl_context=ctx, print=None)


if __name__ == "__main__":
    main()
