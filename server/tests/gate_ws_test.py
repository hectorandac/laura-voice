"""End-to-end check of enrollment, speaker verification, the addressee gate, mute and verified barge-in over /ws,
with Supertonic voices standing in for people (OWNER voice enrolls, IMPOSTOR is someone else).
Refuses to run if a voice profile is already enrolled (it would be replaced); deletes its test profile at the end.

WS_URL=wss://127.0.0.1:8443/ws?k=... python tests/gate_ws_test.py [--owner M1] [--impostor F3]
"""
import argparse
import asyncio
import json
import os
import ssl
import time
import urllib.request

import numpy as np
import websockets

TTS = os.environ.get("TTS_URL", "http://127.0.0.1:8091/synth")
SIL = np.zeros(1600, "<i2")


def speech(text, lang, voice):
    req = urllib.request.Request(TTS, json.dumps({"text": text, "lang": lang, "voice": voice}).encode(),
                                 {"Content-Type": "application/json"})
    r = urllib.request.urlopen(req)
    x = np.frombuffer(r.read(), "<i2").astype(np.float32)
    rate = int(r.headers["X-Sample-Rate"])
    return np.interp(np.arange(0, x.size, rate / 16000), np.arange(x.size), x).astype("<i2")


class Client:
    def __init__(self, ws):
        self.ws = ws
        self.events = asyncio.Queue()
        self.audio_at = None
        self.pump_on = True

    async def reader(self):
        async for m in self.ws:
            if isinstance(m, bytes):
                if self.audio_at is None:
                    self.audio_at = time.time()
                continue
            await self.events.put(json.loads(m))

    async def pump(self):
        while True:
            if self.pump_on:
                await self.ws.send(SIL.tobytes())
            await asyncio.sleep(0.1)

    async def say(self, audio):
        self.pump_on = False
        for i in range(0, audio.size, 1600):
            await self.ws.send(audio[i:i + 1600].tobytes())
            await asyncio.sleep(0.1)
        self.audio_at = None
        self.pump_on = True
        return time.time()

    async def until(self, types, timeout=20):
        got = []
        t = time.time()
        while time.time() - t < timeout:
            try:
                d = await asyncio.wait_for(self.events.get(), 0.5)
            except asyncio.TimeoutError:
                continue
            got.append(d)
            if d["type"] in types:
                return d, got
        return None, got


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--owner", default="M1")
    ap.add_argument("--impostor", default="F3")
    a = ap.parse_args()
    ctx = ssl.create_default_context()
    ctx.check_hostname, ctx.verify_mode = False, ssl.CERT_NONE
    results = {}
    async with websockets.connect(os.environ["WS_URL"], max_size=None, ssl=ctx) as ws:
        c = Client(ws)
        tasks = [asyncio.create_task(c.reader()), asyncio.create_task(c.pump())]
        await ws.send(json.dumps({"type": "hello", "version": 1, "client": "test", "lang": "auto",
                                  "mode": "handsfree", "context": {}}))
        prof, _ = await c.until({"speaker_profile"})
        if prof["enrolled"]:
            raise SystemExit("a voice profile is already enrolled; not touching it")

        await ws.send(json.dumps({"type": "enroll_start"}))
        while True:
            d, _ = await c.until({"enroll_prompt", "enroll_done"}, 30)
            if d is None or d["type"] == "enroll_done":
                print("enroll_done:", d)
                break
            await c.say(speech(d["text"], d["lang"], a.owner))
            p, _ = await c.until({"enroll_progress"}, 30)
            print("  enroll", p)

        async def turn(label, text, lang, voice, expect, wait=0.0):
            await asyncio.sleep(wait)
            t_end = await c.say(speech(text, lang, voice))
            d, got = await c.until({"user_turn", "ignored"}, 20)
            outcome = d["type"] if d else "timeout"
            info = {k: d.get(k) for k in ("reason", "why", "score", "text")} if d else {}
            first_audio = None
            if outcome == "user_turn":
                m, got2 = await c.until({"metrics"}, 20)
                e, _ = await c.until({"audio_end"}, 40)
                first_audio = round((c.audio_at - t_end) * 1000) if c.audio_at else None
                info["metrics"] = m and {k: v for k, v in m.items() if k not in ("type", "turn_id")}
            ok = outcome == expect or (expect == "ignored" and outcome == "ignored")
            results[label] = {"ok": ok, "outcome": outcome, "first_audio_ms": first_audio, **info}
            print(f"{'PASS' if ok else 'FAIL'} {label}: {outcome} {json.dumps(results[label], ensure_ascii=False)}",
                  flush=True)

        await turn("owner_no_name_request", "What's a good recipe with eggs, rice and kimchi?", "en", a.owner, "user_turn")
        await turn("owner_followup_in_window", "And how long does that take to cook?", "en", a.owner, "user_turn")
        await turn("owner_name_ko", "로라야 내일 아침 일곱 시에 깨워 줄 수 있어?", "ko", a.owner, "user_turn", wait=22)
        await turn("impostor_with_name", "Laura, what time is it right now?", "en", a.impostor, "ignored", wait=22)
        await turn("owner_tv_like", "And now the weather for the weekend: heavy rain is expected across the southern "
                   "provinces.", "en", a.owner, "ignored")
        await turn("owner_to_person_ko", "여보 쓰레기 버렸어?", "ko", a.owner, "ignored")
        await turn("recall_ko", "로라, 너한테 한 말이야.", "ko", a.owner, "user_turn")
        print("   recall text:", results["recall_ko"].get("text"))
        await turn("owner_name_after_others", "Laura, how far is Busan from Daegu?", "en", a.owner, "user_turn")
        await turn("owner_no_name_after_others", "What's the capital of Australia?", "en", a.owner, "ignored", wait=35)
        await turn("mute", "Laura, stop listening.", "en", a.owner, "user_turn")
        await turn("muted_question", "Laura, what's the capital of Australia?", "en", a.owner, "ignored", wait=10)
        await turn("resume", "Laura, start listening again.", "en", a.owner, "user_turn")

        # barge-in: impostor talks over a long reply (should keep going), then the owner (should stop it)
        await asyncio.sleep(10)
        await c.say(speech("Laura, tell me a long story about a dragon who learns to cook.", "en", a.owner))
        d, _ = await c.until({"audio_start"}, 20)
        await asyncio.sleep(1.0)
        await c.say(speech("I think we should leave for the station soon.", "en", a.impostor))
        d, got = await c.until({"interrupted"}, 3)
        results["barge_impostor_ignored"] = {"ok": d is None}
        await c.say(speech("Okay stop, that's enough.", "en", a.owner))
        d, got = await c.until({"interrupted"}, 6)
        results["barge_owner_stops"] = {"ok": d is not None, "event": d}
        print("barge-in:", results["barge_impostor_ignored"], results["barge_owner_stops"], flush=True)

        await ws.send(json.dumps({"type": "enroll_delete"}))
        d, _ = await c.until({"speaker_profile"})
        print("profile deleted:", d and not d["enrolled"])
        for t in tasks:
            t.cancel()
    print(json.dumps({k: v["ok"] for k, v in results.items()}))


asyncio.run(main())
