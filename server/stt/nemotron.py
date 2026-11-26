"""Nemotron 3.5 ASR Streaming 0.6B (cache-aware FastConformer, RNN-T) as an utterance-at-a-time recognizer.

The encoder runs on fixed-size chunks with its attention cache carried from call to call (NeMo's
`conformer_stream_step`). `MelFeed` computes log-mel frames as audio arrives, recomputing a few frames of left
context per call, so the streamed frames equal those of one pass over the whole utterance and the text does not
depend on how the audio was cut into pieces.
"""
import threading
from dataclasses import dataclass

import numpy as np
import torch

HF_REPO = "nvidia/nemotron-3.5-asr-streaming-0.6b"
HF_FILE = "nemotron-3.5-asr-streaming-0.6b.nemo"
CONTEXT = (56, 13)   # encoder attention context (left, right), in encoder frames


def load_recognizer(device="cuda", context=CONTEXT, path=None):
    from huggingface_hub import hf_hub_download
    from nemo.collections.asr.models import ASRModel
    from nemo.collections.asr.parts.submodules.rnnt_decoding import RNNTDecodingConfig

    path = path or hf_hub_download(HF_REPO, HF_FILE)
    model = ASRModel.restore_from(path, map_location="cpu").eval()   # unpacking straight onto the GPU peaks at ~4.3 GB
    model = model.to(device=device, dtype=torch.float32)
    model.encoder.set_default_att_context_size(list(context))
    decoding = RNNTDecodingConfig(fused_batch_size=-1, strip_lang_tags=True)
    decoding.greedy.use_cuda_graph_decoder = False
    model.change_decoding_strategy(decoding)
    return Recognizer(model)


def _pick(value, first):
    """Streaming config entries are either one number or a (first chunk, later chunks) pair."""
    if isinstance(value, (list, tuple)):
        return value[0] if first else value[1]
    return value


@dataclass
class DecoderState:
    cache: tuple
    hyps: list | None = None
    steps: int = 0


class Recognizer:
    """The loaded model, shared by every utterance. The language prompt is a property of the model, so encoder
    steps take a lock and set the prompt of the utterance they belong to."""

    def __init__(self, model):
        self.model = model
        self.device = next(model.parameters()).device
        pre = model.cfg.preprocessor
        self.rate = int(pre.sample_rate)
        self.hop = int(round(pre.window_stride * self.rate))
        self.n_fft = int(pre.n_fft)
        self.cfg = model.encoder.streaming_cfg
        self._lock = threading.Lock()
        self._prompt = None

    def utterance(self, lang="auto"):
        return Utterance(self, lang)

    def transcribe(self, audio, lang="auto", block=1600):
        u = self.utterance(lang)
        for i in range(0, audio.size, block):
            u.feed(audio[i:i + block])
        return u.close()

    def mel(self, samples):
        x = torch.from_numpy(np.ascontiguousarray(samples)).unsqueeze(0).to(self.device)
        n = torch.tensor([samples.size], device=self.device)
        with torch.no_grad():
            frames, _ = self.model.preprocessor(input_signal=x, length=n)
        return frames

    def new_state(self):
        return DecoderState(cache=tuple(self.model.encoder.get_initial_cache_state(batch_size=1)))

    def step(self, lang, chunk, state, last):
        """Encode one chunk ([pre-encode context | new frames]) and extend the hypothesis. Returns its text."""
        drop = 0 if state.steps == 0 else self.cfg.drop_extra_pre_encoded
        n = torch.tensor([chunk.shape[-1]], device=self.device)
        with self._lock, torch.no_grad():
            if lang != self._prompt:
                self.model.set_inference_prompt(lang)
                self._prompt = lang
            out = self.model.conformer_stream_step(
                processed_signal=chunk, processed_signal_length=n,
                cache_last_channel=state.cache[0], cache_last_time=state.cache[1],
                cache_last_channel_len=state.cache[2], keep_all_outputs=last,
                previous_hypotheses=state.hyps, drop_extra_pre_encoded=drop, return_transcription=True)
        state.cache, state.hyps, state.steps = (out[2], out[3], out[4]), out[5], state.steps + 1
        return (state.hyps[0].text or "") if state.hyps else None


