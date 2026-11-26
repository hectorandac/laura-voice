"""End-to-end check of the wake flag and the source tag against a gateway (a separate test instance).

Hands-free, PCM. Without a wake flag a request with no name, outside the follow-up window, is ignored; the same
request right after {"type":"wake"} is answered (why "wake"). Replies carry `source` on assistant_delta,
assistant_done and audio_start.

WS_URL=wss://127.0.0.1:8444/ws?k=... TTS_URL=http://127.0.0.1:8091/synth python tests/wake_ws_test.py
"""
import asyncio
import json
import os
import ssl
import sys

from gate_ws_test import Client, speech


async def main():
    ctx = ssl.create_default_context()
    ctx.check_hostname, ctx.verify_mode = False, ssl.CERT_NONE
    results = {}
    import websockets
    async with websockets.connect(os.environ["WS_URL"], max_size=None, ssl=ctx) as ws:
        c = Client(ws)
        tasks = [asyncio.create_task(c.reader()), asyncio.create_task(c.pump())]
        await ws.send(json.dumps({"type": "hello", "version": 1, "client": "test", "lang": "auto",
                                  "mode": "handsfree", "context": {}}))
        await c.until({"speaker_profile"})
        await asyncio.sleep(22)                         # the hello opens a follow-up window; let it lapse

        async def turn(label, text, lang, expect, wake=False):
            if wake:
                await ws.send(json.dumps({"type": "wake", "score": 0.91}))
            await c.say(speech(text, lang, "M1"))
            d, got = await c.until({"user_turn", "ignored"}, 25)
            outcome = d["type"] if d else "timeout"
            r = {"outcome": outcome, "why": d.get("why") if d else None, "text": d.get("text") if d else None}
            if outcome == "user_turn":
                e, got2 = await c.until({"audio_end"}, 60)
                tagged = {m["type"]: m.get("source") for m in got2 if m["type"] in ("assistant_delta", "assistant_done", "audio_start")}
                r["source"] = tagged
                r["source_ok"] = len(tagged) == 3 and all(v and v.get("engine") in ("local", "hermes") for v in tagged.values())
            r["ok"] = outcome == expect and r.get("source_ok", True)
            results[label] = r
            print(f"{'PASS' if r['ok'] else 'FAIL'} {label}: {json.dumps(r, ensure_ascii=False)}", flush=True)

        await turn("no_name_no_wake_en", "What's the capital of Australia?", "en", "ignored")
        await turn("no_name_wake_en", "What's the capital of Australia?", "en", "user_turn", wake=True)
        await asyncio.sleep(24)
        await turn("no_name_no_wake_ko", "호주의 수도가 어디야?", "ko", "ignored")
        await turn("no_name_wake_ko", "호주의 수도가 어디야?", "ko", "user_turn", wake=True)
        for t in tasks:
            t.cancel()
    ok = all(r["ok"] for r in results.values())
    print("ALL PASS" if ok else "SOME FAILED")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    asyncio.run(main())
