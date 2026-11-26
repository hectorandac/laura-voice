"""Automatic level control for a far-field microphone.

Works on 10 ms blocks. A background estimate follows the quietest blocks; only blocks clearly above it move the
speech level and the gain, so the gain holds between words instead of climbing on room noise. The gain is ramped
linearly across each block, and peaks above the knee are bent smoothly towards full scale instead of clipped.
"""
import numpy as np


class _Background:
    """Background level in dB: drops quickly to a quieter block, rises slowly otherwise."""

    def __init__(self, rise_db, fall):
        self.rise_db, self.fall, self.db = rise_db, fall, None

    def update(self, db):
        if self.db is None:
            self.db = db
        elif db < self.db:
            self.db += self.fall * (db - self.db)
        else:
            self.db = min(db, self.db + self.rise_db)
        return self.db


def _smooth(dt, seconds):
    return 1 - np.exp(-dt / seconds)


class Leveler:
    def __init__(self, rate=16000, block_ms=10, target_dbfs=-22.0, gain_range_db=(-10.0, 30.0), margin_db=10.0,
                 attack_s=0.15, release_s=1.5, background_rise_db_s=3.0, background_fall_s=0.05, knee=0.7):
        dt = block_ms / 1000
        self.block = int(rate * dt)
        self.target, self.gain_range, self.margin, self.knee = target_dbfs, gain_range_db, margin_db, knee
        self.attack, self.release = _smooth(dt, attack_s), _smooth(dt, release_s)
        self.background = _Background(background_rise_db_s * dt, _smooth(dt, background_fall_s))
        self.speech_db = None
        self.gain_db = 0.0
        self.gain = 1.0                       # linear gain at the end of the last block
        self.carry = np.zeros(0, np.float32)  # samples short of a whole block

    def _block_gain(self, block):
        db = 10 * np.log10(np.mean(block.astype(np.float64) ** 2) + 1e-12)
        floor = self.background.update(db)
        if db > floor + self.margin and db > -70:
            if self.speech_db is None:
                self.speech_db = db
            else:
                self.speech_db += (self.attack if db > self.speech_db else self.release) * (db - self.speech_db)
            want = float(np.clip(self.target - self.speech_db, *self.gain_range))
            self.gain_db += (self.attack if want < self.gain_db else self.release) * (want - self.gain_db)
        return 10 ** (self.gain_db / 20)

    def _bend(self, x):
        k = self.knee
        a = np.abs(x)
        return np.where(a > k, np.sign(x) * (k + (1 - k) * np.tanh((a - k) / (1 - k))), x).astype(np.float32)

    def apply(self, audio):
        """float32 mono in and out; the output trails the input by less than one block."""
        x = np.concatenate([self.carry, np.asarray(audio, np.float32)])
        whole = x.size - x.size % self.block
        self.carry = x[whole:]
        out = np.empty(whole, np.float32)
        for i in range(0, whole, self.block):
            b = x[i:i + self.block]
            g = self._block_gain(b)
            out[i:i + self.block] = b * np.linspace(self.gain, g, self.block, endpoint=False, dtype=np.float32)
            self.gain = g
        return self._bend(out)

    def drain(self):
        """The samples still held back, at the current gain (end of the utterance)."""
        x, self.carry = self.carry, np.zeros(0, np.float32)
        return self._bend(x * np.float32(self.gain))
