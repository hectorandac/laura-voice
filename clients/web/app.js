const OUT_RATE = 16000;
const FRAME_SAMPLES = 1600;
const BARGE_RMS = 0.02;
const BARGE_FRAMES = 3;
const BARGE_WINDOW = 5;

const $ = (id) => document.getElementById(id);
const ui = {
  conn: $('conn'), connLabel: $('connLabel'), modeSeg: $('modeSeg'), langSeg: $('langSeg'),
  log: $('log'), empty: $('empty'), orb: $('orb'), orbLabel: $('orbLabel'), partial: $('partial'),
};

const store = {
  get: (k, d) => { try { return localStorage.getItem(k) || d; } catch { return d; } },
  set: (k, v) => { try { localStorage.setItem(k, v); } catch {} },
};

const settings = { mode: store.get('va.mode', 'handsfree'), lang: store.get('va.lang', 'auto') };
// Car mode: echo cancellation off so Android keeps media routing (car Bluetooth), half-duplex to avoid self-hearing.
let carMode = store.get('va.car', new URLSearchParams(location.search).get('car') === '1' ? '1' : '0') === '1';
let quietUntil = 0;
let serverState = 'idle';
let handsfreeOn = false;
let holding = false;

// Single place that assembles situational cues for the backend (presence, location, activity).
function buildContext() {
  return carMode ? { car: true } : {};
}

// ---- WebSocket ----

let ws = null;
let retry = 0;
let retryTimer = 0;

const isOpen = () => ws && ws.readyState === WebSocket.OPEN;

function send(msg) {
  if (isOpen()) ws.send(JSON.stringify(msg));
}

function accessKey() {
  const fromUrl = new URLSearchParams(location.search).get('k');
  try {
    if (fromUrl) localStorage.setItem('va-key', fromUrl);
    return fromUrl || localStorage.getItem('va-key') || '';
  } catch {
    return fromUrl || '';
  }
}

