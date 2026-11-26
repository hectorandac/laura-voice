"""Does the avatar part of the system prompt (body description + reaction tags) cost first-audio time?
Streams the same questions through llama-server with and without it and measures time to the first speakable
clause (tags stripped), plus how often a tag is used. python tests/avatar_latency.py"""
import json
import os
import statistics
import sys
import time
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from assistant_server import FIRST_CLAUSE, LEAD_TAG, SENT_END, system_prompt  # noqa: E402

LLM_URL = os.environ.get("LLM_URL", "http://127.0.0.1:8090/v1/chat/completions")
QS = ["What's a good recipe with eggs, rice and kimchi?", "내일 아침에 일찍 일어나려면 뭘 하면 좋을까?",
      "What do you look like?", "I finally fixed that bug I told you about!", "오늘 너무 피곤하다.",
      "How far is Busan from Daegu?", "Tell me a joke.", "로라, 너 어떻게 생겼어?"]


def first_clause(http, system, q):
    body = {"model": "bonsai-9b-voice", "stream": True, "max_tokens": 120, "temperature": 0.6,
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": q}],
            "chat_template_kwargs": {"enable_thinking": False}}
    t, text, first = time.time(), "", None
    with http.stream("POST", LLM_URL, json=body) as r:
        for line in r.iter_lines():
            if not line.startswith("data: ") or line.endswith("[DONE]"):
                continue
            text += json.loads(line[6:])["choices"][0]["delta"].get("content") or ""
            stripped = LEAD_TAG.sub("", text).lstrip()
            if first is None and (SENT_END.match(stripped) or FIRST_CLAUSE.match(stripped)):
                first = time.time() - t
    return first or (time.time() - t), text


with httpx.Client(timeout=60) as http:
    for avatar in (False, True, False, True):
        sp = system_prompt({}, avatar=avatar)
        first_clause(http, sp, "hi")
        ts, tags = [], 0
        for q in QS:
            dt, text = first_clause(http, sp, q)
            ts.append(dt * 1000)
            tags += bool(LEAD_TAG.match(text))
            print(f"  avatar={avatar} {dt * 1000:4.0f} ms  {text[:90]!r}")
        print(f"avatar={avatar}: first clause median {statistics.median(ts):.0f} ms, mean {statistics.mean(ts):.0f} ms, "
              f"tagged {tags}/{len(QS)}, system prompt {len(sp)} chars", flush=True)
