# Third-party notices

The code in this repository is MIT-licensed (see `LICENSE`) except for the vendored files listed below. No model
weights, voice clips or datasets are committed; the services download or bake them in at build or start time, and
each comes with its own licence. If you redistribute built images or models, those licences apply to you.

## Vendored in this repository

| Component | Path | Licence |
|---|---|---|
| three.js r160 (core and the postprocessing/shader addons used by the avatar) | `clients/android/app/src/main/assets/avatar/three/` | MIT, notice in `three/LICENSE` |
| Gradle wrapper | `clients/android/gradle/wrapper/`, `gradlew`, `gradlew.bat` | Apache-2.0 |

## Models used at runtime (not included)

| Model | Used by | Licence | Notes |
|---|---|---|---|
| NVIDIA Nemotron 3.5 ASR Streaming 0.6B (`nvidia/nemotron-3.5-asr-streaming-0.6b`) | gateway ASR | OpenMDW-1.1 | Downloaded from Hugging Face on first start |
| NVIDIA TitaNet-small (`titanet_small`, NGC) | gateway speaker verification | Covered by the NeMo toolkit licence (Apache-2.0) per its NGC card | Trained on VoxCeleb, Fisher, Switchboard, LibriSpeech and others; check their terms for your use |
| Silero VAD | gateway turn detection | MIT | Shipped inside the `silero-vad` package |
| Ternary Bonsai 2 9B, PTQ1_0 GGUF (`prism-ml/Ternary-Bonsai-2-9B-gguf`) | local LLM | Apache-2.0 (follows its Qwen3.5-9B base) | Put the GGUF in `llm.modelsHostPath` |
| Supertonic | `tts_server.py` (GPU TTS option) | Code MIT; weights BigScience OpenRAIL-M | OpenRAIL-M has use-based restrictions (for example no impersonation without consent, no undisclosed machine-generated content) that must be passed on to anyone you give access to |
| Qwen3-TTS 12Hz 0.6B Base | `tts_qwen_server.py` (GPU TTS option); teacher for the small voice | Apache-2.0 | |
| Laura small voice (single-speaker VITS, ONNX) | `tts_small_server.py` | See its model card at huggingface.co/hectorandac/laura-tts-small | Trained by me on audio generated with Qwen3-TTS (Apache-2.0) from text generated with Qwen2.5-7B-Instruct, warm-started from the public Piper LJSpeech checkpoint; see `docs/tts-small.md` |

## Evaluation data (not included)

| Dataset | Used by | Licence |
|---|---|---|
| FLEURS (google/fleurs), ko_kr and en_us test | `server/tests/stt_bench.py`, `opus_cer.py` | CC BY 4.0 |
| Zeroth-Korean test, LibriSpeech test-clean | `server/tests/speaker_eval.py` | CC BY 4.0 |

## Runtime libraries (installed by the Dockerfiles, not vendored)

| Library | Licence | Notes |
|---|---|---|
| NVIDIA NeMo | Apache-2.0 | Pinned to a commit in `server/requirements.txt` |
| PrismML llama.cpp fork | MIT | Built from source in `deploy/docker/llama-server.Dockerfile` |
| faster-qwen3-tts | MIT | Wrapper only; the weights are Qwen3-TTS |
| g2pk2 | Apache-2.0 | Korean phonemes for the small voice |
| python-mecab-ko | BSD-3-Clause | |
| espeak-ng (`libespeak-ng1`) | GPL-3.0-or-later | Installed from Debian in the `tts-small` image and called through `ctypes` at runtime; nothing from it is in this repository. An image that contains it is subject to the GPL's source-offer terms for that library |
| ONNX Runtime, PyTorch, FastAPI, uvicorn, httpx, OkHttp, AndroidX/Compose | Their own permissive licences | |
| PyAV | BSD-3-Clause | Its wheels bundle FFmpeg libraries (LGPL) |

Piper's training code (GPL-3.0) was used only to train the small voice. None of it is in this repository or in
the runtime image.
