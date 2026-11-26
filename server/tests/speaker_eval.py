"""Speaker-verification calibration: EER and thresholds for the owner gate.

Real voices: Zeroth Korean test (10 speakers) and LibriSpeech test-clean (English, first N speakers), parquet
files from the Hugging Face hub in $SPK_EVAL. Synthetic voices: the 10 Supertonic TTS voices in Korean and
English (needs tts_server on 127.0.0.1:8091).
Each speaker is enrolled with ~30 s of clean speech; the rest are test trials, scored as clean, cropped to
1.0/1.5 s, and "far-field" (synthetic room reverb + noise at 10 dB SNR, -35 dBFS).

python tests/speaker_eval.py [--models titanet_small,...] [--device cpu|cuda] [--out results.json]
"""
import argparse
import io
import json
import os
import sys
import time
import urllib.request
from collections import defaultdict
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
SR = 16000
EVAL = Path(os.environ.get("SPK_EVAL", Path.home() / "llm/voice/spk_eval"))
TTS = os.environ.get("TTS_URL", "http://127.0.0.1:8091/synth")
TTS_LINES = {
    "ko": ["오늘 저녁에 뭐 먹을지 아직 못 정했어요.", "내일 회의가 몇 시였는지 기억나요?",
           "주말에 부모님 댁에 가려고 하는데 길이 막힐까요?", "이번 달 전기 요금이 생각보다 많이 나왔네요.",
           "운동을 다시 시작하려면 어떤 걸 먼저 하면 좋을까요?", "커피를 너무 많이 마셔서 잠이 안 와요.",
           "다음 주에 서울 출장이 잡혀서 기차표를 예매해야 해요.", "고양이가 아침부터 계속 울어서 걱정이에요."],
    "en": ["I still haven't decided what to cook for dinner tonight.", "Do you remember what time the meeting is tomorrow?",
           "I'm planning to visit my parents this weekend, will traffic be bad?", "The electricity bill came in higher than I expected.",
           "If I want to start working out again, what should I do first?", "I drank too much coffee and now I can't sleep.",
           "I have a business trip to Seoul next week, so I need train tickets.", "The cat has been meowing since early this morning."],
}


def decode(b):
    import soundfile as sf
    x, sr = sf.read(io.BytesIO(b), dtype="float32")
    if x.ndim > 1:
        x = x.mean(1)
    if sr != SR:
        import librosa
        x = librosa.resample(x, orig_sr=sr, target_sr=SR)
    return x


def parquet_speakers(path, max_speakers, per_speaker):
    import pyarrow.parquet as pq
    rows = pq.read_table(path, columns=["speaker_id", "audio"]).to_pylist()
    by = defaultdict(list)
    for r in rows:
        by[r["speaker_id"]].append(r["audio"]["bytes"])
    out = {}
    for spk in sorted(by)[:max_speakers]:
        out[str(spk)] = [decode(b) for b in by[spk][:per_speaker]]
    return out


def tts_speakers():
    out = {}
    for v in [f"F{i}" for i in range(1, 6)] + [f"M{i}" for i in range(1, 6)]:
        clips = []
        for lang in ("ko", "en"):
            for line in TTS_LINES[lang]:
                req = urllib.request.Request(TTS, json.dumps({"text": line, "lang": lang, "voice": v}).encode(),
                                             {"Content-Type": "application/json"})
                r = urllib.request.urlopen(req, timeout=60)
                x = np.frombuffer(r.read(), "<i2").astype(np.float32) / 32768
                import librosa
                clips.append(librosa.resample(x, orig_sr=int(r.headers["X-Sample-Rate"]), target_sr=SR))
        out["tts_" + v] = clips[0::2] + clips[1::2]   # interleave ko/en so enrollment gets both
    return out


def far_field(x, rng):
    rt60 = rng.uniform(0.35, 0.7)
    n = int(SR * rt60)
    t = np.arange(n) / SR
    h = rng.standard_normal(n) * np.exp(-6.9 * t / rt60) * 0.25
    h[0] = 1.0
    y = np.convolve(x, h)[: x.size]
    noise = rng.standard_normal(y.size)
    noise = np.convolve(noise, np.ones(8) / 8, "same")
    p_s, p_n = np.mean(y ** 2), np.mean(noise ** 2)
    y = y + noise * np.sqrt(p_s / (p_n * 10 ** (10 / 10)))
    y *= 10 ** (-35 / 20) / (np.sqrt(np.mean(y ** 2)) + 1e-9)
    return y.astype(np.float32)


def crop(x, s, rng):
    n = int(s * SR)
    if x.size <= n:
        return x
    i = rng.integers(0, x.size - n)
    return x[i:i + n]


