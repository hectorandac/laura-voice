# Hermes plugin: laura_voice

A Hermes Agent platform plugin that makes the voice gateway one of the agent's channels. Copy `laura_voice/`
to `$HERMES_HOME/plugins/laura-voice/`, add `laura-voice` to `plugins.enabled`, and set `LAURA_VOICE_URL` and
`LAURA_VOICE_TOKEN`. Design, link protocol and configuration: [docs/hermes.md](../docs/hermes.md).

`link.py` has no Hermes imports and is covered by `tests/` (`pytest` from the repo root). Tested against
Hermes Agent v0.22.5.
