"""Korean FLEURS test audio through the phone's uplink codec (Opus at 16 kHz, 100 ms frames) vs raw PCM, on the CPU.
FLEURS_DIR=... python tests/opus_cer.py [bitrate ...]   (first 50 ko_kr test utterances, see stt_bench.py)"""
import os
import sys
import time
from pathlib import Path

os.environ["CUDA_VISIBLE_DEVICES"] = ""
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import av  # noqa: E402
import numpy as np  # noqa: E402
import torch  # noqa: E402

from stt import load_recognizer  # noqa: E402
from stt_bench import cer, load_wav, rows, run  # noqa: E402

torch.set_num_threads(int(os.environ.get("THREADS", "2")))


def through_opus(audio, bitrate):
    enc = av.CodecContext.create("libopus", "w")
    enc.sample_rate, enc.layout, enc.format, enc.bit_rate = 16000, "mono", "s16", bitrate
    dec = av.CodecContext.create("opus", "r")
    dec.sample_rate, dec.layout = 48000, "mono"
    rs = av.AudioResampler(format="s16", layout="mono", rate=16000)
    x = (np.clip(audio, -1, 1) * 32767).astype(np.int16)
    out, nbytes = [], 0
    for i in range(0, x.size, 1600):
        f = av.AudioFrame.from_ndarray(x[i:i + 1600].reshape(1, -1), format="s16", layout="mono")
        f.sample_rate = 16000
        for p in enc.encode(f):
            nbytes += p.size
            for fr in dec.decode(av.Packet(bytes(p))):
                out += [g.to_ndarray().reshape(-1) for g in rs.resample(fr)]
    return np.concatenate(out).astype(np.float32) / 32768, nbytes * 8 / (audio.size / 16000) / 1000


ROOT = os.environ.get("FLEURS_DIR", "fleurs")
items = [(load_wav(ROOT, "ko_kr", f), ref) for f, ref in rows(ROOT, "ko_kr", 50)]
refs = [r for _, r in items]
rec = load_recognizer("cpu")


def score(transform):
    return cer(refs, [run(rec, transform(x), "ko", True)[0] for x, _ in items])


for br in [int(b) for b in sys.argv[1:]] or [24000]:
    t = time.time()
    kbps = np.mean([through_opus(x, br)[1] for x, _ in items])
    print(f"opus {br // 1000} kbps ({kbps:.0f} kbps actual): CER {score(lambda x: through_opus(x, br)[0]):.4f} "
          f"({time.time() - t:.0f} s)", flush=True)
t = time.time()
print(f"raw pcm: CER {score(lambda x: x):.4f} ({time.time() - t:.0f} s)", flush=True)