def eer(tgt, imp):
    tgt, imp = np.asarray(tgt), np.asarray(imp)
    ths = np.sort(np.concatenate([tgt, imp]))
    frr = np.array([(tgt < t).mean() for t in ths])
    far = np.array([(imp >= t).mean() for t in ths])
    i = np.argmin(np.abs(frr - far))
    return float((frr[i] + far[i]) / 2), float(ths[i])


def rates(tgt, imp, th):
    return float((np.asarray(tgt) < th).mean()), float((np.asarray(imp) >= th).mean())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", default="titanet_small")
    ap.add_argument("--device", default="cpu")
    ap.add_argument("--libri", type=int, default=20)
    ap.add_argument("--out", default=str(EVAL / "results.json"))
    a = ap.parse_args()
    if a.device == "cpu":
        os.environ["CUDA_VISIBLE_DEVICES"] = ""
    import torch
    torch.set_num_threads(int(os.environ.get("THREADS", "6")))
    from speaker import SpeakerModel, VoiceProfile

    rng = np.random.default_rng(0)
    t0 = time.time()
    groups = {"zeroth_ko": parquet_speakers(EVAL / "zeroth_test.parquet", 10, 40),
              "libri_en": parquet_speakers(EVAL / "libri_test_clean.parquet", a.libri, 25)}
    try:
        groups["tts"] = tts_speakers()
    except Exception as e:  # noqa: BLE001
        print("TTS voices skipped:", e)
    print(f"data loaded in {time.time() - t0:.0f}s:", {g: (len(s), sum(len(c) for c in s.values())) for g, s in groups.items()},
          flush=True)

    results = {}
    for name in a.models.split(","):
        m = SpeakerModel(name, a.device)
        emb_t = []
        def E(x):
            t = time.time()
            e = m.embed(x)
            emb_t.append((x.size / SR, time.time() - t))
            return e
        conds = ("clean", "1.5s", "1.0s", "far", "far_1.5s")
        scores = {c: {"tgt": [], "imp": []} for c in conds}
        per_group = {}
        profiles, tests = {}, {}
        for g, spk in groups.items():
            for s, clips in spk.items():
                enr, secs = [], 0.0
                for i, c in enumerate(clips):
                    if secs >= 30 or len(clips) - i <= 4:
                        break
                    enr.append(c)
                    secs += c.size / SR
                profiles[(g, s)] = VoiceProfile([E(c) for c in enr], 0.5, name, secs)
                test = clips[len(enr):]
                tests[(g, s)] = {"clean": [E(c) for c in test], "1.5s": [E(crop(c, 1.5, rng)) for c in test],
                                 "1.0s": [E(crop(c, 1.0, rng)) for c in test],
                                 "far": [E(far_field(c, rng)) for c in test],
                                 "far_1.5s": [E(crop(far_field(c, rng), 1.5, rng)) for c in test]}
        for key, p in profiles.items():
            for c in conds:
                for k2, t in tests.items():
                    sc = [p.score(e) for e in t[c]]
                    (scores[c]["tgt"] if k2 == key else scores[c]["imp"]).extend(sc)
                    if k2 == key:
                        per_group.setdefault((key[0], c), {"tgt": [], "imp": []})["tgt"].extend(sc)
                    elif k2[0] == key[0]:
                        per_group.setdefault((key[0], c), {"tgt": [], "imp": []})["imp"].extend(sc)
        r = {"embed_ms_per_clip_mean": round(1000 * np.mean([t for _, t in emb_t]), 1),
             "clip_s_mean": round(np.mean([d for d, _ in emb_t]), 2), "conditions": {}, "per_group": {}}
        for c in conds:
            e, th = eer(scores[c]["tgt"], scores[c]["imp"])
            r["conditions"][c] = {"eer": round(e, 4), "eer_threshold": round(th, 3),
                                  "n_tgt": len(scores[c]["tgt"]), "n_imp": len(scores[c]["imp"])}
        for (g, c), d in sorted(per_group.items()):
            e, th = eer(d["tgt"], d["imp"])
            r["per_group"][f"{g}/{c}"] = {"eer": round(e, 4), "eer_threshold": round(th, 3)}
        for th in np.arange(0.3, 0.75, 0.05):
            row = {}
            for c in conds:
                frr, far = rates(scores[c]["tgt"], scores[c]["imp"], th)
                row[c] = f"FRR {frr:.3f} FAR {far:.4f}"
            r.setdefault("operating_points", {})[f"{th:.2f}"] = row
        results[name] = r
        print(name, json.dumps(r, indent=1), flush=True)
        del m
    Path(a.out).write_text(json.dumps(results, indent=1))


if __name__ == "__main__":
    main()
