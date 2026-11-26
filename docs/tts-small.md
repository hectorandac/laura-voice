# Small CPU voice: distilling the Qwen3-TTS clone into a 16M-parameter VITS

Laura's voice started as a zero-shot clone in Qwen3-TTS 0.6B. It sounded right but cost about 1 GB of VRAM and ran
the GPU at ~95 % while speaking, on an 8 GB card already shared with ASR, speaker verification and the 9B LLM.
Under that contention its first audio grew to 2-2.5 s. The small voice moves TTS off the GPU entirely.

Runtime: `server/tts_small_server.py` (ONNX Runtime, CPU), `server/laura_g2p.py` (phonemiser), image
`deploy/docker/tts-small.Dockerfile`, chart overlay `deploy/helm/laura-voice/values-tts-small.yaml`
(`tts.gpu: false`). It serves the same `/synth` and `/synth_stream` API as the other TTS services.

## How it was built

| Step | What |
|---|---|
| Text | 7,979 assistant-style lines, about half Korean and half English, written by Qwen2.5-7B-Instruct over a topic list and normalised with the gateway's own `speakable()` |
| Audio | Each line voiced by the Qwen3-TTS 0.6B clone with Laura's reference clip (a stock Qwen3-TTS voice, not a recording of a person) |
| Filtering | Whisper-turbo transcript error and CAM++ similarity to the reference per clip: 6,293 clips kept (4.10 h English, 3.26 h Korean) |
| Model | Single-speaker VITS in the Piper "medium" layout, warm-started from the public LJSpeech checkpoint, 150 epochs at batch 16 on one GPU |
| Phonemes | Korean via g2pk2, spelled in IPA from jamo and mapped onto the shared symbol table (two new symbols: tense and unreleased consonants); English via the system espeak-ng library. Training phonemes were produced inside the runtime image, so they match what it uses live |
| Export | ONNX, split into encoder and decoder so audio streams out in decoder chunks; chunked output matches a whole-sentence decode to 3e-7 |

The weights are published separately at [huggingface.co/hectorandac/laura-tts-small](https://huggingface.co/hectorandac/laura-tts-small).
The training scripts and the generated corpus are not in this repository: training used Piper's GPL-3.0 training
code, and the runtime contains none of it (ONNX Runtime plus the phonemiser here). No weights are committed to git.

## Results

8 fixed test lines (4 English, 4 Korean), scored with Whisper-turbo (WER/CER) and CAM++ (speaker similarity);
the small model's numbers average 3 samples per line, the baselines 1.

| Voice | Params | Runs on | WER en | CER ko | Similarity to reference clip |
|---|---|---|---|---|---|
| Qwen3-TTS 0.6B clone (teacher) | 0.6B | GPU, ~1 GB | 0.135 | 0.088 | 0.769 |
| Supertonic (previous default) | – | GPU | 0.166 | 0.080 | 0.400 |
| **Small VITS, epoch 149** | **15.7M** (encoder 14.0M + decoder 1.7M, counted from the ONNX initializers) | **CPU, 4 threads** | 0.171 | 0.104 | 0.806 |

Speed on a laptop CPU (i9-10980HK, `docker --cpus 4`), same 8 lines: RTF 0.056 at speed 1.0 and 0.058 at 1.2
(range 0.048-0.069), first audio 72 ms on average at 1.2x. A re-run on a busier desktop i5-13400 with 4 CPUs gave
RTF 0.07-0.16. Single-sample WER/CER swing by about ±0.05, so the table is a sanity check, not a benchmark: the
voice keeps the teacher's identity (similarity is even slightly higher than the teacher's own) at roughly the
teacher's intelligibility, and most remaining errors are numbers written as digits by the scoring ASR.
