"""Streaming ASR accuracy and speed on FLEURS test audio (Google, CC BY 4.0).

python tests/stt_bench.py --fleurs DIR [--sets ko_kr,en_us] [--n 100] [--device cpu|cuda] [--agc on|off]
                          [--threads 2] [--out hyps.jsonl] [--check]

DIR holds a FLEURS download laid out as <set>_test.tsv and <set>_audio_test/test/*.wav (see server/README.md).
The first N rows of each test TSV are used, in file order. Korean is scored by CER with spaces and punctuation
removed, English by WER, both against FLEURS' normalised transcription and without number normalisation.
Speed: RTF of the whole streamed decode, and the finish time (close() after the last 100 ms block), which is what a
speaker waits for at the end of a turn. --check also verifies that streamed features equal one pass over the whole
utterance and that the text does not depend on the block size or on NeMo's own streaming buffer.
"""
import argparse
import csv
import json
import os
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

PROMPT = {"ko_kr": "ko", "en_us": "en"}
BLOCK = 1600


def rows(root, name, n):
    with open(Path(root) / f"{name}_test.tsv", encoding="utf-8", newline="") as f:
        out = [(r[1], r[3]) for r in csv.reader(f, delimiter="\t", quoting=csv.QUOTE_NONE)]
    return out[:n] if n else out


def load_wav(root, name, file):
    import soundfile as sf
    import numpy as np
    x, rate = sf.read(Path(root) / f"{name}_audio_test" / "test" / file, dtype="float32")
    assert rate == 16000, rate
    return np.asarray(x if x.ndim == 1 else x.mean(axis=1), np.float32)


def clean(text):
    return re.sub(r"<[^>]*>", "", text).lower()


def cer(refs, hyps):
    import jiwer
    squash = lambda t: re.sub(r"[\W_]+", "", clean(t))  # noqa: E731
    return jiwer.cer([squash(r) for r in refs], [squash(h) for h in hyps])


def wer(refs, hyps):
    import jiwer
    words = lambda t: " ".join(re.sub(r"[^\w' ]+", " ", clean(t)).split())  # noqa: E731
    return jiwer.wer([words(r) for r in refs], [words(h) for h in hyps])


def run(rec, audio, lang, agc):
    """Stream one utterance in 100 ms blocks; returns (text, decode seconds, finish seconds)."""
    from stt import Leveler
    lev = Leveler() if agc else None
    u = rec.utterance(lang)
    t0 = time.perf_counter()
    for i in range(0, audio.size, BLOCK):
        x = audio[i:i + BLOCK]
        u.feed(lev.apply(x) if lev else x)
    t1 = time.perf_counter()
    if lev:
        u.feed(lev.drain())
    text = u.close()
    t2 = time.perf_counter()
    return text, t2 - t0, t2 - t1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fleurs", default=os.environ.get("FLEURS_DIR", "fleurs"))
    ap.add_argument("--sets", default="ko_kr,en_us")
    ap.add_argument("--n", type=int, default=100)
    ap.add_argument("--device", default="cpu")
    ap.add_argument("--agc", default="on")
    ap.add_argument("--threads", type=int, default=2)
    ap.add_argument("--out")
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()
    if a.device == "cpu":
        os.environ["CUDA_VISIBLE_DEVICES"] = ""
    import numpy as np
    import torch
    torch.set_num_threads(a.threads)
    from stt import load_recognizer

    t0 = time.time()
    rec = load_recognizer(a.device)
    print(f"load {time.time() - t0:.1f}s on {a.device}, {a.threads} threads", flush=True)
    out = open(a.out, "w", encoding="utf-8") if a.out else None
    if a.check:
        check(rec, np.concatenate([load_wav(a.fleurs, "ko_kr", f) for f, _ in rows(a.fleurs, "ko_kr", 3)]))
    for name in a.sets.split(","):
        refs, hyps, audio_s, decode_s, finish = [], [], 0.0, 0.0, []
        for file, ref in rows(a.fleurs, name, a.n):
            x = load_wav(a.fleurs, name, file)
            text, dec, fin = run(rec, x, PROMPT[name], a.agc == "on")
            refs.append(ref)
            hyps.append(text)
            audio_s += x.size / 16000
            decode_s += dec
            finish.append(fin)
            if out:
                out.write(json.dumps({"set": name, "file": file, "ref": ref, "hyp": text, "decode_s": round(dec, 3),
                                      "finish_s": round(fin, 3)}, ensure_ascii=False) + "\n")
        score = {"cer": round(cer(refs, hyps), 4)} if name.startswith("ko") else {"wer": round(wer(refs, hyps), 4)}
        print(json.dumps({"set": name, "utterances": len(refs), "audio_min": round(audio_s / 60, 1), **score,
                          "rtf": round(decode_s / audio_s, 3), "finish_ms_mean": round(1000 * np.mean(finish)),
                          "finish_ms_p90": round(1000 * np.percentile(finish, 90)), "agc": a.agc}), flush=True)


def check(rec, x):
    import torch
    from nemo.collections.asr.parts.utils.streaming_utils import CacheAwareStreamingAudioBuffer
    from stt.nemotron import MelFeed
    full = rec.mel(x)
    feed, parts = MelFeed(rec), []
    for i in range(0, x.size, BLOCK):
        feed.push(x[i:i + BLOCK])
        if (f := feed.pull(False)) is not None:
            parts.append(f)
    parts.append(feed.pull(True))
    streamed = torch.cat([p for p in parts if p is not None], dim=-1)
    print(f"check: streamed features {tuple(streamed.shape)} vs one pass {tuple(full.shape)}, "
          f"max abs diff {(streamed - full).abs().max().item():.2e}", flush=True)
    s100 = rec.transcribe(x, "ko", BLOCK)
    s_all = rec.transcribe(x, "ko", x.size)
    s_odd = rec.transcribe(x, "ko", 1237)
    print("check: 100 ms blocks == whole:", s100 == s_all, "| 1237-sample blocks == whole:", s_odd == s_all, flush=True)
    buf = CacheAwareStreamingAudioBuffer(model=rec.model, online_normalization=False)
    buf.append_audio(x, stream_id=-1)
    cache = rec.model.encoder.get_initial_cache_state(batch_size=1)
    hyp = None
    with torch.no_grad():
        for step, (chunk, n) in enumerate(buf):
            o = rec.model.conformer_stream_step(
                processed_signal=chunk, processed_signal_length=n, cache_last_channel=cache[0],
                cache_last_time=cache[1], cache_last_channel_len=cache[2],
                keep_all_outputs=buf.is_buffer_empty(), previous_hypotheses=hyp,
                drop_extra_pre_encoded=0 if step == 0 else rec.cfg.drop_extra_pre_encoded, return_transcription=True)
            cache, hyp = o[2:5], o[5]
    print("check: NeMo streaming buffer text == ours:", hyp[0].text == s_all, flush=True)


if __name__ == "__main__":
    main()