function connect() {
  clearTimeout(retryTimer);
  if (ws && ws.readyState <= WebSocket.OPEN) return;
  const key = accessKey();
  const sock = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws${key ? `?k=${encodeURIComponent(key)}` : ''}`);
  sock.binaryType = 'arraybuffer';
  const rawSend = sock.send.bind(sock);
  sock.send = (d) => { net.count('up', d); rawSend(d); };
  ws = sock;
  setConn('connecting');
  sock.onopen = () => {
    retry = 0;
    setConn('online');
    codec.ready.then(() => {
      if (ws !== sock) return;
      codec.enc = null;
      codec.ts = 0;
      send({ type: 'hello', version: 1, client: 'pwa', lang: settings.lang, mode: settings.mode, context: buildContext(),
        audio: { up: codec.up, down: codec.down } });
      net.codecs = `${codec.up}/${codec.down[0]}`;
    });
  };
  sock.onmessage = (e) => {
    if (ws !== sock) return;
    net.count('down', e.data);
    if (typeof e.data === 'string') onMessage(JSON.parse(e.data));
    else { net.firstAudio(); onAudio(e.data); }
  };
  sock.onclose = (e) => {
    if (ws !== sock) return;
    ws = null;
    serverState = 'idle';
    stopPlayback();
    ui.partial.textContent = '';
    if (e.code === 4000) {
      // Another device took the conversation: don't reconnect on our own, or the two would keep evicting each other.
      setConn('elsewhere');
      return;
    }
    setConn('offline');
    render();
    const delay = Math.min(10000, 500 * 2 ** retry++) * (0.75 + Math.random() * 0.5);
    retryTimer = setTimeout(connect, delay);
  };
  sock.onerror = () => sock.close();
}

function setConn(state) {
  ui.conn.dataset.state = state;
  ui.connLabel.textContent = ui.conn.title = { online: 'Connected', connecting: 'Connecting', offline: 'Offline',
    elsewhere: 'In use on another device · tap to use here' }[state];
  render();
}

function sendContext(data) {
  send({ type: 'context', data });
}

// ---- Live network and latency stats ----

const net = {
  up: 0, down: 0, upTotal: 0, downTotal: 0, upKbps: 0, downKbps: 0,
  rtt: null, turnMs: null, turnT0: null, server: null, tick: 0,
  size: (d) => (typeof d === 'string' ? d.length : d.byteLength ?? d.size ?? 0),
  count(dir, d) {
    const n = this.size(d);
    this[dir] += n;
    this[dir + 'Total'] += n;
  },
  turnStarted() { this.turnT0 = performance.now(); },
  firstAudio() {
    if (this.turnT0 == null) return;
    this.turnMs = Math.round(performance.now() - this.turnT0);
    this.turnT0 = null;
    this.render();
  },
  pong(t) { this.rtt = Math.round(performance.now() - t); },
  everySecond() {
    this.upKbps = Math.round((this.up * 8) / 1000);
    this.downKbps = Math.round((this.down * 8) / 1000);
    this.up = this.down = 0;
    this.tick++;
    if (isOpen() && this.tick % 2 === 0) send({ type: 'ping', t: performance.now() });
    if (isOpen() && this.tick % 5 === 0) {
      send({ type: 'client_stats', rtt_ms: this.rtt, up_kbps: this.upKbps, down_kbps: this.downKbps,
        up_mb: +(this.upTotal / 1e6).toFixed(2), down_mb: +(this.downTotal / 1e6).toFixed(2), turn_ms: this.turnMs,
        codecs: this.codecs, gate_open: gate.open, played: this.played || 0, played_s: +(this.playedS || 0).toFixed(1),
        barge_ins: this.bargeIns || 0, dec_errors: this.decErrors || 0, last_err: this.lastErr || null,
        ctx: ctx ? ctx.state : null, ctx_rate: ctx ? ctx.sampleRate : null, car: carMode,
        conn: navigator.connection ? { type: navigator.connection.effectiveType, downlink: navigator.connection.downlink, rtt: navigator.connection.rtt } : null });
    }
    this.render();
  },
  render() {
    const el = $('stats');
    if (!el) return;
    const s = this.server;
    const parts = [
      `RTT ${this.rtt ?? '–'} ms`,
      `↑ ${this.upKbps} kbps`,
      `↓ ${this.downKbps} kbps`,
      `${((this.upTotal + this.downTotal) / 1e6).toFixed(1)} MB`,
      `reply ${this.turnMs != null ? (this.turnMs / 1000).toFixed(2) + ' s' : '–'}`,
    ];
    if (this.codecs) parts.push(`audio ${this.codecs}${gate.off ? '' : ' · gated'}`);
    if (s) parts.push(`server: asr ${s.asr_ms} · llm ${s.llm_first_ms} · tts ${s.tts_first_ms} ms`);
    el.textContent = parts.join('  ·  ');
  },
};
setInterval(() => net.everySecond(), 1000);

// ---- Server messages ----

const assistantBubbles = new Map();

function onMessage(m) {
  switch (m.type) {
    case 'session':
      ui.conn.title = `Connected · session ${m.id}`;
      break;
    case 'state':
      serverState = m.value;
      if (m.value === 'thinking') ui.partial.textContent = '';
      render();
      break;
    case 'partial':
      ui.partial.textContent = m.text;
      ui.partial.lang = detectLang(m.text);
      break;
    case 'user_turn':
      ui.partial.textContent = '';
      addBubble('user', m.text, m.lang);
      net.turnStarted();
      break;
    case 'pong':
      net.pong(m.t);
      break;
    case 'metrics':
      net.server = m;
      net.render();
      break;
    case 'assistant_delta': {
      const el = assistantBubble(m.turn_id);
      el.textContent += m.text;
      el.lang = detectLang(el.textContent);
      scrollLog();
      break;
    }
    case 'assistant_done': {
      const el = assistantBubble(m.turn_id);
      el.textContent = m.text;
      el.lang = detectLang(m.text);
      scrollLog();
      break;
    }
    case 'audio_start':
      startTurnAudio(m.turn_id, m.sample_rate, m.codec);
      break;
    case 'audio_end':
      break;
    case 'duck':
      if (m.turn_id === play.turn && play.gain && ctx) {
        const g = m.on ? Math.pow(10, (m.gain_db ?? -12) / 20) : 1;
        play.gain.gain.setTargetAtTime(g, ctx.currentTime, m.on ? 0.01 : 0.05);
      }
      break;
    case 'interrupted':
      if (m.turn_id === play.turn && !droppedTurns.has(m.turn_id)) {
        send({ type: 'playback', turn_id: m.turn_id, played_ms: playedMs(), state: 'stopped' });
        stopPlayback(true);
      }
      droppedTurns.add(m.turn_id);
      break;
    case 'error':
      addBubble('system', m.message);
      break;
  }
}

const detectLang = (text) => (/[ㄱ-ㆎ가-힣]/.test(text) ? 'ko' : 'en');

function addBubble(role, text, lang) {
  ui.empty.hidden = true;
  const el = document.createElement('div');
  el.className = `msg ${role}`;
  if (role === 'user') {
    const tag = document.createElement('span');
    tag.className = 'tag';
    tag.textContent = (lang || '?').toUpperCase();
    el.lang = lang || detectLang(text);
    el.append(tag, document.createTextNode(text));
  } else {
    el.textContent = text;
  }
  ui.log.append(el);
  scrollLog(true);
  return el;
}

function assistantBubble(turnId) {
  let el = assistantBubbles.get(turnId);
  if (!el) {
    el = addBubble('assistant', '');
    assistantBubbles.set(turnId, el);
  }
  return el;
}

function scrollLog(force) {
  const l = ui.log;
  if (force || l.scrollHeight - l.scrollTop - l.clientHeight < 120) l.scrollTop = l.scrollHeight;
}

// ---- Audio: shared context, capture, playback ----

let ctx = null;
let workletReady = null;
let mic = null;
let preroll = null;
let loudHistory = [];

function audioContext() {
  if (!ctx) {
    if (navigator.audioSession) navigator.audioSession.type = 'play-and-record';
    ctx = new AudioContext({ latencyHint: 'interactive' });
    workletReady = ctx.audioWorklet.addModule('capture-worklet.js');
  }
  if (ctx.state !== 'running') ctx.resume().catch(() => {});
  return ctx;
}

async function openMic() {
  if (mic) return mic.ready;
  const c = audioContext();
  const m = (mic = {});
  m.ready = (async () => {
    const [stream] = await Promise.all([
      navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: !carMode, noiseSuppression: false, autoGainControl: false, channelCount: 1 },
      }),
      workletReady,
    ]);
    if (mic !== m) return stream.getTracks().forEach((t) => t.stop());
    m.stream = stream;
    m.source = c.createMediaStreamSource(stream);
    m.node = new AudioWorkletNode(c, 'pcm16-capture', {
      numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [1],
      processorOptions: { outRate: OUT_RATE, frameSamples: FRAME_SAMPLES },
    });
    m.node.port.onmessage = (e) => onFrame(e.data.pcm, e.data.rms);
    m.source.connect(m.node).connect(c.destination);
    stream.getAudioTracks()[0].addEventListener('ended', closeMic);
  })();
  try {
    await m.ready;
  } catch (err) {
    if (mic === m) mic = null;
    handsfreeOn = holding = false;
    addBubble('system', `Microphone unavailable: ${err.message || err.name}`);
    render();
    throw err;
  }
}

function closeMic() {
  const m = mic;
  mic = null;
  preroll = null;
  if (!m) return;
  m.node && m.node.disconnect();
  m.source && m.source.disconnect();
  m.stream && m.stream.getTracks().forEach((t) => t.stop());
  setLevel(0);
}

// ---- Audio codecs (Opus via WebCodecs when available, PCM16 otherwise) and the speech gate ----

const urlOpt = (k) => new URLSearchParams(location.search).get(k);
const codec = { up: 'pcm', down: ['pcm'], enc: null, ts: 0 };
codec.ready = (async () => {
  if (urlOpt('codec') === 'pcm') return;
  try {
    if (typeof AudioEncoder !== 'undefined' && (await AudioEncoder.isConfigSupported({
      codec: 'opus', sampleRate: OUT_RATE, numberOfChannels: 1, bitrate: 24000 })).supported) codec.up = 'opus';
  } catch {}
  try {
    if (typeof AudioDecoder !== 'undefined' && (await AudioDecoder.isConfigSupported({
      codec: 'opus', sampleRate: 24000, numberOfChannels: 1 })).supported) codec.down = ['opus', 'pcm'];
  } catch {}
})();

function sendAudio(buf) {
  if (!isOpen()) return;
  if (codec.up !== 'opus') { ws.send(buf); return; }
  if (!codec.enc || codec.enc.state === 'closed') {
    codec.enc = new AudioEncoder({
      output: (chunk) => {
        const b = new Uint8Array(chunk.byteLength);
        chunk.copyTo(b);
        if (isOpen()) ws.send(b.buffer);
      },
      error: (e) => {
        console.warn('Opus encoder failed, falling back to PCM', e);
        codec.up = 'pcm';
        if (ws) ws.close();
      },
    });
    codec.enc.configure({ codec: 'opus', sampleRate: OUT_RATE, numberOfChannels: 1, bitrate: 24000 });
  }
  const pcm = new Int16Array(buf);
  codec.enc.encode(new AudioData({ format: 's16', sampleRate: OUT_RATE, numberOfFrames: pcm.length,
    numberOfChannels: 1, timestamp: codec.ts, data: pcm }));
  codec.ts += Math.round((pcm.length / OUT_RATE) * 1e6);
}

// Hands-free only sends while someone is talking: an adaptive noise floor opens the gate, it sends ~300 ms
// of lead-in, and keeps sending for GATE_HANG_MS after the last loud frame so the server still hears the
// pause that ends the turn.
const GATE_HANG_MS = 1200;
const GATE_PRE_FRAMES = 3;
const gate = { open: false, floor: 0.002, lastLoud: 0, pre: [], off: urlOpt('gate') === 'off' };

function gatedSend(pcm, rms) {
  if (gate.off) { sendAudio(pcm); return; }
  const now = performance.now();
  gate.floor = Math.max(0.0005, Math.min(rms, gate.floor * 1.01));
  if (rms > Math.max(0.002, gate.floor * 2.5)) gate.lastLoud = now;
  const wantOpen = now - gate.lastLoud < GATE_HANG_MS;
  if (wantOpen && !gate.open) {
    gate.open = true;
    for (const p of gate.pre) sendAudio(p);
    gate.pre = [];
  } else if (!wantOpen && gate.open) {
    gate.open = false;
  }
  if (gate.open) sendAudio(pcm);
  else gate.pre = [...gate.pre.slice(1 - GATE_PRE_FRAMES), pcm];
}

function onFrame(pcm, rms) {
  setLevel(rms);
  if (carMode && (isPlaying() || serverState === 'speaking')) quietUntil = performance.now() + 600;
  if (carMode && performance.now() < quietUntil) { gate.open = false; gate.pre = []; loudHistory = []; return; }
  if (isOpen() && settings.mode === 'handsfree' && handsfreeOn) gatedSend(pcm, rms);
  else if (isOpen() && settings.mode === 'ptt' && holding) sendAudio(pcm);
  else preroll = pcm;
  if (settings.mode === 'handsfree' && handsfreeOn && isPlaying()) {
    loudHistory = [...loudHistory.slice(1 - BARGE_WINDOW), rms > BARGE_RMS];
    if (loudHistory.filter(Boolean).length >= BARGE_FRAMES) bargeIn();
  } else {
    loudHistory = [];
  }
}

function setLevel(rms) {
  const db = 20 * Math.log10(rms || 1e-6);
  ui.orb.style.setProperty('--level', Math.max(0, Math.min(1, (db + 60) / 45)).toFixed(3));
}

const play = { turn: null, drop: false, rate: 24000, next: 0, sources: new Set() };
const droppedTurns = new Set();
const isPlaying = () => play.sources.size > 0;

function startTurnAudio(turnId, rate, codecName) {
  play.turn = turnId;
  play.rate = rate;
  play.drop = droppedTurns.has(turnId);
  play.next = 0;
  play.chunks = [];
  if (ctx) {
    play.gain = ctx.createGain();
    play.gain.connect(ctx.destination);
  }
  play.codec = codecName || 'pcm';
  if (play.dec && play.dec.state !== 'closed') { try { play.dec.close(); } catch {} }
  play.dec = null;
  if (play.codec === 'opus') {
    play.ts = 0;
    play.dec = new AudioDecoder({
      output: (d) => {
        try {
          const f = new Float32Array(d.numberOfFrames);
          d.copyTo(f, { planeIndex: 0, format: 'f32-planar' });
          schedule(f, d.sampleRate);
        } catch (err) {
          net.decErrors = (net.decErrors || 0) + 1;
          net.lastErr = `output: ${err.message || err}`;
        } finally {
          d.close();
        }
      },
      error: (e) => { net.decErrors = (net.decErrors || 0) + 1; net.lastErr = String(e && e.message || e); console.warn('Opus decoder error', e); },
    });
    play.dec.configure({ codec: 'opus', sampleRate: rate, numberOfChannels: 1 });
  }
}

function onAudio(buf) {
  if (play.drop || !ctx || buf.byteLength < 2) return;
  if (play.codec === 'opus') {
    if (!play.dec || play.dec.state === 'closed') return;
    play.dec.decode(new EncodedAudioChunk({ type: 'key', timestamp: play.ts, data: buf }));
    play.ts += 20000;
    return;
  }
  const pcm = new Int16Array(buf, 0, buf.byteLength >> 1);
  const f = new Float32Array(pcm.length);
  for (let i = 0; i < pcm.length; i++) f[i] = pcm[i] / 32768;
  schedule(f, play.rate);
}

function schedule(samples, rate) {
  if (play.drop || !ctx) return;
  const ab = ctx.createBuffer(1, samples.length, rate);
  ab.getChannelData(0).set(samples);
  const src = ctx.createBufferSource();
  src.buffer = ab;
  src.connect(play.gain || ctx.destination);
  const at = Math.max(play.next, ctx.currentTime + 0.05);
  src.start(at);
  play.chunks.push([at, ab.duration]);
  net.played = (net.played || 0) + 1;
  net.playedS = (net.playedS || 0) + ab.duration;
  play.next = at + ab.duration;
  play.sources.add(src);
  src.onended = () => {
    play.sources.delete(src);
    if (!isPlaying()) render();
  };
  if (play.sources.size === 1) render();
}

// Reply audio of the current turn that has come out of the speaker so far.
function playedMs() {
  if (!ctx || !play.chunks) return 0;
  const now = ctx.currentTime - (ctx.outputLatency || ctx.baseLatency || 0);
  let s = 0;
  for (const [at, d] of play.chunks) s += Math.min(Math.max(now - at, 0), d);
  return Math.round(s * 1000);
}

function stopPlayback(fade) {
  const end = ctx && fade && play.gain ? ctx.currentTime + 0.08 : 0;
  if (end) {
    play.gain.gain.setValueAtTime(play.gain.gain.value, ctx.currentTime);
    play.gain.gain.linearRampToValueAtTime(0, end);
  }
  for (const src of play.sources) {
    src.onended = null;
    try { src.stop(end); } catch {}
  }
  play.sources.clear();
  if (play.turn !== null) droppedTurns.add(play.turn);
  play.drop = true;
}

function bargeIn() {
  net.bargeIns = (net.bargeIns || 0) + 1;
  const turn = play.turn;
  const played = playedMs();
  loudHistory = [];
  stopPlayback(true);
  const el = assistantBubbles.get(turn);
  if (el) el.classList.add('cut');
  send({ type: 'interrupt', turn_id: turn, played_ms: played });
  render();
}

// ---- Controls ----

async function toggleHandsfree() {
  if (handsfreeOn) {
    handsfreeOn = false;
    if (isPlaying()) bargeIn();
    closeMic();
    render();
    return;
  }
  handsfreeOn = true;
  render();
  try { await openMic(); } catch { return; }
  render();
}

async function pttDown() {
  if (holding) return;
  holding = true;
  if (isPlaying()) bargeIn();
  render();
  try { await openMic(); } catch { return; }
  if (holding && preroll && isOpen()) sendAudio(preroll);
  preroll = null;
}

function pttUp() {
  if (!holding) return;
  holding = false;
  send({ type: 'end_turn' });
  render();
}

ui.conn.addEventListener('click', () => { if (ui.conn.dataset.state === 'elsewhere') connect(); });
ui.orb.addEventListener('click', () => {
  if (ui.conn.dataset.state === 'elsewhere') { connect(); return; }
  if (settings.mode === 'handsfree') toggleHandsfree();
});
ui.orb.addEventListener('pointerdown', (e) => {
  if (settings.mode !== 'ptt' || e.button > 0) return;
  ui.orb.setPointerCapture(e.pointerId);
  pttDown();
});
for (const ev of ['pointerup', 'pointercancel', 'lostpointercapture']) {
  ui.orb.addEventListener(ev, () => settings.mode === 'ptt' && pttUp());
}
ui.orb.addEventListener('contextmenu', (e) => e.preventDefault());
ui.orb.addEventListener('keydown', (e) => {
  if (settings.mode === 'ptt' && (e.key === ' ' || e.key === 'Enter')) {
    e.preventDefault();
    if (!e.repeat) pttDown();
  }
});
ui.orb.addEventListener('keyup', (e) => {
  if (settings.mode === 'ptt' && (e.key === ' ' || e.key === 'Enter')) pttUp();
});

function setMode(mode) {
  if (mode === settings.mode) return;
  settings.mode = mode;
  store.set('va.mode', mode);
  holding = false;
  if (handsfreeOn) {
    handsfreeOn = false;
    closeMic();
  }
  send({ type: 'mode', value: mode });
  render();
}

function setLang(lang) {
  if (lang === settings.lang) return;
  settings.lang = lang;
  store.set('va.lang', lang);
  send({ type: 'lang', value: lang });
  render();
}

ui.modeSeg.addEventListener('click', (e) => e.target.dataset.value && setMode(e.target.dataset.value));
const carBtn = $('carBtn');
carBtn.addEventListener('click', async () => {
  carMode = !carMode;
  store.set('va.car', carMode ? '1' : '0');
  sendContext(buildContext());
  render();
  if (mic) { closeMic(); if (handsfreeOn || holding) { try { await openMic(); } catch {} } }
});
ui.langSeg.addEventListener('click', (e) => e.target.dataset.value && setLang(e.target.dataset.value));

// ---- View ----

const LABELS = { listening: 'Listening', thinking: 'Thinking…', speaking: 'Speaking', offline: 'Offline' };

function viewState() {
  if (isPlaying()) return 'speaking';
  if (holding) return 'listening';
  if (!isOpen()) return 'offline';
  if (serverState === 'thinking') return 'thinking';
  if (serverState === 'speaking' && !play.drop) return 'speaking';
  if (handsfreeOn) return 'listening';
  return 'idle';
}

function render() {
  const state = viewState();
  ui.orb.dataset.state = state;
  document.body.dataset.state = state;
  ui.orbLabel.textContent =
    state === 'idle' ? (settings.mode === 'ptt' ? 'Hold to talk' : 'Tap to start') : LABELS[state];
  if (state === 'offline' && settings.mode === 'handsfree' && handsfreeOn) ui.orbLabel.textContent = 'Reconnecting';
  for (const b of ui.modeSeg.children) b.setAttribute('aria-pressed', b.dataset.value === settings.mode);
  for (const b of ui.langSeg.children) b.setAttribute('aria-pressed', b.dataset.value === settings.lang);
  $('carBtn').setAttribute('aria-pressed', carMode);
  if (!handsfreeOn && !holding) setLevel(0);
  syncWakeLock();
}

// ---- Wake lock, lifecycle ----

let wakeLock = null;
let wakePending = false;

async function syncWakeLock() {
  if (!('wakeLock' in navigator) || wakePending) return;
  const want = document.visibilityState === 'visible' && (handsfreeOn || holding || isPlaying() || !!mic);
  wakePending = true;
  try {
    if (want && !wakeLock) {
      wakeLock = await navigator.wakeLock.request('screen');
      wakeLock.addEventListener('release', () => { wakeLock = null; });
    } else if (!want && wakeLock) {
      await wakeLock.release();
      wakeLock = null;
    }
  } catch {}
  wakePending = false;
}

document.addEventListener('visibilitychange', () => {
  if (document.visibilityState !== 'visible') return;
  if (ctx && ctx.state !== 'running') ctx.resume().catch(() => {});
  if (!ws) connect();
  render();
});
window.addEventListener('online', () => { if (!ws) connect(); });

if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});

Object.assign(window, { voiceAssistant: { sendContext, buildContext } });

render();
connect();
