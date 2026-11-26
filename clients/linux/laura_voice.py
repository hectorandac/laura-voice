#!/usr/bin/env python3
"""Headless laura-voice client (smart speaker). Python 3.6+, standard library only.

Audio goes through arecord/aplay (or parec/pacat), Opus through libopus via ctypes (PCM16 if it is missing).

  laura_voice.py -c laura-voice.conf               run as a speaker
  laura_voice.py -c laura-voice.conf --wav q.wav --once --out reply.wav
  laura_voice.py fingerprint wss://host:8443        print the server certificate pin
"""
import argparse
import array
import base64
import configparser
import ctypes
import ctypes.util
import hashlib
import json
import os
import random
import signal
import socket
import ssl
import struct
import subprocess
import sys
import threading
import time
import wave
from urllib.parse import urlsplit, urlencode, parse_qsl

try:
    import warnings
    with warnings.catch_warnings():
        warnings.simplefilter('ignore')
        import audioop
except ImportError:
    audioop = None

RATE = 16000
FRAME = 1600
OPUS_FRAME = 320
GATE_HANG = 1.2
GATE_PRE_FRAMES = 3
BARGE_FRAMES = 3
BARGE_WINDOW = 5
TAKEOVER = 4000
BAD_KEY = 4401

DEFAULTS = {
    'url': '',
    'key': '',
    'pin_sha256': '',
    'ca_file': '',
    'lang': 'auto',
    'codec': 'opus',
    'capture': 'arecord -q -t raw -f S16_LE -c 1 -r 16000 -D default',
    'playback': 'aplay -q -t raw -f S16_LE -c 1 -r {rate} -D default',
    'half_duplex': 'yes',
    'barge_rms': '0.02',
    'gate_min': '0.002',
    'takeover_resume_s': '0',
    'device': '',
}


def log(*a):
    print(time.strftime('%H:%M:%S'), *a, flush=True)


def rms16(pcm):
    if audioop:
        return audioop.rms(pcm, 2) / 32768.0
    a = array.array('h', pcm)
    return (sum(x * x for x in a) / max(1, len(a))) ** 0.5 / 32768.0


def norm_pin(pin):
    pin = pin.strip().lower()
    if pin.startswith('sha256/'):
        pin = pin[7:]
    return pin.replace(':', '')


# ---- Opus via libopus ----

class Opus:
    lib = None

    @classmethod
    def load(cls):
        if cls.lib is None:
            name = ctypes.util.find_library('opus') or 'libopus.so.0'
            try:
                lib = ctypes.CDLL(name)
            except OSError:
                cls.lib = False
                return False
            lib.opus_encoder_create.restype = ctypes.c_void_p
            lib.opus_encoder_create.argtypes = [ctypes.c_int32, ctypes.c_int, ctypes.c_int, ctypes.POINTER(ctypes.c_int)]
            lib.opus_encode.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_int32]
            lib.opus_encoder_ctl.argtypes = [ctypes.c_void_p, ctypes.c_int]
            lib.opus_encoder_destroy.argtypes = [ctypes.c_void_p]
            lib.opus_decoder_create.restype = ctypes.c_void_p
            lib.opus_decoder_create.argtypes = [ctypes.c_int32, ctypes.c_int, ctypes.POINTER(ctypes.c_int)]
            lib.opus_decode.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int32, ctypes.c_char_p, ctypes.c_int, ctypes.c_int]
            lib.opus_decoder_destroy.argtypes = [ctypes.c_void_p]
            cls.lib = lib
        return bool(cls.lib)