class MelFeed:
    """Log-mel frames for one utterance, computed as audio arrives."""

    OVERLAP = 4   # hops of audio recomputed on the left of each call (pre-emphasis and the STFT window)

    def __init__(self, rec):
        self.rec = rec
        self.samples = np.zeros(0, np.float32)
        self.first = 0      # utterance sample index of samples[0]
        self.done = 0       # frames emitted so far

    @property
    def end(self):
        return self.first + self.samples.size

    def push(self, audio):
        if audio.size:
            self.samples = np.concatenate([self.samples, audio])

    def pull(self, final):
        """New frames since the last call: only those whose window is complete, unless the utterance is over."""
        hop, half = self.rec.hop, self.rec.n_fft // 2
        ready = self.end // hop + 1 if final else max(0, (self.end - half) // hop + 1)
        if ready <= self.done:
            return None
        start, skip = (self.done - self.OVERLAP) * hop, self.OVERLAP
        if start <= 0:
            start, skip = 0, self.done
        frames = self.rec.mel(self.samples[start - self.first:])[..., skip:skip + ready - self.done]
        self.done += frames.shape[-1]
        keep = max(0, (self.done - self.OVERLAP - 2) * hop - half)
        if keep > self.first:
            self.samples = self.samples[keep - self.first:]
            self.first = keep
        return frames


class Utterance:
    """One user turn: feed() audio of any length as it arrives (returns the running text when it changed, else
    None), close() at the end of the turn (returns the final text)."""

    MIN_TAIL = 16   # frames: a shorter last chunk is padded with silence

    def __init__(self, rec, lang="auto"):
        self.rec, self.lang = rec, lang
        self.mel = MelFeed(rec)
        self.state = rec.new_state()
        self.frames = None   # pre-encode context plus frames not yet encoded
        self.base = 0        # utterance frame index of frames[..., 0]
        self.next = 0        # first frame not yet given to the encoder as new input
        self.text = ""

    def _collect(self, final):
        f = self.mel.pull(final)
        if f is not None:
            self.frames = f if self.frames is None else torch.cat([self.frames, f], dim=-1)

    def _chunk(self, final):
        cfg, first = self.rec.cfg, self.state.steps == 0
        size = _pick(cfg.chunk_size, first)
        avail = self.mel.done - self.next
        if avail <= 0 or (avail < size and not final):
            return None
        n = min(size, avail)
        ctx = 0 if first else _pick(cfg.pre_encode_cache_size, False)
        lo = self.next - ctx - self.base
        chunk = self.frames[..., lo:lo + ctx + n]
        if ctx and chunk.shape[-1] - ctx < 1:
            return None
        self.next += n
        cut = self.next - _pick(cfg.pre_encode_cache_size, False) - self.base
        if cut > 0:
            self.frames = self.frames[..., cut:]
            self.base += cut
        return chunk

    def _encode(self, chunk, last):
        text = self.rec.step(self.lang, chunk, self.state, last)
        if text is not None:
            self.text = text

    def feed(self, audio):
        self.mel.push(np.asarray(audio, np.float32))
        self._collect(False)
        before = self.text
        while (chunk := self._chunk(False)) is not None:
            self._encode(chunk, False)
        return self.text if self.text != before else None

    def close(self):
        short = self.mel.end // self.rec.hop + 1 - self.next
        if 0 < short < self.MIN_TAIL:
            self.mel.push(np.zeros((self.MIN_TAIL - short) * self.rec.hop, np.float32))
        self._collect(True)
        while (chunk := self._chunk(True)) is not None:
            self._encode(chunk, True)
        self.frames = None
        return self.text
