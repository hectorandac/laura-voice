"""Small Laura voice on the CPU: single-speaker VITS (Piper layout, ONNX), distilled from the Qwen3-TTS clone.

POST /synth        {"text","lang","voice","speed"} -> PCM16 mono, X-Sample-Rate header
POST /synth_stream same body -> chunked PCM16 as it is decoded
Same contract as the other TTS services; `voice` and `steps` are accepted and ignored.

The model is split into an encoder (text -> latent frames, run once per sentence) and a decoder
(latent -> audio). Streaming decodes the latent in chunks that overlap by CONTEXT frames on each side, more
than the decoder's receptive field, so the joined audio matches a whole-sentence decode. Speed is the
model's own length scale (no time-stretch).
"""
import json
import logging
import os
import re
import threading
import time

import numpy as np
import onnxruntime as ort
from fastapi import FastAPI
from fastapi.responses import Response, StreamingResponse
from pydantic import BaseModel

from laura_g2p import phonemize, to_ids

MODEL_DIR = os.environ.get("TTS_MODEL_DIR", "/app/model")
SPEED_MULT = float(os.environ.get("TTS_SPEED_MULT", "1.2"))   # effective speed = requested speed x this
THREADS = int(os.environ.get("TTS_THREADS", "4"))
DEVICE = os.environ.get("TTS_DEVICE", "cpu")                  # cpu | cuda
FIRST = int(os.environ.get("TTS_FIRST_FRAMES", "32"))         # latent frames in the first chunk (~0.37 s)
CHUNK = int(os.environ.get("TTS_CHUNK_FRAMES", "128"))        # later chunks
GAIN = float(os.environ.get("TTS_GAIN", "1.33"))              # ~+2.5 dB: matches the Qwen clone's loudness
CONTEXT = 12                                                  # overlap on each side, in latent frames
HOP = 256

log = logging.getLogger("tts")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

with open(os.path.join(MODEL_DIR, "config.json"), encoding="utf-8") as f:
    CONFIG = json.load(f)
SR = CONFIG["audio"]["sample_rate"]
ID_MAP = CONFIG["phoneme_id_map"]
NOISE = float(os.environ.get("TTS_NOISE", CONFIG["inference"].get("noise_scale", 0.667)))   # voice variation
NOISE_W = float(os.environ.get("TTS_NOISE_W", CONFIG["inference"].get("noise_w", 0.8)))   # rhythm variation

opts = ort.SessionOptions()
opts.intra_op_num_threads = THREADS
opts.inter_op_num_threads = 1
providers = ["CUDAExecutionProvider", "CPUExecutionProvider"] if DEVICE == "cuda" else ["CPUExecutionProvider"]
encoder = ort.InferenceSession(os.path.join(MODEL_DIR, "model.encoder.onnx"), opts, providers=providers)
decoder = ort.InferenceSession(os.path.join(MODEL_DIR, "model.decoder.onnx"), opts, providers=providers)
LATENT = decoder.get_inputs()[0].name
lock = threading.Lock()                                        # one sentence at a time; the CPU is shared

SENTENCE = re.compile(r"(?<=[.!?。])\s+")


def latent(text, lang, speed):
    ids = to_ids(phonemize(text, lang), ID_MAP)
    feed = {"input": np.array([ids], np.int64), "input_lengths": np.array([len(ids)], np.int64),
            "scales": np.array([NOISE, 1.0 / speed, NOISE_W], np.float32)}
    return encoder.run(None, feed)[0]


def decode(z, start, end):
    a, b = max(start - CONTEXT, 0), min(end + CONTEXT, z.shape[2])
    audio = decoder.run(None, {LATENT: z[:, :, a:b]})[0].reshape(-1)
    return audio[(start - a) * HOP:(start - a + end - start) * HOP]


def pcm16(x):
    return (np.clip(x * GAIN, -1, 1) * 32767).astype("<i2").tobytes()


def generate(text, lang, speed):
    """Yields PCM16 chunks; logs time to first chunk and real-time factor."""
    t0, first, audio_s = time.time(), None, 0.0
    with lock:
        waited = time.time() - t0
        for sentence in SENTENCE.split(text.strip()) or [text]:
            if not sentence.strip():
                continue
            z = latent(sentence, lang, speed)
            n, pos, size = z.shape[2], 0, FIRST
            while pos < n:
                end = n if n - pos - size < CONTEXT else pos + size
                out = decode(z, pos, end)
                pos, size = end, CHUNK
                first = first or time.time() - t0
                audio_s += out.size / SR
                yield pcm16(out)
    dt = time.time() - t0
    log.info("synth %s %.2fx %d chars: first %.0f ms (queued %.0f ms), %.2f s audio in %.2f s (RTF %.3f)",
             lang, speed, len(text), (first or dt) * 1000, waited * 1000, audio_s, dt, dt / max(audio_s, 1e-6))


for _text, _lang in (("Okay, warming up.", "en"), ("네, 준비하고 있어요.", "ko")):
    for _ in generate(_text, _lang, SPEED_MULT):
        pass
log.info("ready: %s, %d threads, %d Hz", DEVICE, THREADS, SR)
app = FastAPI()


class Req(BaseModel):
    text: str
    lang: str = "en"
    voice: str | None = None
    speed: float | None = None
    steps: int | None = None                                   # Supertonic's; ignored


def args(r: Req):
    return r.text, r.lang, min(max((r.speed or 1.0) * SPEED_MULT, 0.5), 2.5)


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
    return {"voices": {"laura": [1.0]}, "speed_mult": SPEED_MULT, "device": DEVICE, "threads": THREADS}