class OpusEncoder:
    def __init__(self, rate=RATE, bitrate=24000):
        err = ctypes.c_int()
        self.lib = Opus.lib
        self.st = self.lib.opus_encoder_create(rate, 1, 2048, ctypes.byref(err))
        if err.value != 0:
            raise RuntimeError('opus_encoder_create failed: %d' % err.value)
        self.lib.opus_encoder_ctl(self.st, 4002, ctypes.c_int32(bitrate))
        self.out = ctypes.create_string_buffer(1500)

    def encode(self, pcm):
        n = self.lib.opus_encode(self.st, pcm, len(pcm) // 2, self.out, len(self.out))
        if n < 0:
            raise RuntimeError('opus_encode failed: %d' % n)
        return self.out.raw[:n]

    def __del__(self):
        if getattr(self, 'st', None):
            self.lib.opus_encoder_destroy(self.st)


class OpusDecoder:
    def __init__(self, rate):
        err = ctypes.c_int()
        self.lib = Opus.lib
        self.st = self.lib.opus_decoder_create(rate, 1, ctypes.byref(err))
        if err.value != 0:
            raise RuntimeError('opus_decoder_create failed: %d' % err.value)
        self.max = rate * 120 // 1000
        self.out = ctypes.create_string_buffer(self.max * 2)

    def decode(self, packet):
        n = self.lib.opus_decode(self.st, packet, len(packet), self.out, self.max, 0)
        if n < 0:
            raise RuntimeError('opus_decode failed: %d' % n)
        return self.out.raw[:n * 2]

    def __del__(self):
        if getattr(self, 'st', None):
            self.lib.opus_decoder_destroy(self.st)


# ---- Minimal RFC 6455 client ----

class Closed(Exception):
    def __init__(self, code, reason=''):
        Exception.__init__(self, 'closed %s %s' % (code, reason))
        self.code = code


class HandshakeError(Exception):
    def __init__(self, status):
        Exception.__init__(self, 'handshake failed: HTTP %s' % status)
        self.status = status


def tls_context(pin, ca_file):
    if pin:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT) if hasattr(ssl, 'PROTOCOL_TLS_CLIENT') else ssl.SSLContext(ssl.PROTOCOL_SSLv23)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        return ctx
    return ssl.create_default_context(cafile=ca_file or None)


def open_socket(url, pin='', ca_file='', timeout=10):
    u = urlsplit(url)
    secure = u.scheme in ('wss', 'https')
    port = u.port or (443 if secure else 80)
    sock = socket.create_connection((u.hostname, port), timeout=timeout)
    if secure:
        sock = tls_context(pin, ca_file).wrap_socket(sock, server_hostname=u.hostname)
        if pin:
            got = hashlib.sha256(sock.getpeercert(True)).hexdigest()
            if got != norm_pin(pin):
                sock.close()
                raise ssl.SSLError('certificate pin mismatch: server sha256 %s' % got)
    return sock


