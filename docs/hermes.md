# Hermes integration: Laura in the middle

Goal: one Laura. Her persona, memory, skills, tools and approvals live in the Hermes gateway. The voice gateway
is just another channel, like Matrix or ntfy, but one that has to answer in under a second.

## Why two lanes

Laura's normal turns are large. On the reference install, each model call carries about 28K tokens (a 26K-character
system prompt plus tool schemas), and a turn on the frontier model takes about 25 s at the median. Hermes can
route voice turns to a fast model (`auxiliary.voice_chat`), but it keeps the full system prompt and toolset,
and falls back to the main model whenever the request doesn't fit the fast model's window. A 9B model with a 16K
window on an 8 GB GPU shared with ASR and TTS doesn't fit, and processing 28K tokens cold would take tens of
seconds anyway.

So the work is split:

| Lane | Runs where | Model | Latency | Has |
|---|---|---|---|---|
| Live conversation | voice gateway | local Bonsai 2 9B | ~1 s to first audio | Laura's persona and memory files (pushed by the plugin), the recent spoken exchange, one tool: `ask_laura` |
| Real work | Hermes, `laura_voice` platform session | Laura's main model | seconds to minutes | everything: tools, skills, memory writes, approvals, cron, delegation |

## Pieces

**`hermes/laura_voice/`**: a Hermes platform plugin (`kind: platform`, `BasePlatformAdapter`). It needs no
change to Hermes core. It:

- dials the gateway's `/laura` WebSocket with a bearer token and reconnects with backoff, since the GPU node
  sleeps and travels.
- pushes `SOUL.md`, `memories/USER.md` and `memories/MEMORY.md` on connect and whenever they change (30 s poll).
- turns each handed-off task into a `MessageEvent` in one session (`chat_id` `voice`), so Laura has continuity
  across spoken tasks. It runs tasks one at a time: Hermes' default busy mode would interrupt the running task,
  and that setting is global, so the plugin queues and dispatches the next task from `on_processing_complete`.
- forwards everything Hermes sends in that session. Messages before the turn ends are progress; the last one is
  the result. Messages outside a task (cron `deliver: laura_voice`, reminders) are forwarded as unsolicited.
- renders exec approvals natively (`_send_exec_approval_prompt`) and resolves taps with
  `tools.approval.resolve_gateway_approval(session_key, choice)`.
- keeps up to 50 outgoing messages for 24 h while the gateway is unreachable. Approval prompts are not kept:
  Hermes times them out (deny) after `approvals.timeout`.

**`server/laura_link.py`**: the gateway side. It serves `/laura`, builds the fast lane's system prompt from the
persona, adds `ask_laura` to the fast model's request, turns a tool call into a spoken acknowledgment plus a
`task`, relays task, progress, result and approval messages to the client
([protocol](protocol.md#lauras-tasks-and-approvals-v1-additive)), and speaks finished results at the next quiet
moment as a one-to-three sentence summary written by the fast model. Approvals are accepted only from the
`approval_response` control message, which clients send only on a tap.

## Link protocol (gateway ⇄ plugin)

`GET /laura` (WebSocket), header `Authorization: Bearer <LAURA_LINK_TOKEN>`. JSON text frames.

| Direction | Message |
|---|---|
| plugin → gateway | `{"type":"persona","soul","user","memory","model"?}` on connect and on change (`model`: her default model from `config.yaml`) |
| plugin → gateway | `{"type":"task_update","task_id","state":"queued"\|"running"\|"done"\|"failed"\|"cancelled","model"?}` |
| plugin → gateway | `{"type":"message","task_id"\|null,"text","final","model"?}` (`model`: what the turn ran on, a session `/model` override or the default; the phone shows it as who answered) |
| plugin → gateway | `{"type":"approval_request","approval_id","task_id","text","command","description","choices"}` / `{"type":"approval_closed","approval_id","choice"}` |
| gateway → plugin | `{"type":"task","task_id","text","context":[{role,content}...],"lang"}` |
| gateway → plugin | `{"type":"approval_response","approval_id","choice"}` / `{"type":"cancel","task_id"}` |

A second plugin connection replaces the first (close code 4000). A bad token gets close code 4401.

## Installing the plugin

Hermes loads user platform plugins from `$HERMES_HOME/plugins/<name>/`, and only those listed in
`plugins.enabled`. The reference Helm chart for Hermes ships the plugin files in its `files/plugins/laura-voice/`,
puts them in its ConfigMap, and copies them onto the data volume in its init container on every start.

```yaml
tools:
  localPlugins: [laura-voice]
config:
  plugins:
    enabled: [laura-voice]
env:
  LAURA_VOICE_URL: ws://voice-gateway.voice.svc.cluster.local:8080/laura
  LAURA_VOICE_USER: hector
  LAURA_VOICE_ALLOWED_USERS: hector
# secret: LAURA_VOICE_TOKEN (same value as LAURA_LINK_TOKEN in the voice namespace)
```

The local model is also registered as a named provider, so Laura or her owner can pick it explicitly
(`/model bonsai-9b-voice --provider laura-voice-local`). It is deliberately not her auxiliary or voice-chat
route, for the reasons above, and because each call would evict the live conversation's prompt cache on a
single-slot server.

```yaml
config:
  providers:
    laura-voice-local:
      name: Laura voice (local Bonsai 9B)
      url: http://voice-llm.voice.svc.cluster.local:8090/v1
      api_key: local-no-auth
      default_model: bonsai-9b-voice
      models: {bonsai-9b-voice: {context_length: 16384}}
```

## Limits

- One task at a time. A second request waits in the queue; `task_cancel` removes it or stops the running one.
- The fast model decides when to hand off. A 9B model sometimes answers when it should have handed off; the
  persona prompt tells it never to invent results.
- Each handed-off task spends the main model's quota like any other Laura turn.
