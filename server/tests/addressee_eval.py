"""Addressee gate on a labelled text set (tests/addressee_set.jsonl): precision/recall and classifier latency.
Uses the live llama-server (LLM_URL). Items are fresh utterances outside the conversation window; the name layer
is deterministic, the LLM yes/no probability is computed once per item and swept over thresholds.

python tests/addressee_eval.py
"""
import asyncio
import json
import os
import sys
import time
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import addressee  # noqa: E402

LLM_URL = os.environ.get("LLM_URL", "http://127.0.0.1:8090/v1/chat/completions")
LLM_MODEL = os.environ.get("LLM_MODEL", "bonsai-9b-voice")
LAST_REPLY = "Tomorrow should be sunny, around twelve degrees in the afternoon."


def prf(rows, th, key="p"):
    pred = [(r["name"] or (not r["other"] and (r["greet"] or r[key] >= th)), r["label"]) for r in rows]
    tp = sum(1 for p, l in pred if p and l)
    fp = sum(1 for p, l in pred if p and not l)
    fn = sum(1 for p, l in pred if not p and l)
    return {"precision": round(tp / (tp + fp), 3) if tp + fp else 1.0,
            "recall": round(tp / (tp + fn), 3) if tp + fn else 1.0, "fp": fp, "fn": fn, "n": len(rows)}


async def main():
    items = [json.loads(l) for l in (Path(__file__).parent / "addressee_set.jsonl").read_text().splitlines() if l]
    async with httpx.AsyncClient(timeout=60) as http:
        clf = addressee.AddresseeClassifier(http, LLM_URL, LLM_MODEL)
        await clf.p_addressed("warm up")
        lat = []
        for it in items:
            it["name"] = addressee.mentions_name(it["text"])
            it["other"] = addressee.talks_to_someone_else(it["text"])
            it["greet"] = bool(addressee.GREETING.match(it["text"].strip()))
            it["p"], ms = await clf.p_addressed(it["text"])
            it["p_win"], _ = await clf.p_addressed(it["text"], LAST_REPLY, 8.0)
            lat.append(ms)
        lat.sort()
        print(f"classifier latency median {lat[len(lat) // 2]} ms, p90 {lat[int(len(lat) * .9)]} ms, max {lat[-1]} ms")
        ko = [r for r in items if any("가" <= c <= "힣" for c in r["text"])]
        en = [r for r in items if r not in ko]
        print("outside the window (default 0.6):")
        for th in (0.25, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9):
            print(f"  th {th:.2f} all {prf(items, th)} | ko {prf(ko, th)} | en {prf(en, th)}")
        print(f"inside the window, after the reply {LAST_REPLY!r} (threshold 0.25):")
        for th in (0.1, 0.25, 0.4):
            print(f"  th {th:.2f} all {prf(items, th, 'p_win')} | ko {prf(ko, th, 'p_win')} | en {prf(en, th, 'p_win')}")
        for r in sorted(items, key=lambda r: (r["label"], r["p"] or 0)):
            print(f"  {r['label']} {r['kind']:12s} name={int(r['name'])} other={int(r['other'])} p={r['p']:.3f} "
                  f"p_win={r['p_win']:.3f}"
                  f"  {r['text']}")
        g = addressee.Gate(clf)
        g.note_reply("It will be sunny tomorrow.", time.time())
        print("window follow-up:", await g.decide("And what about Sunday?", {}))
        print("others present, in window:", await g.decide("Honey, can you grab my jacket?", {}, others_present=True))
        g.reply_end = 0
        print("others present, no name:", await g.decide("What's the capital of Australia?", {}, others_present=True))
        g.muted = True
        print("muted:", await g.decide("Laura what time is it", {}))
        print("muted, resume:", await g.decide("Laura, start listening again", {}))


asyncio.run(main())