class WebSocket:
    def __init__(self, url, pin='', ca_file=''):
        u = urlsplit(url)
        self.sock = open_socket(url, pin, ca_file)
        self.lock = threading.Lock()
        self.buf = b''
        key = base64.b64encode(os.urandom(16)).decode()
        path = (u.path or '/') + ('?' + u.query if u.query else '')
        host = u.hostname + (':%d' % u.port if u.port else '')
        req = ('GET %s HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n'
               'Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\nUser-Agent: laura-voice-linux\r\n\r\n') % (path, host, key)
        self.sock.sendall(req.encode())
        head = b''
        while b'\r\n\r\n' not in head:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise HandshakeError('eof')
            head += chunk
        head, self.buf = head.split(b'\r\n\r\n', 1)
        lines = head.decode('latin-1').split('\r\n')
        status = lines[0].split(' ')[1] if ' ' in lines[0] else '?'
        if status != '101':
            self.sock.close()
            raise HandshakeError(status)
        headers = dict((k.strip().lower(), v.strip()) for k, v in (l.split(':', 1) for l in lines[1:] if ':' in l))
        want = base64.b64encode(hashlib.sha1((key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
        if headers.get('sec-websocket-accept') != want:
            self.sock.close()
            raise HandshakeError('bad accept')
        self.sock.settimeout(None)
        self.closed = False

    def _send(self, op, data):
        n = len(data)
        if n < 126:
            hdr = struct.pack('!BB', 0x80 | op, 0x80 | n)
        elif n < 65536:
            hdr = struct.pack('!BBH', 0x80 | op, 0x80 | 126, n)
        else:
            hdr = struct.pack('!BBQ', 0x80 | op, 0x80 | 127, n)
        mask = os.urandom(4)
        if n:
            m = (mask * (n // 4 + 1))[:n]
            data = (int.from_bytes(data, 'big') ^ int.from_bytes(m, 'big')).to_bytes(n, 'big')
        with self.lock:
            if self.closed:
                raise Closed(1006)
            self.sock.sendall(hdr + mask + data)

    def send_text(self, s):
        self._send(1, s.encode('utf-8'))

    def send_binary(self, b):
        self._send(2, bytes(b))

    def _read(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise Closed(1006, 'eof')
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def recv(self):
        """Returns str for text, bytes for binary; raises Closed."""
        parts, first_op = [], None
        while True:
            b0, b1 = self._read(2)
            fin, op, n = b0 & 0x80, b0 & 0x0F, b1 & 0x7F
            if n == 126:
                n = struct.unpack('!H', self._read(2))[0]
            elif n == 127:
                n = struct.unpack('!Q', self._read(8))[0]
            mask = self._read(4) if b1 & 0x80 else None
            data = self._read(n)
            if mask:
                data = bytes(c ^ mask[i % 4] for i, c in enumerate(data))
            if op == 8:
                code = struct.unpack('!H', data[:2])[0] if len(data) >= 2 else 1005
                try:
                    self._send(8, data[:2])
                except Exception:
                    pass
                self.close()
                raise Closed(code, data[2:].decode('utf-8', 'replace'))
            if op == 9:
                self._send(10, data)
                continue
            if op == 10:
                continue
            if op in (1, 2):
                first_op, parts = op, [data]
            else:
                parts.append(data)
            if fin:
                payload = b''.join(parts)
                return payload.decode('utf-8') if first_op == 1 else payload

    def close(self, code=None):
        with self.lock:
            if self.closed:
                return
            if code is not None:
                try:
                    self.sock.sendall(struct.pack('!BBH', 0x88, 0x82, 0) + b'\0\0' + struct.pack('!H', code))
                except Exception:
                    pass
            self.closed = True
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except Exception:
            pass
        self.sock.close()


# ---- Audio in/out ----

class CommandSource:
    def __init__(self, cmd):
        self.cmd = cmd
        self.proc = None

    def frames(self):
        while True:
            self.proc = subprocess.Popen(self.cmd, shell=True, stdout=subprocess.PIPE)
            while True:
                b = self.proc.stdout.read(FRAME * 2)
                if len(b) < FRAME * 2:
                    break
                yield b
            log('capture ended (exit %s), restarting' % self.proc.wait())
            time.sleep(1)

    def stop(self):
        if self.proc:
            self.proc.kill()


class WavSource:
    """Streams a 16 kHz mono WAV in real time, then silence (test input)."""

    def __init__(self, path, lead_s=0.5):
        w = wave.open(path, 'rb')
        if (w.getframerate(), w.getnchannels(), w.getsampwidth()) != (RATE, 1, 2):
            raise SystemExit('%s: need 16 kHz mono 16-bit (ffmpeg -i in -ar 16000 -ac 1 out.wav)' % path)
        self.pcm = b'\0' * int(lead_s * RATE) * 2 + w.readframes(w.getnframes())
        w.close()
        self.done = threading.Event()

    def frames(self):
        t = time.time()
        for i in range(0, len(self.pcm), FRAME * 2):
            yield self.pcm[i:i + FRAME * 2].ljust(FRAME * 2, b'\0')
            t += FRAME / RATE
            time.sleep(max(0, t - time.time()))
        self.done.set()
        while True:
            yield b'\0' * FRAME * 2
            t += FRAME / RATE
            time.sleep(max(0, t - time.time()))

    def stop(self):
        pass


class CommandSink:
    LATENCY = 0.15

    def __init__(self, cmd):
        self.cmd = cmd
        self.proc = None
        self.rate = None
        self.busy_until = 0.0
        self.played_s = 0.0
        self.turn_s = 0.0

    def new_turn(self):
        self.turn_s = 0.0

    def played_ms(self):
        """Reply audio of the current turn that has come out of the speaker (estimated from what was written)."""
        return max(0, round((self.turn_s - max(0.0, self.busy_until - time.time())) * 1000))

    def write(self, pcm, rate):
        if self.proc is None or self.proc.poll() is not None or rate != self.rate:
            self.stop()
            self.rate = rate
            self.proc = subprocess.Popen(self.cmd.format(rate=rate), shell=True, stdin=subprocess.PIPE)
        dur = len(pcm) / 2.0 / rate
        self.busy_until = max(self.busy_until, time.time() + self.LATENCY) + dur
        self.played_s += dur
        self.turn_s += dur
        try:
            self.proc.stdin.write(pcm)
            self.proc.stdin.flush()
        except (BrokenPipeError, OSError):
            self.stop()

    def playing(self):
        return time.time() < self.busy_until

    def stop(self):
        self.busy_until = 0.0
        if self.proc:
            try:
                self.proc.kill()
                self.proc.wait()
            except Exception:
                pass
            self.proc = None


class WavSink(CommandSink):
    def __init__(self, path):
        CommandSink.__init__(self, '')
        self.path = path
        self.w = None

    def write(self, pcm, rate):
        if self.w is None:
            self.w = wave.open(self.path, 'wb')
            self.w.setnchannels(1)
            self.w.setsampwidth(2)
            self.w.setframerate(rate)
        dur = len(pcm) / 2.0 / rate
        self.busy_until = max(self.busy_until, time.time()) + dur
        self.played_s += dur
        self.turn_s += dur
        self.w.writeframes(pcm)

    def stop(self):
        self.busy_until = 0.0

    def finish(self):
        if self.w:
            self.w.close()
            self.w = None


# ---- Speech gate (same rules as the web and Android clients) ----

class Gate:
    def __init__(self, gate_min=0.002):
        self.min = gate_min
        self.floor = gate_min
        self.last_loud = -1e9
        self.open = False
        self.pre = []

    def push(self, pcm, rms, now):
        """Returns the frames to send for this input frame."""
        self.floor = max(0.0005, min(rms, self.floor * 1.01))
        if rms > max(self.min, self.floor * 2.5):
            self.last_loud = now
        want = now - self.last_loud < GATE_HANG
        if want and not self.open:
            self.open = True
            out, self.pre = self.pre + [pcm], []
            return out
        if not want and self.open:
            self.open = False
        if self.open:
            return [pcm]
        self.pre = (self.pre + [pcm])[-GATE_PRE_FRAMES:]
        return []

    def reset(self):
        self.open = False
        self.pre = []


# ---- Client ----

class Client:
    def __init__(self, cfg, source, sink, once=False):
        self.cfg = cfg
        self.source = source
        self.sink = sink
        self.once = once
        self.ws = None
        self.state = 'idle'
        self.muted = False
        self.resume = threading.Event()
        self.finished = threading.Event()
        self.exit_code = 0
        self.gate = Gate(float(cfg['gate_min']))
        self.half_duplex = cfg['half_duplex'].lower() in ('1', 'yes', 'true', 'on')
        self.barge_rms = float(cfg['barge_rms'])
        self.loud = []
        self.quiet_until = 0.0
        self.opus = cfg['codec'] == 'opus' and Opus.load()
        if cfg['codec'] == 'opus' and not self.opus:
            log('libopus not found, using PCM16')
        self.enc = None
        self.dec = None
        self.play_turn = None
        self.play_rate = RATE
        self.play_codec = 'pcm'
        self.dropped = set()
        self.stats = dict(up=0, down=0, up_total=0, down_total=0, up_kbps=0, down_kbps=0, rtt=None, turn_ms=None,
                          barge_ins=0, dec_errors=0)
        self.turn_t0 = None
        self.got_reply = False

    # -- connection --

    def url(self):
        u = urlsplit(self.cfg['url'])
        q = dict(parse_qsl(u.query))
        if self.cfg['key']:
            q['k'] = self.cfg['key']
        path = u.path if u.path and u.path != '/' else '/ws'
        return '%s://%s%s?%s' % (u.scheme, u.netloc, path, urlencode(q))

    def send(self, msg):
        ws = self.ws
        if ws is None:
            return
        data = json.dumps(msg, ensure_ascii=False)
        self.count('up', len(data))
        try:
            ws.send_text(data)
        except Exception:
            pass

    def send_audio(self, pcm):
        ws = self.ws
        if ws is None:
            return
        try:
            if self.opus:
                if self.enc is None:
                    self.enc = OpusEncoder()
                for i in range(0, len(pcm), OPUS_FRAME * 2):
                    pkt = self.enc.encode(pcm[i:i + OPUS_FRAME * 2])
                    self.count('up', len(pkt))
                    ws.send_binary(pkt)
            else:
                self.count('up', len(pcm))
                ws.send_binary(pcm)
        except Exception:
            pass

    def count(self, d, n):
        self.stats[d] += n
        self.stats[d + '_total'] += n

    def run(self):
        threading.Thread(target=self.capture_loop, daemon=True).start()
        threading.Thread(target=self.tick_loop, daemon=True).start()
        retry = 0
        while not self.finished.is_set():
            try:
                self.ws = WebSocket(self.url(), self.cfg['pin_sha256'], self.cfg['ca_file'])
            except HandshakeError as e:
                if e.status in ('403', '401'):
                    log('rejected (HTTP %s): check the access key' % e.status)
                    return 2
                log(e)
                self.backoff(retry)
                retry += 1
                continue
            except ssl.SSLError as e:
                log('TLS: %s' % e)
                if 'pin mismatch' in str(e):
                    return 3
                self.backoff(retry)
                retry += 1
                continue
            except OSError as e:
                log('connect failed: %s' % e)
                self.backoff(retry)
                retry += 1
                continue
            retry = 0
            self.enc = None
            self.hello()
            code = self.read_loop()
            self.ws = None
            self.sink.stop()
            self.gate.reset()
            self.state = 'idle'
            if self.finished.is_set():
                break
            if code == BAD_KEY:
                log('bad access key (4401)')
                return 2
            if code == TAKEOVER:
                log('in use on another device; waiting (SIGUSR1 to take it back)')
                wait = float(self.cfg['takeover_resume_s'])
                self.resume.clear()
                self.resume.wait(wait if wait > 0 else None)
                continue
            log('disconnected (%s), reconnecting' % code)
            self.backoff(0)
        return self.exit_code

    def backoff(self, n):
        time.sleep(min(10.0, 0.5 * 2 ** n) * (0.75 + random.random() * 0.5))

    def hello(self):
        ctx = {'device': self.cfg['device']} if self.cfg['device'] else {}
        audio = {'up': 'opus' if self.opus else 'pcm', 'down': ['opus', 'pcm'] if self.opus else ['pcm']}
        self.send({'type': 'hello', 'version': 1, 'client': 'linux', 'lang': self.cfg['lang'], 'mode': 'handsfree',
                   'context': ctx, 'audio': audio})
        self.stats['codecs'] = '%s/%s' % (audio['up'], audio['down'][0])
        log('connected, audio %s' % self.stats['codecs'])

    def read_loop(self):
        ws = self.ws
        try:
            while True:
                m = ws.recv()
                self.count('down', len(m))
                if isinstance(m, bytes):
                    self.on_audio(m)
                else:
                    try:
                        self.on_message(json.loads(m))
                    except ValueError:
                        pass
        except Closed as e:
            return e.code
        except Exception as e:
            log('connection error: %s' % e)
            ws.close()
            return 1006

    def stop(self):
        self.finished.set()
        self.resume.set()
        if self.ws:
            self.ws.close(1000)

    # -- server messages --

    def on_message(self, m):
        t = m.get('type')
        if t == 'session':
            log('session %s' % m.get('id'))
        elif t == 'state':
            self.state = m.get('value')
        elif t == 'user_turn':
            log('you (%s): %s' % (m.get('lang'), m.get('text')))
            self.turn_t0 = time.time()
        elif t == 'assistant_done':
            log('laura: %s' % m.get('text'))
        elif t == 'audio_start':
            self.play_turn = m.get('turn_id')
            self.sink.new_turn()
            self.play_rate = int(m.get('sample_rate') or RATE)
            self.play_codec = m.get('codec') or 'pcm'
            self.dec = OpusDecoder(self.play_rate) if self.play_codec == 'opus' else None
        elif t == 'audio_end':
            if self.once and m.get('turn_id') == self.play_turn:
                self.got_reply = True
                threading.Thread(target=self.finish_once, daemon=True).start()
        elif t == 'metrics':
            self.stats['server'] = m
            log('metrics: asr %s ms, llm first %s ms, tts first %s ms, server first audio %s ms, client reply %s ms' % (
                m.get('asr_ms'), m.get('llm_first_ms'), m.get('tts_first_ms'), m.get('server_first_audio_ms'),
                self.stats['turn_ms']))
        elif t == 'pong':
            try:
                self.stats['rtt'] = int(time.time() * 1000 - float(m.get('t')))
            except (TypeError, ValueError):
                pass
        elif t == 'error':
            log('server error: %s' % m.get('message'))
        elif t == 'ignored':
            log('ignored (%s): %s' % (m.get('reason'), m.get('text') or ''))
        elif t == 'interrupted':
            if m.get('turn_id') == self.play_turn and m.get('turn_id') not in self.dropped:
                self.send({'type': 'playback', 'turn_id': self.play_turn, 'played_ms': self.sink.played_ms(),
                           'state': 'stopped'})
                self.sink.stop()
            self.dropped.add(m.get('turn_id'))
        elif t == 'muted':
            log('server mute: %s' % m.get('value'))
        elif t == 'task':
            log('task %s: %s (%s)' % (m.get('task_id'), m.get('title'), m.get('state')))
        elif t == 'laura_message':
            log('laura (task %s): %s' % (m.get('task_id'), m.get('text')))
        elif t == 'approval_request':
            # A speaker has no screen to tap, and approvals must never come from speech.
            log('approval needed on another device: %s' % (m.get('description') or m.get('text')))

    def finish_once(self):
        while self.sink.playing():
            time.sleep(0.05)
        self.stop()

    def on_audio(self, data):
        if self.play_turn in self.dropped or len(data) < 2:
            return
        if self.turn_t0 is not None:
            self.stats['turn_ms'] = int((time.time() - self.turn_t0) * 1000)
            self.turn_t0 = None
        if self.play_codec == 'opus':
            try:
                data = self.dec.decode(data)
            except Exception:
                self.stats['dec_errors'] += 1
                return
        self.sink.write(data, self.play_rate)

    # -- microphone --

    def capture_loop(self):
        for pcm in self.source.frames():
            if self.finished.is_set():
                break
            try:
                self.on_frame(pcm)
            except Exception as e:
                log('frame error: %s' % e)

    def on_frame(self, pcm):
        now = time.time()
        rms = rms16(pcm)
        playing = self.sink.playing()
        if self.muted or self.ws is None:
            self.gate.reset()
            return
        if self.half_duplex:
            if playing or self.state == 'speaking':
                self.quiet_until = now + 0.6
            if now < self.quiet_until:
                self.gate.reset()
                self.loud = []
                return
        for f in self.gate.push(pcm, rms, now):
            self.send_audio(f)
        if playing:
            self.loud = (self.loud + [rms > self.barge_rms])[-BARGE_WINDOW:]
            if sum(self.loud) >= BARGE_FRAMES:
                self.barge_in()
        else:
            self.loud = []

    def barge_in(self):
        self.stats['barge_ins'] += 1
        self.loud = []
        played = self.sink.played_ms() if self.play_turn else None
        if self.play_turn:
            self.dropped.add(self.play_turn)
        self.sink.stop()
        self.send({'type': 'interrupt', 'turn_id': self.play_turn, 'played_ms': played})
        log('barge-in')

    def tick_loop(self):
        tick = 0
        while not self.finished.is_set():
            time.sleep(1)
            tick += 1
            s = self.stats
            s['up_kbps'], s['down_kbps'] = s['up'] * 8 // 1000, s['down'] * 8 // 1000
            s['up'] = s['down'] = 0
            if self.ws is None:
                continue
            if tick % 2 == 0:
                self.send({'type': 'ping', 't': int(time.time() * 1000)})
            if tick % 5 == 0:
                self.send({'type': 'client_stats', 'rtt_ms': s['rtt'], 'up_kbps': s['up_kbps'], 'down_kbps': s['down_kbps'],
                           'up_mb': round(s['up_total'] / 1e6, 2), 'down_mb': round(s['down_total'] / 1e6, 2),
                           'turn_ms': s['turn_ms'], 'codecs': s.get('codecs'), 'gate_open': self.gate.open,
                           'played_s': round(self.sink.played_s, 1), 'barge_ins': s['barge_ins'],
                           'dec_errors': s['dec_errors'], 'half_duplex': self.half_duplex})


def load_config(path):
    cp = configparser.ConfigParser()
    cp['laura-voice'] = DEFAULTS
    if path:
        if not cp.read(path):
            raise SystemExit('cannot read %s' % path)
    cfg = dict(cp['laura-voice'])
    for k in DEFAULTS:
        env = os.environ.get('LAURA_VOICE_' + k.upper())
        if env is not None:
            cfg[k] = env
    return cfg


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if argv[:1] == ['fingerprint']:
        if len(argv) != 2:
            raise SystemExit('usage: laura_voice.py fingerprint wss://host:port')
        u = urlsplit(argv[1])
        ctx = tls_context('x', '')
        with socket.create_connection((u.hostname, u.port or 443), timeout=10) as raw:
            with ctx.wrap_socket(raw, server_hostname=u.hostname) as s:
                print('sha256/' + hashlib.sha256(s.getpeercert(True)).hexdigest())
        return 0
    p = argparse.ArgumentParser(description='laura-voice headless client')
    p.add_argument('-c', '--config', default=os.environ.get('LAURA_VOICE_CONFIG', ''))
    p.add_argument('--wav', help='use a 16 kHz mono WAV as the microphone (test)')
    p.add_argument('--out', help='write reply audio to this WAV instead of playing it')
    p.add_argument('--once', action='store_true', help='exit after the first spoken reply')
    p.add_argument('--timeout', type=float, default=0, help='give up after this many seconds')
    a = p.parse_args(argv)
    cfg = load_config(a.config)
    if not cfg['url']:
        raise SystemExit('no url configured')
    source = WavSource(a.wav) if a.wav else CommandSource(cfg['capture'])
    sink = WavSink(a.out) if a.out else CommandSink(cfg['playback'])
    client = Client(cfg, source, sink, once=a.once)

    def on_term(*_):
        client.stop()

    def on_mute(*_):
        client.muted = not client.muted
        log('muted' if client.muted else 'unmuted')

    signal.signal(signal.SIGTERM, on_term)
    signal.signal(signal.SIGINT, on_term)
    signal.signal(signal.SIGUSR1, lambda *_: client.resume.set())
    signal.signal(signal.SIGUSR2, on_mute)
    if a.timeout:
        timer = threading.Timer(a.timeout, lambda: (setattr(client, 'exit_code', 4), client.stop()))
        timer.daemon = True
        timer.start()
    try:
        code = client.run()
    finally:
        source.stop()
        sink.stop()
        if isinstance(sink, WavSink):
            sink.finish()
    if a.once and not client.got_reply and not code:
        code = 4
    return code


if __name__ == '__main__':
    sys.exit(main())
