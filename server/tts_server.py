"""Minimal Supertonic TTS service on the GPU: POST /synth {"text","lang"} -> PCM16 mono (X-Sample-Rate header)."""
import onnxruntime as ort
ort.preload_dlls()
import supertonic.loader as L
L.DEFAULT_ONNX_PROVIDERS = ['CUDAExecutionProvider', 'CPUExecutionProvider']
import numpy as np
from fastapi import FastAPI
from fastapi.responses import Response
from pydantic import BaseModel
from supertonic import TTS

tts = TTS()
styles = {}


def style(name):
    if name not in styles:
        styles[name] = tts.get_voice_style(name if name in tts.voice_style_names else 'F1')
    return styles[name]


app = FastAPI()

class Req(BaseModel):
    text: str
    lang: str = 'en'
    steps: int = 12
    voice: str = 'F1'
    speed: float = 1.0

@app.post('/synth')
def synth(r: Req):
    wav, _ = tts.synthesize(r.text, voice_style=style(r.voice), lang=r.lang, total_steps=r.steps, speed=r.speed)
    pcm = (np.clip(np.asarray(wav).squeeze(), -1, 1) * 32767).astype('<i2').tobytes()
    return Response(pcm, media_type='application/octet-stream', headers={'X-Sample-Rate': str(tts.sample_rate)})
