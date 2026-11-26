"""Repetition guard: cut_loop() on a real looping reply, then "너 어떻게 생겼어?" x N through llama-server with and
without the anti-repetition sampling (repeat_penalty + DRY), counting replies that loop.
python tests/repetition_test.py [N]"""
import os
import sys
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from assistant_server import LOOP, SAMPLING, cut_loop, system_prompt  # noqa: E402

looping = ("안녕, 호키. 나는 네 laptop 의 작은 빛나는 구석구석으로 이루어진 구석 구석으로 이루어진 구석구석으로 "
           "이루어진 구석구석으로 이루어진 구석구석으로 이루어진 구")
full, pending = cut_loop(looping, looping)
assert full == "안녕, 호키.", full
assert cut_loop("I'm a little cloud. Ha ha, that's fun.", "that's fun.") is None
full, pending = cut_loop("Okay. Okay. Okay. Sure.", "")   # already spoken: nothing more to say
assert pending == "" and full == "Okay. Okay. Okay. Sure.", (full, pending)
print("cut_loop unit checks pass")

N = int(sys.argv[1]) if len(sys.argv) > 1 else 10
URL = os.environ.get("LLM_URL", "http://127.0.0.1:8090/v1/chat/completions")
with httpx.Client(timeout=120) as http:
    for name, extra in (("plain", {}), ("repeat_penalty+DRY", SAMPLING)):
        loops, lengths = 0, []
        for _ in range(N):
            r = http.post(URL, json={"model": "bonsai-9b-voice", "max_tokens": 300, "temperature": 0.6, **extra,
                                     "messages": [{"role": "system", "content": system_prompt({})},
                                                  {"role": "user", "content": "너 어떻게 생겼어?"}],
                                     "chat_template_kwargs": {"enable_thinking": False}})
            r.raise_for_status()
            text = r.json()["choices"][0]["message"]["content"]
            hit = bool(LOOP.search(text))
            loops += hit
            lengths.append(len(text))
            if hit:
                print(f"  [{name}] loop: {text[:140]!r} -> guard keeps {cut_loop(text, text)[0]!r}")
        print(f"{name}: {loops}/{N} replies loop, mean length {sum(lengths) / N:.0f} chars", flush=True)
