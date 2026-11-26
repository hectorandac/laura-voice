"""End-to-end interruption test against a gateway (use a separate instance, e.g. :8444 with its own data dir).

A real-time simulated client: reply audio is "played" at real speed behind an output latency, a residual echo of
what is playing is mixed into the microphone (AEC leftovers), and Supertonic voices stand in for Hector, talking
over the reply at known points. Measures onset -> duck and onset -> stop, checks that backchannels and echo don't
stop her, that "go on" resumes without repeating, that "no, the other one" resolves against what was heard, and
Korean. Writes a JSON report.

WS_URL=wss://127.0.0.1:8444/ws?k=... TTS_URL=http://127.0.0.1:8091/synth python tests/interrupt_test.py [--quick]
"""
import argparse
import asyncio
import json
import os
import random
import ssl
import statistics
import time
import urllib.request

import numpy as np
import websockets

TTS = os.environ.get("TTS_URL", "http://127.0.0.1:8091/synth")
SR = 16000
FRAME = 320                  # 20 ms, like the phone
OUT_LAT = 0.08               # speaker output latency of the simulated client
FADE = 0.08                  # client fade-out on interrupted
NOISE = 10 ** (-62 / 20)
_cache = {}


def speech(text, lang, voice, speed=1.0):
    key = (text, lang, voice, speed)
    if key not in _cache:
        body = json.dumps({"text": text, "lang": lang, "voice": voice, "speed": speed}).encode()
        r = urllib.request.urlopen(urllib.request.Request(TTS, body, {"Content-Type": "application/json"}))
        x = np.frombuffer(r.read(), "<i2").astype(np.float32) / 32768
        rate = int(r.headers["X-Sample-Rate"])
        y = np.interp(np.arange(0, x.size, rate / SR), np.arange(x.size), x).astype(np.float32)
        e = np.sqrt(np.convolve(y ** 2, np.ones(160) / 160, "same"))
        v = np.flatnonzero(e > e.max() * 0.01)          # -40 dB: where a VAD starts to hear it
        _cache[key] = (y[max(0, v[0] - 1600):v[-1] + 1600] * 0.7, 1600 if v[0] >= 1600 else v[0])
    return _cache[key]


def cough():
    rng = np.random.default_rng(1)
    n = int(0.25 * SR)
    env = np.exp(-np.linspace(0, 6, n)) * np.minimum(1, np.linspace(0, 20, n))
    burst = rng.standard_normal(n) * env * 0.3
    x = np.concatenate([np.zeros(1600), burst, np.zeros(800), burst * 0.6, np.zeros(1600)]).astype(np.float32)
    return x, 1600


