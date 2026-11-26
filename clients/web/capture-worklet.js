// Resamples mic input to 16 kHz mono and posts PCM16 frames plus their RMS.

function lowpass(fc, n) {
  const h = new Float32Array(n);
  const m = (n - 1) / 2;
  let sum = 0;
  for (let i = 0; i < n; i++) {
    const x = i - m;
    const sinc = x === 0 ? 2 * fc : Math.sin(2 * Math.PI * fc * x) / (Math.PI * x);
    const w = 0.42 - 0.5 * Math.cos((2 * Math.PI * i) / (n - 1)) + 0.08 * Math.cos((4 * Math.PI * i) / (n - 1));
    h[i] = sinc * w;
    sum += h[i];
  }
  return h.map((v) => v / sum);
}

class Pcm16Capture extends AudioWorkletProcessor {
  constructor({ processorOptions: o = {} } = {}) {
    super();
    this.step = sampleRate / (o.outRate || 16000);
    this.h = this.step > 1 ? lowpass(0.45 / this.step, 63) : new Float32Array(0);
    this.buf = new Float32Array(2 * this.h.length);
    this.w = 0;
    this.t = 1;
    this.prev = 0;
    this.frame = new Int16Array(o.frameSamples || 1600);
    this.n = 0;
    this.sq = 0;
  }

  process(inputs) {
    const ch = inputs[0] && inputs[0][0];
    if (!ch) return true;
    const { h, buf } = this;
    const N = h.length;
    for (let i = 0; i < ch.length; i++) {
      let y = ch[i];
      if (N) {
        buf[this.w] = buf[this.w + N] = y;
        this.w = (this.w + 1) % N;
        y = 0;
        for (let k = 0; k < N; k++) y += h[k] * buf[this.w + k];
      }
      while (this.t <= 1) {
        this.push(this.prev + (y - this.prev) * this.t);
        this.t += this.step;
      }
      this.t -= 1;
      this.prev = y;
    }
    return true;
  }

  push(v) {
    const s = v < -1 ? -1 : v > 1 ? 1 : v;
    this.sq += s * s;
    this.frame[this.n++] = Math.round(s < 0 ? s * 0x8000 : s * 0x7fff);
    if (this.n === this.frame.length) {
      const len = this.frame.length;
      const { buffer } = this.frame;
      this.frame = new Int16Array(len);
      this.port.postMessage({ pcm: buffer, rms: Math.sqrt(this.sq / len) }, [buffer]);
      this.n = 0;
      this.sq = 0;
    }
  }
}

registerProcessor('pcm16-capture', Pcm16Capture);
