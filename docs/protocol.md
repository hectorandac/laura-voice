# Client ↔ voice gateway protocol (v1)

One WebSocket per client at `wss://<gateway>/ws?k=<access key>`. JSON text frames are control messages;
binary frames are audio. Changes must be additive: new message types or optional fields only. Clients ignore
unknown message types; the server ignores unknown fields.

Preferred ways to send the access key, so it never appears in a URL (logs, proxies, history):
`Authorization: Bearer <key>` (native clients) or the subprotocols `laura.v1, key.<key>` (browsers; the server
accepts `laura.v1`). `?k=` keeps working for older clients.

## Client → server

| Message | Meaning |
|---|---|
| `{"type":"hello","version":1,"client":"pwa"\|"android"\|"wear"\|"linux",...,"lang":"auto"\|"ko"\|"en","mode":"handsfree"\|"ptt","context":{},"audio":{"up":"opus"\|"pcm","down":["opus","pcm"]}}` | First message. `audio` negotiates codecs (absent = PCM both ways). |
| binary | Microphone audio. `audio.up == "pcm"`: PCM16 LE mono 16 kHz, any frame size (~100 ms recommended). `"opus"`: one raw Opus packet per message (16 kHz mono, ~20 ms, 24–40 kbps, no Ogg container). In hands-free mode clients only send while their speech gate is open (300 ms lead-in, ~1.2 s hang-over); silence costs nothing. |
| `{"type":"end_turn"}` | Push-to-talk released. |
| `{"type":"interrupt"}` | User barged in: stop the current reply. |
| `{"type":"mode","value":...}` / `{"type":"lang","value":...}` | Change mode or language. |
| `{"type":"context","data":{...}}` | Situational cues; replaces the previous context. Known keys: `car` (bool, see Car mode below). Reserved: `location`, `activity`, `others_present`, `device`. |
| `{"type":"ping","t":<client clock ms>}` | Latency probe; server answers `pong` with the same `t`. |
| `{"type":"client_stats",...}` | Periodic telemetry (rtt_ms, up/down kbps, MB, turn_ms, codecs, playback counters). Logged by the server. |

## Server → client

| Message | Meaning |
|---|---|
| `{"type":"session","id"}` | Session accepted. |
| `{"type":"state","value":"listening"\|"thinking"\|"speaking"\|"idle"}` | Assistant state. |
| `{"type":"partial","text"}` | Live transcript of the current utterance (full hypothesis, not a delta). |
| `{"type":"user_turn","turn_id","text","lang"}` | Final transcript of a user turn the assistant will answer. |
| `{"type":"assistant_delta","turn_id","text"}` / `{"type":"assistant_done","turn_id","text"}` | Reply text, streamed then final. |
| `{"type":"audio_start","turn_id","sample_rate","codec":"opus"\|"pcm"}` | Reply audio follows as binary frames: Opus = one packet per message at `sample_rate` (24 kHz); PCM = PCM16 LE mono at `sample_rate` (16 kHz) in ~100 ms pieces. |
| `{"type":"audio_end","turn_id"}` | End of reply audio. Drop audio for a `turn_id` after sending `interrupt`. |
| `{"type":"metrics","turn_id","asr_ms","llm_first_ms","tts_first_ms","server_first_audio_ms",...}` | Server-side timing for the turn (from end-of-turn detection). Optional extras: `spk_ms`, `speaker_score`, `gate_ms`, `classifier_ms`, `p`. |
| `{"type":"pong","t"}` | Answer to `ping`. |
| `{"type":"error","message"}` | Recoverable error. |

Close code `4401`: bad access key. Close code `4000`: another device took over the single conversation; the
displaced client must not auto-reconnect (show "in use on another device", reconnect on user action).

## Owner voice and addressee gate (v1, additive)

In hands-free mode the server only answers utterances that are (1) in the enrolled owner's voice, when a voice
profile exists, and (2) addressed to the assistant: the name ("Laura", 로라, common misspellings) anywhere in the
utterance, a follow-up within ~20 s of the last answered turn, reply or connect, a bare greeting, or, otherwise, a yes from a small LLM
classifier. When another voice was heard recently (or `context.others_present` is true), only the name counts
outside the window. Push-to-talk turns skip both checks. Everything else gets an `ignored` event instead of
`user_turn`; partials are still sent while it is heard. With `context.car` = true the addressee gate is replaced
by the reply model's own decision (see Car mode); the owner check and mute still apply.

