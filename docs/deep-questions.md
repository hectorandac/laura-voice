# Deep questions: Laura thinks it through, out loud

Status: design proposal, built on the `ask_laura` handoff ([hermes.md](hermes.md)). Not implemented yet.

Today a spoken request goes one of two ways. The local 9B answers it (fast, shallow), or it becomes a **task**: a
full Hermes turn with tools, about 25 s at the median, shown as a card. A third kind is missing: questions that
need a strong model's thinking but no tools or data. Examples: "explain sliding-window attention", "help me
think through these two offers", "write a regex for Korean phone numbers". This document adds an **ask** lane
for them. Laura's main model answers immediately, streamed, and spoken in her own voice.

| Lane | Trigger | Model | First audio | Tools | Quota per use |
|---|---|---|---|---|---|
| local | everything else | Bonsai 2 9B on the GPU node | ~1 s | none | none |
| **ask** (new) | `think_deeper` tool or a forced phrase | Laura's main model, one streamed call | filler at ~1 s, answer at ~4-6 s | none | 1 call, ~4K in / ~0.3-0.7K out |
| task | `ask_laura` tool | Laura's full agent | "On it." at ~1 s, result later | all | a full turn (~28K-token calls, often several) |

## 1. Routing

### The local model decides, with one tool per lane

The fast model gets a second tool next to `ask_laura`:

```json
{"type": "function", "function": {
  "name": "think_deeper",
  "description": "Answer with Laura's full reasoning model instead of yourself. Use for questions that need careful or multi-step reasoning, expert knowledge, comparisons and advice with trade-offs, writing or reviewing code, math beyond arithmetic, or a long explanation, and whenever you are not confident you would be right. Not for anything that needs live data, Hector's files/accounts/homelab, or doing something: that is ask_laura.",
  "parameters": {"type": "object", "properties": {
    "question": {"type": "string", "description": "The question, self-contained, in Hector's language."},
    "filler": {"type": "string", "description": "A few words to say right away, e.g. 'Let me think about that properly.'"}},
    "required": ["question"]}}}
```

The routing rules added to the fast lane's system prompt (after the persona):

```text
## How you answer
You are Laura's quick voice. Pick exactly one:
1. Answer yourself: greetings, small talk, things said earlier in this conversation, simple facts you are sure
   of, quick arithmetic, translations of short phrases. Keep it to one or two sentences.
2. think_deeper(question): when a good answer needs careful or multi-step reasoning, expert knowledge,
   weighing options, code, or more than a few sentences, or when you are not sure you would be right.
3. ask_laura(task): when it needs live or current information (news, weather, prices, versions, scores),
   Hector's own data (calendar, finances, files, messages), the homelab, or doing anything.
If ask_laura fits, prefer it over think_deeper. Never guess facts to avoid a handoff.
```

Why a tool rather than heuristics in code: the 9B already reads the request, and a tool call costs no extra
round trip. Tool calling through this llama.cpp build was verified with the 27B (opencode). How reliably the 9B
picks between two tools is untested, and is what the evaluation below measures.
Heuristics alone (length, question words) misfire on short hard questions ("is P equal to NP?"). Low confidence
can't be measured reliably from a 9B's output. Instead the prompt tells it to hand off when unsure, and the
evaluation below measures how often it doesn't.

### Forcing it by voice

Before calling the 9B, the gateway checks the transcript against forced phrases. On a match, answering locally
is no longer an option: the request still goes to the 9B, but with `tool_choice: "required"`, so it must pick
`think_deeper` or `ask_laura`. A task wins when the rest of the request needs an action (`routing_cases.jsonl`
has one such case).

```python
FORCE_DEEP = re.compile(
    r"\b(think (about )?(this|it|that) (properly|carefully|through|hard)|think deeper|take your time|"
    r"give it (some )?(real )?thought)\b|깊게 생각|깊이 생각|잘 생각해|제대로 생각|곰곰이|신중하게 생각|"
    r"pi[eé]nsalo bien", re.I)
```