class Client:
    def __init__(self, ws, echo_db):
        self.ws = ws
        self.echo = 10 ** (echo_db / 20) if echo_db is not None else 0.0
        self.events = []
        self.cond = asyncio.Condition()
        self.turns = {}               # turn_id -> {"pcm": [...], "t_first": wall, "head": samples played}
        self.cur = None
        self.gain = 1.0
        self.inject = None            # (samples, start index)
        self.injected_at = None
        self.mic_pos = 0
        self.stopped = set()

    async def reader(self):
        async for m in self.ws:
            now = time.time()
            if isinstance(m, bytes):
                t = self.turns.get(self.cur)
                if t is not None and self.cur not in self.stopped:
                    t["pcm"].append(np.frombuffer(m, "<i2").astype(np.float32) / 32768)
                    t["n"] += len(m) // 2
                continue
            d = json.loads(m)
            d["_t"] = now
            if d["type"] == "audio_start":
                self.cur = d["turn_id"]
                self.turns[self.cur] = {"pcm": [], "n": 0, "head": 0, "t0": None, "done": False}
                self.gain = 1.0
            elif d["type"] == "audio_end":
                if d["turn_id"] in self.turns:
                    self.turns[d["turn_id"]]["done"] = True
            elif d["type"] == "duck" and d["turn_id"] == self.cur:
                self.gain = 10 ** (d.get("gain_db", -12) / 20) if d["on"] else 1.0
            elif d["type"] == "interrupted":
                tid = d["turn_id"]
                if tid in self.turns and tid not in self.stopped:
                    self.stopped.add(tid)
                    played = self.turns[tid]["head"] / SR
                    await self.ws.send(json.dumps({"type": "playback", "turn_id": tid,
                                                   "played_ms": round(played * 1000), "state": "stopped"}))
            async with self.cond:
                self.events.append(d)
                self.cond.notify_all()

    def playing(self):
        t = self.turns.get(self.cur)
        return t is not None and self.cur not in self.stopped and (not t["done"] or t["head"] < t["n"])

    def _play(self, n):
        """Advance the playhead by n samples (after the output latency) and return what came out."""
        out = np.zeros(n, np.float32)
        t = self.turns.get(self.cur)
        if t is None or self.cur in self.stopped or not t["pcm"]:
            return out
        if t["t0"] is None:
            t["t0"] = time.time() + OUT_LAT
        if time.time() < t["t0"]:
            return out
        if t.get("buf") is None or t["buf"].size < t["n"]:
            t["buf"] = np.concatenate(t["pcm"])
        seg = t["buf"][t["head"]:t["head"] + n]
        out[:seg.size] = seg * self.gain
        t["head"] += seg.size
        if t["head"] >= t["n"] and t["done"] and not t.get("reported"):
            t["reported"] = True
            asyncio.ensure_future(self.ws.send(json.dumps({"type": "playback", "turn_id": self.cur,
                                                           "played_ms": round(t["head"] / SR * 1000),
                                                           "state": "done"})))
        return out

    async def mic(self):
        rng = np.random.default_rng(0)
        t_next = time.time()
        while True:
            played = self._play(FRAME)
            x = rng.standard_normal(FRAME).astype(np.float32) * NOISE + played * self.echo
            if self.inject is not None:
                clip, i = self.inject
                piece = clip[i:i + FRAME]
                x[:piece.size] += piece
                self.inject = (clip, i + FRAME) if i + FRAME < clip.size else None
            await self.ws.send((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())
            self.mic_pos += FRAME
            t_next += FRAME / SR
            await asyncio.sleep(max(0.0, t_next - time.time()))

    async def say(self, clip_lead):
        """Start talking now; returns the wall time the first voiced sample enters the microphone."""
        while self.inject is not None:
            await asyncio.sleep(0.02)
        clip, lead = clip_lead
        self.inject = (clip, 0)
        onset = time.time() + lead / SR
        self.injected_at = onset
        return onset, onset + clip.size / SR - lead / SR

    async def wait(self, pred, timeout):
        end = time.time() + timeout
        async with self.cond:
            while True:
                for d in self.events:
                    if pred(d):
                        return d
                left = end - time.time()
                if left <= 0:
                    return None
                try:
                    await asyncio.wait_for(self.cond.wait(), left)
                except asyncio.TimeoutError:
                    return None

    def since(self, t0, typ, turn=None):
        return [d for d in self.events if d["_t"] >= t0 and d["type"] == typ and (turn is None or d.get("turn_id") == turn)]

    async def ask(self, clip_lead, timeout=40):
        await self.settle()
        _, t_end = await self.say(clip_lead)
        ut = await self.wait(lambda d: d["_t"] >= t_end and d["type"] == "user_turn", timeout)
        if not ut:
            return None
        d = await self.wait(lambda d: d["type"] == "audio_start" and d["turn_id"] == ut["turn_id"], timeout)
        return d["turn_id"] if d else None

    async def settle(self, quiet_s=1.5, timeout=60):
        """Nothing playing, nothing being said, and no reply on its way for `quiet_s`."""
        end = time.time() + timeout
        last = time.time()
        while time.time() < end:
            busy = self.playing() or self.inject is not None or any(
                d["type"] in ("audio_start", "user_turn", "assistant_delta") and d["_t"] > time.time() - 0.2
                for d in self.events[-20:])
            if busy:
                last = time.time()
            elif time.time() - last >= quiet_s:
                return
            await asyncio.sleep(0.05)

    async def until_heard(self, turn, seconds, timeout=30):
        end = time.time() + timeout
        while time.time() < end:
            t = self.turns.get(turn)
            if t and t["head"] >= seconds * SR:
                return True
            if t and t["done"] and t["head"] >= t["n"]:
                return False
            await asyncio.sleep(0.02)
        return False

    async def until_quiet(self, timeout=60):
        end = time.time() + timeout
        while time.time() < end and (self.playing() or self.inject is not None):
            await asyncio.sleep(0.05)
        await asyncio.sleep(0.8)

    def reply_text(self, turn):
        d = [x for x in self.events if x["type"] == "assistant_done" and x["turn_id"] == turn]
        return d[-1]["text"] if d else ""


EN, KO = "en", "ko"
QUESTIONS = {
    EN: ["Laura, tell me about the history of coffee in a few sentences.",
         "Laura, explain how airplanes stay in the air, in four or five sentences.",
         "Laura, describe what makes a good night of sleep, in a few sentences.",
         "Laura, tell me some fun facts about octopuses."],
    KO: ["로라, 커피의 역사에 대해 몇 문장으로 이야기해 줘.",
         "로라, 비행기가 어떻게 하늘에 떠 있는지 네다섯 문장으로 설명해 줘.",
         "로라, 잠을 잘 자는 방법을 몇 가지 알려 줘."],
}
INTERRUPTS = {EN: ["Wait, stop.", "Hold on, what about tea?", "No, skip that, just tell me the time.",
                   "Sorry, can you make it shorter?", "Actually, never mind."],
              KO: ["잠깐만요.", "아니, 그건 됐고 지금 몇 시야?", "그만, 짧게 말해 줘."]}
BACKCHANNELS = {EN: ["Uh-huh.", "Yeah.", "Mm-hmm.", "Okay.", "Right.", "I see.", "Wow.", "Haha."],
                KO: ["응.", "네.", "아, 그렇구나.", "음.", "맞아요.", "와."]}
VOICES = ["M1", "M2", "M3", "M4"]


async def run(a):
    ctx = ssl.create_default_context()
    ctx.check_hostname, ctx.verify_mode = False, ssl.CERT_NONE
    rnd = random.Random(7)
    res = {"interrupt": [], "backchannel": [], "echo": [], "resume": [], "other_one": [], "repeat": []}
    async with websockets.connect(os.environ["WS_URL"], ssl=ctx, max_size=None) as ws:
        c = Client(ws, a.echo_db)
        await ws.send(json.dumps({"type": "hello", "version": 1, "client": "linux", "lang": "auto",
                                  "mode": "handsfree", "gate": False, "audio": {"up": "pcm", "down": ["pcm"]}}))
        tasks = [asyncio.create_task(c.reader()), asyncio.create_task(c.mic())]
        await asyncio.sleep(1.0)

        async def interruption(lang, q, text, at_s, voice):
            turn = await c.ask(speech(q, lang, voice))
            if not turn or not await c.until_heard(turn, at_s):
                return None
            pre_ducked = c.gain < 1.0
            onset, _ = await c.say(speech(text, lang, voice))
            st = await c.wait(lambda d: d["type"] == "interrupted" and d["turn_id"] == turn, 6)
            duck = [d for d in c.since(onset - 0.3, "duck", turn) if d["on"]]
            cut = await c.wait(lambda d: d["type"] == "reply_cut" and d["turn_id"] == turn, 6)
            r = {"lang": lang, "text": text, "at_s": at_s, "voice": voice,
                 "duck_ms": 0 if pre_ducked else round((duck[0]["_t"] - onset) * 1000) if duck else None,
                 "stop_msg_ms": round((st["_t"] - onset) * 1000) if st else None,
                 "heard": cut and cut["heard"], "played_ms": cut and cut["played_ms"],
                 "reported": cut and cut["reported"], "full": None}
            if st:
                r["stop_ms"] = r["stop_msg_ms"] + round((OUT_LAT + FADE / 2) * 1000)
            await c.until_quiet()
            r["full"] = c.reply_text(turn)
            return r, turn

        n_int = 4 if a.quick else a.interrupts
        for i in range(n_int):
            lang = KO if i % 3 == 2 else EN
            r = await interruption(lang, rnd.choice(QUESTIONS[lang]), rnd.choice(INTERRUPTS[lang]),
                                   rnd.uniform(1.2, 4.0), rnd.choice(VOICES))
            if r:
                res["interrupt"].append(r[0])
                print("interrupt", json.dumps(r[0], ensure_ascii=False), flush=True)

        n_bc = 6 if a.quick else a.backchannels
        for i in range(n_bc):
            lang = KO if i % 3 == 2 else EN
            turn = await c.ask(speech(rnd.choice(QUESTIONS[lang]), lang, "M1"))
            if not turn or not await c.until_heard(turn, rnd.uniform(1.0, 3.0)):
                continue
            bc = rnd.choice(BACKCHANNELS[lang]) if i % 7 != 6 else "(cough)"
            onset, _ = await c.say(cough() if bc == "(cough)" else speech(bc, lang, rnd.choice(VOICES)))
            st = await c.wait(lambda d: d["type"] == "interrupted" and d["turn_id"] == turn, 4)
            duck = [d for d in c.since(onset - 0.05, "duck", turn)]
            ign = c.since(onset, "ignored")
            r = {"lang": lang, "text": bc, "stopped": st is not None,
                 "duck_ms": round((duck[0]["_t"] - onset) * 1000) if duck and duck[0]["on"] else None,
                 "unducked": any(not d["on"] for d in duck), "ignored": [d.get("reason") for d in ign]}
            res["backchannel"].append(r)
            print("backchannel", json.dumps(r, ensure_ascii=False), flush=True)
            if st:
                await c.until_quiet()
                continue
            # the rest of the reply must still play
            await c.until_quiet()
            t = c.turns[turn]
            r["finished"] = t["head"] >= t["n"] - SR // 10

        if a.echo_db is not None:
            for k, level in enumerate([x for x in a.loud_echo_db for _ in range(1 if a.quick else 2)]):
                lang = EN if k % 2 == 0 else KO
                c.echo = 10 ** (level / 20)
                turn = await c.ask(speech(QUESTIONS[lang][k % len(QUESTIONS[lang])], lang, "M2"))
                t_ask = time.time()
                await c.until_quiet()
                c.echo = 10 ** (a.echo_db / 20)
                r = {"lang": lang, "echo_db": level,
                     "stopped": bool(c.since(t_ask, "interrupted", turn)),
                     "ducks": sum(1 for d in c.since(t_ask, "duck", turn) if d["on"]),
                     "reply_s": round(c.turns[turn]["n"] / SR, 1) if turn in c.turns else None}
                res["echo"].append(r)
                print("echo", json.dumps(r), flush=True)

        # go on / 계속해
        for lang, q, stop, go in ((EN, QUESTIONS[EN][1], "Wait.", "Okay, go on."),
                                  (KO, QUESTIONS[KO][1], "잠깐만요.", "계속해.")):
            got = await interruption(lang, q, stop, 2.5, "M1")
            if not got:
                continue
            r0, turn = got
            t1 = await c.ask(speech(go, lang, "M1"))
            await c.until_quiet()
            said = c.reply_text(t1)
            heard, full = r0["heard"] or "", r0["full"]
            r = {"lang": lang, "heard": heard, "full": full, "resumed": said,
                 "no_repeat": bool(said) and not said.startswith(heard[:25]) if heard else None,
                 "continues": bool(said) and full.replace(" ", "").find(said.replace(" ", "")[:20]) >= len(heard.replace(" ", "")) - 15}
            res["resume"].append(r)
            print("resume", json.dumps(r, ensure_ascii=False), flush=True)

        # repeat that
        for lang, stop, rep in ((EN, "Hang on.", "Repeat that."), (KO, "잠깐.", "다시 말해 줘.")):
            got = await interruption(lang, QUESTIONS[lang][0], stop, 3.0, "M3")
            if not got:
                continue
            r0, turn = got
            t1 = await c.ask(speech(rep, lang, "M3"))
            await c.until_quiet()
            res["repeat"].append({"lang": lang, "heard": r0["heard"], "repeated": c.reply_text(t1)})
            print("repeat", json.dumps(res["repeat"][-1], ensure_ascii=False), flush=True)

        # no, the other one
        for lang, q, opts, other in (
                (EN, "Laura, compare two dinner ideas for tonight, pizza and bibimbap. Talk about pizza first, "
                     "then bibimbap, a few sentences each.", ("pizza", "bibimbap"), "No, the other one."),
                (KO, "로라, 오늘 저녁 메뉴로 피자랑 비빔밥을 비교해 줘. 피자 먼저, 그다음 비빔밥, 각각 몇 문장씩.",
                 ("피자", "비빔밥"), "아니, 다른 거.")):
            turn = await c.ask(speech(q, lang, "M1"))
            if not turn:
                continue
            done = await c.wait(lambda d: d["type"] == "assistant_done" and d["turn_id"] == turn, 40)
            await c.wait(lambda d: d["type"] == "audio_end" and d["turn_id"] == turn, 40)
            full = done["text"] if done else ""
            i2 = full.find(opts[1], len(full) // 4)
            dur = c.turns[turn]["n"] / SR
            target = dur * (i2 / max(len(full), 1)) + 1.0 if i2 > 0 else dur * 0.6
            await c.until_heard(turn, min(target, dur - 1.0))
            onset, _ = await c.say(speech(other, lang, "M1"))
            cut = await c.wait(lambda d: d["type"] == "reply_cut" and d["turn_id"] == turn, 8)
            heard = cut["heard"] if cut else ""
            ut = await c.wait(lambda d: d["_t"] >= onset and d["type"] == "user_turn"
                              and any(k in d["text"].lower() for k in ("other", "다른")), 12)
            ans = ""
            if ut:
                d = await c.wait(lambda d: d["type"] == "assistant_done" and d["turn_id"] == ut["turn_id"], 30)
                ans = d["text"] if d else ""
            await c.until_quiet()
            last = max(opts, key=lambda o: heard.rfind(o))
            target_opt = opts[1] if last == opts[0] else opts[0]
            r = {"lang": lang, "heard": heard, "answer": ans, "talking_about": last, "expected": target_opt,
                 "resolved": target_opt in ans, "second_seen": i2 > 0}
            res["other_one"].append(r)
            print("other_one", json.dumps(r, ensure_ascii=False), flush=True)

        for t in tasks:
            t.cancel()
    return res


def summary(res):
    out = {}
    stops = [r["stop_ms"] for r in res["interrupt"] if r.get("stop_ms") is not None]
    ducks = [r["duck_ms"] for r in res["interrupt"] if r.get("duck_ms") is not None]

    def q(v, p):
        return round(float(np.percentile(v, p))) if v else None

    out["interrupts"] = len(res["interrupt"])
    out["missed_interrupts"] = sum(1 for r in res["interrupt"] if r.get("stop_msg_ms") is None)
    out["duck_ms"] = {"p50": q(ducks, 50), "p90": q(ducks, 90), "n": len(ducks)}
    out["stop_ms"] = {"p50": q(stops, 50), "p90": q(stops, 90), "min": min(stops) if stops else None,
                      "max": max(stops) if stops else None, "n": len(stops)}
    bc = res["backchannel"]
    out["backchannel_false_stops"] = f"{sum(r['stopped'] for r in bc)}/{len(bc)}"
    out["echo_false_stops"] = f"{sum(r['stopped'] for r in res['echo'])}/{len(res['echo'])}"
    out["resume_ok"] = f"{sum(bool(r['continues']) for r in res['resume'])}/{len(res['resume'])}"
    out["other_one_ok"] = f"{sum(r['resolved'] for r in res['other_one'])}/{len(res['other_one'])}"
    out["cut_reported"] = f"{sum(bool(r.get('reported')) for r in res['interrupt'])}/{len(res['interrupt'])}"
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--quick", action="store_true")
    ap.add_argument("--interrupts", type=int, default=16)
    ap.add_argument("--backchannels", type=int, default=21)
    ap.add_argument("--echo-db", type=float, default=-40.0, help="residual echo after AEC (dB)")
    ap.add_argument("--loud-echo-db", type=float, nargs="+", default=[-30.0, -18.0],
                    help="echo levels for the echo-only runs")
    ap.add_argument("--out", default="interrupt_report.json")
    a = ap.parse_args()
    res = asyncio.run(run(a))
    s = summary(res)
    print(json.dumps(s, indent=1, ensure_ascii=False))
    with open(a.out, "w") as f:
        json.dump({"summary": s, "results": res}, f, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
