"""Speaker verification: is this utterance the enrolled owner's voice?

TitaNet (NeMo) speaker embeddings, cosine similarity against the mean of the enrollment embeddings.
The profile is a small .npz on the gateway (ASSISTANT_DATA), never in git.
"""
import time
from pathlib import Path

import numpy as np
import torch

SR = 16000
ENROLL_PROMPTS = [
    ("ko", "안녕 로라, 오늘 하루 일정 좀 같이 정리해 줄래요?"),
    ("en", "Hey Laura, what's a good recipe I can make with the things in my fridge?"),
    ("ko", "내일 아침 일곱 시에 일어나야 하니까 잊지 말고 알려줘요."),
    ("en", "I'm driving to Daegu station now, remind me to call my parents when I get there."),
    ("ko", "요즘 날씨가 많이 추워졌는데, 주말에 뭐 하면 좋을까요?"),
    ("en", "Read me a short summary of what we talked about this morning, please."),
]
ENROLL_MIN_S = 2.0      # shortest usable enrollment clip (speech seconds)


def _l2(x):
    return x / (np.linalg.norm(x, axis=-1, keepdims=True) + 1e-9)


class SpeakerModel:
    def __init__(self, name="titanet_small", device="cuda"):
        from nemo.collections.asr.models import EncDecSpeakerLabelModel
        self.device = torch.device(device)
        m = EncDecSpeakerLabelModel.from_pretrained(name, map_location="cpu").eval()
        self.model = m.to(self.device)
        self.name = name

    @torch.no_grad()
    def embed(self, audio: np.ndarray) -> np.ndarray:
        x = torch.from_numpy(np.ascontiguousarray(audio, np.float32)).unsqueeze(0).to(self.device)
        _, emb = self.model.forward(input_signal=x, input_signal_length=torch.tensor([x.shape[1]], device=self.device))
        return _l2(emb[0].float().cpu().numpy())


class VoiceProfile:
    """Enrollment embeddings and the calibrated decision threshold."""

    def __init__(self, embeddings, threshold, model_name, seconds=0.0, created=None):
        self.embeddings = _l2(np.asarray(embeddings, np.float32))
        self.centroid = _l2(self.embeddings.mean(0))
        self.threshold = float(threshold)
        self.model_name = model_name
        self.seconds = float(seconds)
        self.created = created or time.time()

    def score(self, emb):
        return float(self.centroid @ emb)

    def save(self, path):
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".tmp.npz")
        np.savez(tmp, embeddings=self.embeddings, threshold=self.threshold, model_name=self.model_name,
                 seconds=self.seconds, created=self.created)
        tmp.replace(path)

    @classmethod
    def load(cls, path):
        path = Path(path)
        if not path.exists():
            return None
        d = np.load(path, allow_pickle=False)
        return cls(d["embeddings"], float(d["threshold"]), str(d["model_name"]), float(d["seconds"]),
                   float(d["created"]))