Client → server:

| Message | Meaning |
|---|---|
| `hello` field `"gate": false` | Optional: answer every hands-free utterance (owner check still applies). |
| `{"type":"gate","value":bool}` | Toggle the addressee gate during the session. |
| `{"type":"mute","value":bool}` | Mute/unmute from the UI (same as saying "Laura, stop listening" / "start listening"). |
| `{"type":"recall"}` | "That was for you" button: answer the most recent ignored (`not_addressed`) utterance, joined with ignored ones just before it (≤ 20 s apart, ≤ 90 s old, max 3). |
| `{"type":"enroll_start"}` | Start voice enrollment: the server replies with `enroll_prompt`s; the next turns (hands-free or PTT) are enrollment clips, not questions. ~6 sentences, ~30 s, Korean and English. |
| `{"type":"enroll_cancel"}` | Abort enrollment (answered with `enroll_done` `ok:false`, `reason:"cancelled"`). |
| `{"type":"enroll_delete"}` | Delete the stored voice profile (verification off until re-enrolled). |

Server → client:

| Message | Meaning |
|---|---|
| `{"type":"speaker_profile","available","enrolled","seconds","created","threshold"}` | Sent after `session` and whenever the profile changes. |
| `{"type":"enroll_prompt","index","total","text","lang"}` | Show this sentence and ask the user to read it aloud. |
| `{"type":"enroll_progress","index","total","ok","seconds","text","reason"?}` | Result for one clip; `ok:false`, `reason:"too_short"` re-prompts the same index. |
| `{"type":"enroll_done","ok","seconds"?,"clips"?,"threshold"?,"consistency"?,"reason"?}` | Enrollment finished (`reason`: `inconsistent` = clips don't sound like one speaker, `cancelled`). |
| `{"type":"ignored","turn_id","reason":"not_owner"\|"not_addressed"\|"muted","text","score"?,"why"?}` | The server heard `text` but stays silent. `why` details `not_addressed`: `classifier`, `others_present`, `classifier_error`, ... |
| `{"type":"recall","ok":true,"turn_id","recalled":[turn_id,...],"text"}` | A recall (spoken "that was for you, Laura" / "로라, 너한테 한 말이야" / "Laura, answer that", or the `recall` message) answers the earlier ignored turns listed in `recalled`; `user_turn` with the same `turn_id` and the joined `text` follows. `{"type":"recall","ok":false,"reason":"nothing_to_recall"}` when there is nothing recent. |
| `{"type":"avatar","turn_id","reaction"?,"emotion"?}` | Avatar cue for a reply, sent right after its `audio_start` so it lands with the voice. `reaction` (one-shot): `nod`, `shake`, `happy`, `laugh`, `confused`, `thinking_hard`, `surprised`, `sad`, `shy`, `excited`, `sleepy`, `wave`, `celebrate`, `shrug`, `focus`. `emotion` (persistent mood): `happy`, `confused`, `apologetic`, `none`. Either field may be absent; clients ignore unknown names. Never sent with a reaction in car mode; at most one reaction every two replies, never the same one twice in a row, and `focus`/`shrug` only on short replies (sent once the reply text is complete). The LLM writes these as leading `<r:nod>` / `<e:happy>` tags; the server strips them from `assistant_delta`/`assistant_done` text and from TTS. |
| `{"type":"muted","value":bool}` | Mute state (sent after `session` and on every change). While muted only the resume phrase is acted on. |
| `{"type":"interrupted","turn_id","reason":"barge_in"}` | The owner talked over a reply: stop playback of `turn_id` now and drop its remaining audio. With a profile enrolled the server verifies the voice (~0.8 s of speech) before interrupting, so TV or other people don't cut replies off; clients that do their own level-based barge-in bypass that check. |

## Car mode (v1, additive)

With `context.car` = true (hands-free), she never drops out of the conversation: there is no follow-up window to
expire, no `ignored` `not_addressed` and nothing to recall or re-wake. Every utterance that passes the owner check
(any speech when no voice profile is enrolled) goes to the reply model, whose car-mode prompt tells it to answer
when Hector talks to her or a short answer is clearly useful, and otherwise to reply exactly `<silent>`.

- A silent reply is swallowed: no `user_turn`, no `state` change, no `assistant_*`, no audio, no `ignored`. The
  client keeps showing `listening`. Empty replies, `[silent]`/`<silence>`-style variants, and replies that open by
  saying it wasn't meant for her ("I'm not sure that was for me", "저한테 하신 말씀이에요?") count as silent.
- Otherwise the turn appears as usual, only slightly later: `user_turn`, then `state` `thinking`, are sent once the
  first characters of the reply show she is talking (a few tokens; ordinary replies are released on their first
  word).
- She keeps up to three utterances from the last 90 s that she stayed silent for, so "Laura, answer that" or a
  follow-up that refers to them works without a tap. They are dropped once she answers.
- The name ("Laura", 로라) still works and counts as addressing her, but isn't needed. A `wake` turn, spoken
  mute/resume, "go on" / "repeat that" and push-to-talk are answered as outside the car.
- Gate decisions log `why: "car"` (or `"name"`); `metrics` no longer carry `classifier_ms`/`p` in the car.

## Interruptions (v1, additive)

Talking over Laura works like talking over a person: her voice ducks as soon as the gateway hears speech over a
reply, and the reply stops only once that speech is clearly an interruption. Backchannels ("uh-huh", "yeah",
"응", "네", laughter), coughs and her own echo only duck her briefly. The gateway then records how much of the
reply Hector actually heard, so the model can resolve "no, the other one", "go on" and "repeat that". Owner: interrupts agent.

1. Voice onset over a reply (~100 ms of speech on the echo-cancelled stream): `duck` `on:true`.
2. The gateway transcribes the speech so far (first look at ~0.3 s, then every 0.2 s). It commits when it hears
   anything but a backchannel or her own words. "stop", "wait", "no", "잠깐", "그만", "아니" commit on the
   first look. With a voice profile, the speaker must also be verified. Commit sends `interrupted` `reason:"barge_in"`.
3. Otherwise, after 0.35 s of silence: `duck` `on:false`, and the utterance ends as `ignored` with
   `reason:"backchannel"` or `"echo"`.

Client → server:

| Message | Meaning |
|---|---|
| `{"type":"interrupt","turn_id"?,"played_ms"?}` | As before; optional fields say which reply was stopped and how much of its audio was heard. |
| `{"type":"playback","turn_id","played_ms","state":"stopped"\|"done"}` | Sent when the client stops a reply's audio early (`stopped`, e.g. after `interrupted`) and when it finishes playing it (`done`). `played_ms` = reply audio actually played out of the speaker since that turn's `audio_start`, net of output buffering (Android: `AudioTrack.getTimestamp`). Without it the gateway estimates from send times. |

Server → client:

| Message | Meaning |
|---|---|
| `{"type":"duck","turn_id","on":bool,"gain_db":-12}` | Lower (`on:true`) or restore the volume of `turn_id`'s playback. Ramp ~30 ms down, ~150 ms up. |
| `{"type":"interrupted","turn_id","reason":"barge_in"\|"client"}` | As before. Clients fade out over ~80 ms instead of cutting, drop the rest of the turn's audio, and answer with `playback` `stopped`. |
| `{"type":"reply_cut","turn_id","played_ms","heard","reported"}` | Where the reply was cut: `heard` is the text Hector heard (word-level estimate within the sentence). Clients may trim the displayed reply to it. `reported:false` = estimated. |
| `ignored` `reason:"backchannel"\|"echo"` | Speech over a reply that was not an interruption. Not recallable; clients don't need to show it. |

The model sees a cut reply in its history as the heard part followed by
`— [Hector interrupted me here and did not hear the rest: "…"]`. "go on" / "continue" / "계속해" continues from
the cut point (spoken directly when the reply had been fully generated). "repeat that" / "다시 말해줘" repeats the
sentence that was cut, or the last reply (its last sentence if longer than two).

## Planned additions (owners noted)

- Laura via the Hermes gateway: see below (implemented, off unless the gateway has `LAURA_LINK_TOKEN`).
  Owner: platform agent.

## Laura's tasks and approvals (v1, additive)

Live conversation stays on the gateway's fast local model, speaking as Laura. When a request needs Laura's
full agent (tools, memory, homelab, web, scheduling), the model hands it to Hermes: the reply audio is a
short spoken acknowledgment, and the task then reports progress and its result through the messages below.
The result is also spoken as a one-to-three sentence summary through the normal reply audio path (a new
`turn_id` with `assistant_done`, `audio_start`, binary audio, `audio_end`, but no `user_turn` before it).
These messages only exist when the gateway runs with `LAURA_LINK_TOKEN` (the Hermes link); without it the
gateway never sends them and ignores `approval_response`/`task_cancel`. Messages that arrive while no client is connected are kept (up to 20) and replayed after the next `hello`.

Server → client:

| Message | Meaning |
|---|---|
| `{"type":"laura_status","connected":bool}` | Whether Laura's Hermes gateway is linked. Sent after `hello` and on change. When `false` the fast model still talks but cannot hand off tasks. |
| `{"type":"task","task_id","state":"queued"\|"running"\|"done"\|"failed"\|"cancelled","title"}` | Task card: create on first sight, update on each state. Tasks run one at a time, in order. |
| `{"type":"task_progress","task_id","text"}` | Intermediate message from Laura while the task runs (tool progress, interim notes). Display only. |
| `{"type":"laura_message","task_id"\|null,"text","final":true}` | Laura's full reply (markdown allowed). `task_id` null = unsolicited (a reminder, a scheduled job delivered to the voice channel). Show it; the spoken summary follows separately. |
| `{"type":"approval_request","approval_id","task_id","text","command","description","choices":["once","session","always","deny"]}` | A command Laura wants to run needs Hector's approval. Show a card with one button per choice (subset may be offered). Pending requests are re-sent after `hello`. They expire on the Hermes side (5 min by default → denied). |
| `{"type":"approval_closed","approval_id","choice"}` | The request was answered (`choice` = the choice applied, or `"expired"`). Remove the card. |

Client → server:

| Message | Meaning |
|---|---|
| `{"type":"approval_response","approval_id","choice"}` | Answer an approval. Must only be sent from an explicit tap on the card (never from speech or a voice command), with one of the offered choices. |
| `{"type":"task_cancel","task_id"}` | Cancel a queued task, or stop the running one. |

## Who is answering (v1, additive)

`assistant_delta`, `assistant_done` and `audio_start` carry an optional `source`, and so do `task`,
`task_progress` and `laura_message`:

| `source` | Meaning |
|---|---|
| `{"engine":"local","model":"bonsai-9b"}` | The gateway's fast local model (`LLM_LABEL`, default `LLM_MODEL` without `-voice`). Canned replies count as local. |
| `{"engine":"hermes","model":"claude-sonnet-5-5","lane":"task"}` | Laura's main model through Hermes. `model` is the model that turn ran on, as the plugin reports it (a session `/model` override or her default), else her default from the `persona` message, else `LAURA_MODEL`, else `""`. `lane`: `task` (a handed-off task and its spoken summary); `ask` is reserved for the deep-question lane. |

Clients show it as a small tag on the reply and while she speaks. Absent = unknown (older gateway).

## Wake (v1, additive)

A client that knows the next utterance is meant for Laura (an invocation: side key, assist gesture, shortcut,
floating bubble tap) sends `{"type":"wake","score":0.0-1.0}` with the first audio of that utterance.

The server applies a `wake` to the turn that is in progress or starts within 3 s of it, and treats that turn as
addressed (`why: "wake"`) without asking the classifier. Exception: when the transcript talks about LoRA ("…a new LoRA for that", "로라 어댑터") rather than calling her, the flag is dropped and the turn goes through the gate as usual (logged as `wake_jargon`). The owner check (voice profile) and mute still apply: while
muted only the resume phrase is acted on. Turns without a `wake` go through the addressee gate as before.
