"""Qwen3-TTS voice-clone service on the GPU (faster-qwen3-tts: CUDA graphs, fp16, SDPA).

POST /synth        {"text","lang","voice","speed"} -> PCM16 mono, X-Sample-Rate header
POST /synth_stream same body -> chunked PCM16 as it is generated
Same contract as the Supertonic service; `steps` is accepted and ignored.

Voices are reference clips in TTS_VOICES_DIR: <name>.wav + <name>.txt (transcript). A clip recorded
faster, <name>@<speed>.wav (+ .txt), is used for that speed and up, so the model paces itself; any
remaining factor is applied with a pitch-preserving WSOLA time-stretch.
"""
import glob
import logging
import os
import re
import threading
import time

import numpy as np
import torch
from audiotsm import wsola
from audiotsm.io.array import ArrayReader, ArrayWriter
from fastapi import FastAPI
from fastapi.responses import Response, StreamingResponse
from faster_qwen3_tts import FasterQwen3TTS
from pydantic import BaseModel

MODEL = os.environ.get("TTS_MODEL", "Qwen/Qwen3-TTS-12Hz-0.6B-Base")
VOICES_DIR = os.environ.get("TTS_VOICES_DIR", "/app/voices")
DEFAULT_VOICE = os.environ.get("TTS_DEFAULT_VOICE", "laura")
SPEED_MULT = float(os.environ.get("TTS_SPEED_MULT", "1.2"))   # effective speed = requested speed x this
MAX_SEQ_LEN = int(os.environ.get("TTS_MAX_SEQ_LEN", "1024"))   # static KV cache (reference + sentence)
CHUNK = int(os.environ.get("TTS_CHUNK", "4"))              # codec steps per streamed chunk (12 = 1 s)
LANGS = {"en": "English", "ko": "Korean", "ja": "Japanese", "zh": "Chinese", "de": "German",
         "fr": "French", "es": "Spanish", "it": "Italian", "pt": "Portuguese", "ru": "Russian"}

log = logging.getLogger("tts")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")


def load_voices():
    """-> {name: [(speed, wav, transcript), ...]} sorted by speed."""
    voices = {}
    for wav in glob.glob(os.path.join(VOICES_DIR, "*.wav")):
        m = re.fullmatch(r"(.+?)(?:@([0-9.]+))?\.wav", os.path.basename(wav))
        with open(wav[:-4] + ".txt", encoding="utf-8") as f:
            voices.setdefault(m[1], []).append((float(m[2] or 1.0), wav, f.read().strip()))
    return {k: sorted(v) for k, v in voices.items()}


VOICES = load_voices()
# fp16: Turing (sm_75) has no bf16 tensor cores. TTS_DTYPE=bfloat16|float32 if fp16 overflows.
DTYPE = getattr(torch, os.environ.get("TTS_DTYPE", "float16" if torch.cuda.is_available() else "float32"))
tts = FasterQwen3TTS.from_pretrained(MODEL, device="cuda" if torch.cuda.is_available() else "cpu",
                                     dtype=DTYPE, attn_implementation="sdpa", max_seq_len=MAX_SEQ_LEN)
SR = 24000
gpu = threading.Lock()                                     # CUDA graphs: one generation at a time


def reference(voice, speed):
    """The fastest reference clip not faster than `speed`, and the time-stretch still needed."""
    refs = VOICES.get(voice) or VOICES[DEFAULT_VOICE]
    ref_speed, wav, text = max((r for r in refs if r[0] <= speed + 1e-3), default=refs[0])
    return wav, text, speed / ref_speed


class Stretch:
    """Streaming pitch-preserving time-stretch (WSOLA, 40 ms frames); a no-op at factor 1."""

    def __init__(self, factor):
        self.tsm = None if abs(factor - 1) < 1e-3 else wsola(1, speed=factor, frame_length=960, synthesis_hop=480)

    def feed(self, x):
        if self.tsm is None:
            return x
        r, w = ArrayReader(x[None, :]), ArrayWriter(1)
        while not r.empty:
            self.tsm.read_from(r)
            while True:
                n, done = self.tsm.write_to(w)
                if done or n == 0:
                    break
        return w.data[0]

    def end(self):
        if self.tsm is None:
            return np.zeros(0, np.float32)
        w, done = ArrayWriter(1), False
        while not done:
            _, done = self.tsm.flush_to(w)
        return w.data[0]


def pcm16(x):
    return (np.clip(np.asarray(x, np.float32).reshape(-1), -1, 1) * 32767).astype("<i2").tobytes()


def generate(text, lang, voice, speed):
    """Yields PCM16 chunks; logs time to first chunk and real-time factor."""
    wav, ref_text, factor = reference(voice, speed)
    st, t0, first, audio_s = Stretch(factor), time.time(), None, 0.0
    with gpu:
        for chunk, sr, _ in tts.generate_voice_clone_streaming(
                text=text, language=LANGS.get(lang, "English"), ref_audio=wav, ref_text=ref_text,
                chunk_size=CHUNK):
            out = st.feed(np.asarray(chunk, np.float32))
            if out.size:
                first = first or time.time() - t0
                audio_s += out.size / SR
                yield pcm16(out)
        tail = st.end()
        if tail.size:
            audio_s += tail.size / SR
            yield pcm16(tail)
    dt = time.time() - t0
    log.info("synth %s %.2fx %d chars: first %.0f ms, %.2f s audio in %.2f s (RTF %.2f)",
             lang, speed, len(text), (first or dt) * 1000, audio_s, dt, dt / max(audio_s, 1e-6))


def warm_up():
    for name, refs in VOICES.items():
        for ref_speed, _, _ in refs:
            for lang, text in (("en", "Okay, warming up."), ("ko", "네, 준비하고 있어요.")):
                for _ in generate(text, lang, name, ref_speed):
                    pass


warm_up()
app = FastAPI()


class Req(BaseModel):
    text: str
    lang: str = "en"
    voice: str = DEFAULT_VOICE
    speed: float | None = None
    steps: int | None = None                               # Supertonic's; ignored


def args(r: Req):
    return r.text, r.lang, r.voice, min(max((r.speed or 1.0) * SPEED_MULT, 0.5), 2.5)


@app.post("/synth")
def synth(r: Req):
    pcm = b"".join(generate(*args(r)))
    return Response(pcm, media_type="application/octet-stream", headers={"X-Sample-Rate": str(SR)})


@app.post("/synth_stream")
def synth_stream(r: Req):
    return StreamingResponse(generate(*args(r)), media_type="application/octet-stream",
                             headers={"X-Sample-Rate": str(SR)})


@app.get("/health")
def health():
    stats = {"voices": {k: [s for s, _, _ in v] for k, v in VOICES.items()}, "speed_mult": SPEED_MULT}
    if torch.cuda.is_available():
        stats["max_allocated_mb"] = round(torch.cuda.max_memory_allocated() / 2**20)
        stats["max_reserved_mb"] = round(torch.cuda.max_memory_reserved() / 2**20)
    return stats
