# Voice assistant backend (GPU node)

Developed on a laptop with an RTX 2080 Super Max-Q (8 GB). In production the three services run as the Helm chart in
`deploy/helm/laura-voice`; for development they also run as plain processes:

| Process | Port | What |
|---|---|---|
| `llama-server --models-preset models.ini --models-max 1` | 127.0.0.1:8090 | Bonsai 2 9B, preset `bonsai-9b-voice` (16K ctx) |
| `uvicorn tts_server:app` (or `tts_small_server:app`, `tts_qwen_server:app`) | 127.0.0.1:8091 | TTS, POST /synth |
| `uvicorn assistant_server:app --ssl-*` | 0.0.0.0:8443 | client + /ws |

`assistant_server.py`: Silero VAD turns -> Nemotron 3.5 streaming ASR (`stt/`, level control, attention context [56,13],
loaded via CPU to avoid a 4.3 GB GPU spike) + speaker verification (`speaker.py`, TitaNet-small) -> addressee gate
(`addressee.py`) -> LLM streamed -> sentence-by-sentence TTS. `ASSISTANT_KEY` (from `.env`) is required as `?k=` on
/ws. The voice profile lives in `$ASSISTANT_DATA`
(default `~/.local/share/laura-voice/voice_profile.npz`), never in the repo.

Environment: `ASSISTANT_NAME` (Laura), `ASSISTANT_USER`, `ASSISTANT_DATA`, `SPEAKER_MODEL` (titanet_small),
`SPEAKER_DEVICE` (cuda), `SPEAKER_THRESHOLD`, `LLM_URL`, `LLM_MODEL`, `TTS_URL`, `TTS_VOICE`.

## Streaming ASR (`stt/`)
`stt/nemotron.py` drives the model through NeMo's documented cache-aware streaming API (`conformer_stream_step` with
the encoder cache and the previous hypotheses). `MelFeed` computes log-mel frames as audio arrives, recomputing a
few frames of left context per call, so streamed frames equal one pass over the whole utterance (max abs diff
1.9e-6); an `Utterance` gives the encoder only whole chunks (105 then 112 frames, plus 9 frames of pre-encode
context at [56,13]) until it is closed. The text is therefore the same whether audio arrives in 100 ms blocks,
odd-sized blocks or all at once, and the same as NeMo's own `CacheAwareStreamingAudioBuffer`. fp32,
`strip_lang_tags`, greedy RNN-T without CUDA graphs; the language prompt lives on the shared model, so encoder steps
take a lock and set the prompt of the utterance they belong to. `stt/leveler.py` is the input level control for
far-field microphones (gain moves only on blocks well above the background estimate).

Benchmark: the first 100 utterances of the FLEURS test sets for Korean (`ko_kr`) and English (`en_us`)
([google/fleurs](https://huggingface.co/datasets/google/fleurs), CC BY 4.0), streamed in 100 ms blocks through the
level control with the language prompt set, scored against FLEURS' normalised transcription without number
normalisation (Korean: CER with spaces and punctuation removed; English: WER). CPU fp32, 2 threads of an i5-13400:

| Set | Audio | Error | RTF | Wait after the last block (mean / p90) |
|---|---|---|---|---|
| ko_kr test, first 100 | 20.9 min | CER 0.076 | 0.29 | 245 / 282 ms |
| en_us test, first 100 | 15.9 min | WER 0.097 | 0.30 | 256 / 308 ms |

```sh
# FLEURS layout: $FLEURS_DIR/{ko_kr,en_us}_test.tsv and {ko_kr,en_us}_audio_test/test/*.wav, from
# https://huggingface.co/datasets/google/fleurs/resolve/main/data/<lang>/{test.tsv,audio/test.tar.gz}
python tests/stt_bench.py --fleurs $FLEURS_DIR --n 100 --threads 2 --device cpu --check --out hyps.jsonl
```

`--check` also prints the feature and block-size equivalence checks above. The live gateway runs the same engine on
the GPU (`ASR_DEVICE=cuda`).

## Who is talking, and to whom
Hands-free turns are answered only if (1) the voice matches the enrolled profile (when one exists) and (2) the
utterance is addressed to Laura: her name (로라, 라우라, "Lora,"... fuzzy, vocative-only for ambiguous spellings),
a follow-up within 20 s after the last answered turn, reply or connect, a bare greeting, or a one-token yes/no from the 9B (threshold 0.6, 0.4 when
`context.car` and nobody else was heard). A non-owner voice in the last 2 minutes means "others present": only
the name counts then (inside the window the classifier must also agree). "Laura, stop listening" / "로라 그만
들어" mutes until "start listening" / "다시 들어". With a profile, barge-in during a reply is verified on 0.8 s of
speech first. Ignored turns produce `ignored` events (see docs/protocol.md).
Tests: `tests/speaker_eval.py`, `tests/addressee_eval.py` (+ `addressee_set.jsonl`), `tests/gate_ws_test.py`.

GPU: ~6.0 GB idle with all three loaded, ~6.3 GB peak. End of speech -> first reply audio 0.8-1.2 s.
Tests: `tests/ws_test.py` (WS_URL=wss://...?k=...), `tests/browser_test.js` (Playwright, fake mic).

## Audio over the network
The client announces `audio: {up, down}` in `hello`. With WebCodecs it sends and receives Opus (one packet per
WebSocket message; server decodes with PyAV `opus`, encodes replies with `libopus` at 24 kHz / 24 kbps).
Otherwise PCM16 16 kHz, replies cut into 100 ms pieces. In hands-free mode the client only streams while its
speech gate is open (adaptive noise floor, 300 ms lead-in, 1.2 s hang-over), so silence costs nothing.
Measured: ~23 kbps up while speaking, 0 when quiet, ~0.1 MB per question and spoken reply.
`?codec=pcm` and `?gate=off` in the page URL force the fallbacks. `tests/opus_cer.py` checks ASR accuracy
through the codec on Korean FLEURS test audio.

## Measured (laptop, 2026-11-25)
- Speaker verification, TitaNet-small, enrollment ~30 s clean, cosine to the centroid. Pooled over Zeroth-Korean
  (10 spk), LibriSpeech test-clean (20 spk), 10 Supertonic voices: EER 0.6 % clean, 3.5 % on 1.5 s crops, 6.3 % on
  1.0 s, 4.3 % simulated far-field, 10 % far-field 1.5 s. At the default threshold 0.40: FRR 0.2 % / FAR 2.5 %
  clean, FRR 4 % / FAR 1.1 % at 1.5 s, FRR 34 % far-field (enroll on the device you use). TitaNet-large was no better (EER 1.0 % clean, 6.5 % far-field) at 3x the cost.
  ~35-55 ms per utterance on 6 CPU threads; on the GPU the gateway total grew by < 100 MB.
- Addressee gate, `tests/addressee_set.jsonl` (72 items, ko/en): outside the window at 0.6 precision 0.82 /
  recall 0.97; inside the window at 0.25 precision 0.80 / recall 0.97. Classifier ~90-130 ms warm.
- Live (42 answered turns): end of turn -> first audio median 1.12 s, p90 1.5 s (LLM first clause 0.58 s, TTS
  0.42 s); the old gateway's last 8 turns had a median of 0.96 s.