The phrases are deliberately few and distinctive; anything else goes through the model.

### Test set and evaluation

`server/tests/routing_cases.jsonl` has 38 labelled utterances (English, Korean and one Spanish): `local`,
`deep` and `task`, with the `forced` ones marked. The evaluation runs the fast-lane prompt plus both tools
against the live llama-server, takes the first tool call (none = `local`), and prints a confusion matrix.

| Must hold | Why |
|---|---|
| forced cases: 100% not `local` | Hector asked for it explicitly |
| `task` → `local` = 0 | the fast model must never invent live data |
| `deep` → `local` ≤ 15% | missed depth is recoverable ("think about it properly") |
| `local` → `deep`/`task` ≤ 10% | each misroute costs quota and seconds |

Run the set after any change to the prompt, the model or the tools, and extend it with real misroutes taken
from the gateway log (`routed=` lines, text only with consent).

## 2. Conversation UX

1. **Immediately** (same timing as today's replies): the gateway speaks the filler from the tool call, or a
   canned one in the user's language ("Let me think about that properly." / "잠깐, 제대로 생각해 볼게요."). The
   client shows a *thinking* card with the question.
2. **Streaming**: Laura's main model is told to answer in two parts:
   `SPOKEN: <one to three sentences that answer the question out loud>` then `DETAIL: <anything longer, may use
   markdown>`. The gateway feeds the spoken part sentence by sentence to TTS as it streams, the same path as
   local replies, so the first sentence is heard about 1 s after its last token. The detail part goes only to
   the card. There is no second summarisation pass and no extra latency. If the model ignores the format, the
   first 3 sentences are spoken and the rest is card text.
3. **Waiting**: if nothing arrives within 8 s, one short "Still thinking…" (once). The card shows streaming
   text throughout.
4. **Barge-in while thinking**: Hector speaking does not cancel the question. The new utterance is handled
   normally, and the deep answer continues into the card. Its spoken part is held and offered at the next quiet
   moment ("About your earlier question: …"), like task results. **Cancel** is explicit: a stop word on its own
   ("never mind", "cancel that", "됐어", "그만", "취소"), or the card's cancel button. Either sends `ask_cancel`,
   and the plugin closes the stream, so no further tokens are spent.
5. **Barge-in while it speaks**: same as any reply: audio stops, the card keeps the full text.
6. **Escape to a task**: if the main model realises it needs data or actions, it answers
   `TASK: <task>` instead. The plugin turns that into a normal task. The spoken filler already covered the wait,
   so Laura adds only "I'll check and get back to you."

## 3. Hermes path

**Can Hermes v0.22.5 answer one question synchronously, streamed, without a full task?** Yes, three ways:

| Path | What runs | Streams | Side effects | Verdict |
|---|---|---|---|---|
| `/v1/chat/completions` or `/v1/responses` (API server) | a full `AIAgent` turn with the `api_server` platform toolset (configurable via `platform_toolsets.api_server`, can be `[]`) | yes (SSE) | creates a session, post-turn background review and title generation (extra Haiku calls), needs `apiServer.enabled` + `API_SERVER_KEY` and a new listener | heavier, more quota, more surface |
| plugin turn in a second chat with `platform_toolsets.laura_voice: []` | a full turn, but the toolset is per platform, so tasks would lose their tools too | via message edits only | same post-turn work | doesn't fit |
| **plugin-side one-shot**: `agent.auxiliary_client.call_llm(provider=<main>, model=<main>, messages=…, stream=True)` | one model call, no agent loop | **yes, token deltas** | none (no session, no reviews) | **recommended** |

**Measured** (2 live calls, plugin-side path, Claude Sonnet 5.5 via the subscription provider, from the Hermes pod):

| Prompt | First token | Complete | Tokens | Notes |
|---|---|---|---|---|
| 1-line system prompt, short question | 3.3 s | 4.5 s (34 deltas) | ~0.1K in / 120 out | real token streaming through the provider's relay |
| Laura's persona (SOUL + USER + MEMORY, 9.6K chars) + voice rules | 5.4 s | 8.2 s | 4,032 in (3,663 written to the 1 h cache) / 637 out (336 thinking) | list-price equivalent $0.022, drawn from the Pro quota |

Expected in steady state: later questions within the hour read the persona from cache. With
`reasoning_config={"effort": "low"}` (or thinking off) the thinking tokens before the first word should mostly
go away, bringing first token to roughly 3 s. With the filler spoken at ~1 s, the perceived wait is about
2 to 4 s. Quota: one call of ~4K input (mostly cached) and ~0.3-0.7K output per question. That is roughly a
tenth of a typical task turn, and 25 deep questions cost about as much as one heavy task. Pro-plan 429s
surface as `ask_error`. The gateway then says "I can't think that through right now; want me to try later?"
and offers to queue it as a task.

**Context.** Pass the last 6 turns of the spoken conversation (the gateway already keeps them), so follow-ups
like "and why is that?" work. Pass nothing else: no device `context` (location, car), no earlier tasks' results
beyond what was said aloud. The answer is appended to the gateway's conversation history as Laura's own words,
so a later task handoff carries it too. Nothing is written to Hermes' session database or long-term memory: a
deep question is a conversation, not a task. If Hector wants it remembered, he says so, and that is a task.

**Privacy controls.**

- `ask.context_turns` (default 6; 0 = question only) and `ask.enabled` in the gateway env.
- **Off the record**: a client toggle or a voice phrase ("off the record", "이건 비밀이야") sets
  `context.privacy = "local_only"`. The fast lane then never offers `think_deeper` or `ask_laura`, and says so if
  asked. The client shows a lock badge. It resets when the session ends.
- Only the enrolled speaker can trigger `ask`/`task` once speaker verification lands (SPEECH agent). Guests get
  the local lane.
- Logging: the plugin and the gateway log ids, lengths, timings and token counts, never question or answer text.
- The phone card states which lane answered ("thought it through"), so it's clear when text left the house.

## 4. Persona continuity

The deep answer must be Laura, not a second assistant:

- **Same identity**: the plugin builds the system prompt from the same `SOUL.md`, `USER.md` and `MEMORY.md` it
  pushes to the gateway, followed by the same voice rules the fast lane uses (first person, plain speech, the
  user's language, no markdown in the spoken part, never mention models, providers or "my reasoning engine").
- **Same voice**: TTS stays in the gateway, so the answer is spoken by the same Supertonic voice as the fast lane.
- **Continuous framing**: the filler is in Laura's voice and first person ("Let me think about that properly"),
  never "connecting you to…". The answer is appended to the shared history as an assistant turn, so the fast model
  treats it as something it said.
- **No self-contradiction**: the deep prompt includes the last turns. If the fast model already said something
  wrong, the deep answer corrects it plainly ("Actually, I was off a moment ago: …").

## 5. Protocol and code changes (proposal)

### Client protocol (additive)

| Direction | Message |
|---|---|
| server → client | `{"type":"ask","ask_id","state":"thinking"\|"streaming"\|"done"\|"cancelled"\|"failed","question"}` |
| server → client | `{"type":"ask_delta","ask_id","part":"spoken"\|"detail","text"}`: card text, streamed |
| client → server | `{"type":"ask_cancel","ask_id"}` |
| client → server | `context.privacy: "local_only"` (existing `context` message, new key) |

Spoken audio uses the normal reply path with `turn_id = ask_id` (`audio_start`, binary, `audio_end`).

### Link (gateway ⇄ plugin)

| Direction | Message |
|---|---|
| gateway → plugin | `{"type":"ask","ask_id","question","context":[…],"lang"}` / `{"type":"ask_cancel","ask_id"}` |
| plugin → gateway | `{"type":"ask_delta","ask_id","text"}` / `{"type":"ask_done","ask_id","usage":{"in","out","cached"}}` / `{"type":"ask_error","ask_id","message"}` / `{"type":"ask_task","ask_id","task"}` (escape to a task) |

### Plugin (`hermes/laura_voice/adapter.py`)

```python
ASK_RULES = ("\n\n## Answering out loud\nYou are answering Hector by voice. Reply as yourself, first person. "
             "Write 'SPOKEN:' then one to three plain sentences that answer him, then 'DETAIL:' and anything "
             "longer (markdown allowed). If a correct answer needs live data, his files or accounts, the "
             "homelab or an action, reply only 'TASK: <what to do>'.")

async def _ask(self, msg):                      # on {"type": "ask"}
    ask_id, stop = msg["ask_id"], threading.Event()
    self._asks[ask_id] = stop
    persona = await asyncio.to_thread(read_persona, _hermes_home())
    system = "\n\n".join(v for v in persona.values() if v) + ASK_RULES
    messages = [{"role": "system", "content": system}, *msg.get("context", [])[-ASK_CONTEXT:],
                {"role": "user", "content": msg["question"]}]
    loop, q = asyncio.get_running_loop(), asyncio.Queue()

    def run():                                  # aux client is sync; stream in a worker thread
        from agent.auxiliary_client import call_llm
        try:
            for ev in call_llm(provider=MAIN_PROVIDER, model=MAIN_MODEL, messages=messages, stream=True,
                               max_tokens=1200, timeout=120, reasoning_config={"enabled": True, "effort": "low"},
                               stream_options={"include_usage": True}):
                if stop.is_set():
                    break                       # leaving the iterator closes the HTTP stream
                loop.call_soon_threadsafe(q.put_nowait, ("ev", ev))
        except Exception as e:                  # 429, auth, timeout
            loop.call_soon_threadsafe(q.put_nowait, ("err", str(e)[:200]))
        loop.call_soon_threadsafe(q.put_nowait, ("end", None))

    threading.Thread(target=run, daemon=True).start()
    # forward deltas as ask_delta; on a leading "TASK:" stop and emit ask_task; finish with ask_done(usage)
```

`MAIN_PROVIDER`/`MAIN_MODEL` come from Hermes' resolved main runtime (`model.provider` / `model.default`), and can
be overridden with `LAURA_VOICE_ASK_PROVIDER`/`_MODEL`. Asks run independently of the task queue; at most one at
a time (a new ask cancels nothing and waits).

### Gateway (`server/laura_link.py`)

- `THINK_DEEPER` next to `ASK_LAURA` in `stream()`, offered only when the link is up and
  `context.privacy != "local_only"`.
- `FORCE_DEEP` check before the fast model call. On a match: `tool_choice` restricted to the two handoff tools.
- On a `think_deeper` call: speak the filler, send `ask`, and register a per-ask consumer. That consumer:
  - splits `SPOKEN:`/`DETAIL:`;
  - streams spoken sentences into `session._speak(ask_id, …)` while the session is idle, or holds them for
    the next quiet moment if Hector is talking;
  - forwards everything as `ask_delta`;
  - appends the spoken answer to `session.history`.
- An 8 s "still thinking" timer, `ask_cancel` handling (control message and stop-word utterances), and
  `ask_task` → `submit()`.
- Tests: forced-phrase regex on `routing_cases.jsonl` (`forced` rows), SPOKEN/DETAIL splitting with odd chunk
  boundaries, cancel closing the stream, privacy mode removing the tools.

### Effort

About a day: ~120 lines in the plugin, ~150 in the gateway, the evaluation script, and the client card. It reuses
the existing link, history and TTS path, and needs no Hermes chart change beyond what is already prepared.
