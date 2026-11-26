"""Voice assistant backend: one WebSocket conversation at a time.

Audio in (Opus or PCM16 16 kHz) -> Silero VAD turn detection -> Nemotron streaming ASR (server/stt, level
control) + speaker verification (TitaNet) -> addressee gate -> Bonsai 2 9B via llama-server (streamed) ->
sentence-by-sentence Supertonic TTS -> Opus/PCM audio out. Protocol: docs/protocol.md.
"""
import asyncio
from contextlib import aclosing
import json
import logging
import os
import secrets
import re
import time
import uuid
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import httpx
import numpy as np
import torch
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.staticfiles import StaticFiles
from silero_vad import load_silero_vad

import addressee
import interrupts
import speaker
from stt import Leveler, load_recognizer
from laura_link import CONTROL_TYPES as LAURA_CONTROLS, ENABLED as LAURA_ENABLED, LINK as LAURA, mount as mount_laura
from speech_text import speakable

log = logging.getLogger("assistant")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

LLM_URL = os.environ.get("LLM_URL", "http://127.0.0.1:8090/v1/chat/completions")
LLM_MODEL = os.environ.get("LLM_MODEL", "bonsai-9b-voice")
LOCAL_SOURCE = {"engine": "local", "model": os.environ.get("LLM_LABEL") or re.sub(r"-voice$", "", LLM_MODEL)}
SOURCE_TYPES = ("assistant_delta", "assistant_done", "audio_start")
TTS_URL = os.environ.get("TTS_URL", "http://127.0.0.1:8091/synth")
CLIENT_DIR = Path(os.environ.get("CLIENT_DIR", str(Path(__file__).parent / "client")))
ACCESS_KEY = os.environ.get("ASSISTANT_KEY", "")
SR = 16000
VAD_WIN = 512                 # Silero window at 16 kHz (32 ms)
START_PROB, END_PROB = 0.5, 0.35
MIN_SPEECH_S = 0.25           # ignore blips shorter than this
END_SILENCE_S = 0.7           # silence that ends a hands-free turn
PREROLL_S = 0.3               # audio kept from before speech onset
FRAME_GAP_END_S = 1.5         # no audio at all mid-turn (the client's speech gate closed): the turn is over
TURN_CAP_S = 45.0             # a hands-free turn never stays open longer than this
BUSY_RESET_S = 60.0           # a reply still running after this long is stuck: reset the session
MAX_HISTORY = 12              # messages kept for the LLM

TTS_VOICE = os.environ.get("TTS_VOICE", "F1")
TTS_STEPS = int(os.environ.get("TTS_STEPS", "12"))
TTS_SPEED = float(os.environ.get("TTS_SPEED", "1.0"))
TTS_STREAM = os.environ.get("TTS_STREAM", "") == "1"           # POST <TTS_URL>_stream, play chunks as they come
USER_NAME = os.environ.get("ASSISTANT_USER", "Hector")
NAME = os.environ.get("ASSISTANT_NAME", "Laura")
HOME = os.environ.get("ASSISTANT_HOME", "Daegu, South Korea")
DATA_DIR = Path(os.environ.get("ASSISTANT_DATA", Path.home() / ".local/share/laura-voice"))
PROFILE_PATH = DATA_DIR / "voice_profile.npz"
SPEAKER_MODEL = os.environ.get("SPEAKER_MODEL", "titanet_small")
SPEAKER_DEVICE = os.environ.get("SPEAKER_DEVICE", "cuda")
ASR_DEVICE = os.environ.get("ASR_DEVICE", "cuda")
SPEAKER_THRESHOLD = float(os.environ.get("SPEAKER_THRESHOLD", "0.4"))
SHORT_TURN_S, SHORT_TURN_SLACK = 1.2, 0.08   # very short turns score lower for everyone
RECALL_KEEP, RECALL_MAX_AGE_S, RECALL_JOIN_S = 3, 90.0, 20.0
RECALL_LOG = DATA_DIR / "recalls.jsonl"     # missed turns Hector had to point out: future classifier tuning data
OTHERS_MEMORY_S = 120.0       # a non-owner voice heard this recently means "others present"
BARGE_VERIFY_S = 0.8          # speech needed before a barge-in is verified as the owner
BARGE_ONSET_S = float(os.environ.get("BARGE_ONSET_S", "0.096"))      # voice over a reply this long: duck
BARGE_QUICK_S = float(os.environ.get("BARGE_QUICK_S", "0.3"))        # first look at what is being said
BARGE_QUICK_EVERY_S = 0.2
UNDUCK_SILENCE_S = 0.35
DUCK_DB = -12
RESUME_MAX_AGE_S = 300.0
CUT_REPORT_WAIT_S = 0.6
BARGE_CONTENT_S = 0.6         # a one-word look commits only after this much speech
ECHO_MARGIN_DB = float(os.environ.get("ECHO_MARGIN_DB", "10"))       # how long to wait for the client's playback report before estimating
MAX_TURN_S = 30.0
WAKE_HOLD_S = 3.0             # a wake flag applies to a turn starting within this long


REACTIONS = ("nod", "shake", "happy", "laugh", "confused", "thinking_hard", "surprised", "sad", "shy", "excited",
             "sleepy", "wave", "celebrate", "shrug", "focus")
EMOTIONS = ("happy", "confused", "apologetic", "none")
# thinking_hard is accepted but not offered: a leading "<think..." tag pulls this model into writing its reasoning
AVATAR_TAGS = os.environ.get("AVATAR_TAGS", "1") != "0"
LEAD_TAG = re.compile(r"\s*<(?:([re]):)?\s*([A-Za-z_]+)\s*>")
ANY_TAG = re.compile(r"</?(?:[re]:)?\s*[A-Za-z_]+\s*(?:>|$)")    # stray or closing tags anywhere
TAG_WAIT_CHARS = 14
SHORT_ONLY_REACTIONS, SHORT_REPLY_WORDS = ("focus", "shrug"), 12   # a shrug means "I don't know", not decoration


def system_prompt(context: dict, avatar=AVATAR_TAGS) -> str:
    now = datetime.now(ZoneInfo("Asia/Seoul"))
    ctx = dict(context or {})
    driving = ctx.pop("car", False)
    lines = [
        f"You are {NAME}, {USER_NAME}'s private voice assistant. You're talking with {USER_NAME} out loud; always "
        "speak to him as \"you\" (never \"he\" or \"his\").",
        f"You run on {USER_NAME}'s own laptop at home in {HOME}: a small local model with no internet and no tools "
        f"yet. You can't see {USER_NAME}'s calendar, email, files or location, or today's weather and news.",
        "Only mention those limits when a request actually needs them. Everything else, answer from what you know: "
        "facts, ideas, explanations, advice, jokes, stories. Example: for \"Tell me something interesting\", share "
        "one surprising fact you know, about animals, space, history, food or language, never a disclaimer.",
        "You're not doing anything in the background. If you're asked what you're doing, you're just here talking "
        f"with {USER_NAME}. Never invent activities, memories or experiences.",
        f"Right now it is {now:%A, %B} {now.day}, {now.year}, {now:%-I:%M %p} Korea time.",
        f"{USER_NAME} may be using a phone or watch; don't assume what he's doing.",
        "Everything you write is read aloud by a speech synthesizer, so write exactly what should be heard:",
        "- Reply in the language of the message, Korean or English. In Korean, use natural polite speech (해요체).",
        "- Usually one or two short sentences; more only when asked for detail.",
        "- Sound warm and relaxed, like a capable friend, not a customer-service script. Don't repeat the question back.",
        "- No lists, headings, markdown, emoji, URLs, code or parentheses.",
        "- Write numbers, times, units and symbols as they are spoken: three point five, half past three, 삼 점 오, 세 시 반.",
        f"- Never invent prices, weather, news or personal facts about {USER_NAME}; if you're unsure of a fact, say so briefly.",
        "- If the transcript looks garbled or misheard, ask to hear it again.",
        interrupts.INTERRUPT_RULES,
    ]
    if avatar:
        lines += [
            "You have a body on the phone screen: you are a small floating cloud of glowing see-through pixel cubes, "
            "with pixel eyes, a little mouth, blush, two tiny cube hands and a few cubes orbiting you. You breathe "
            "and blink when idle, lean in when you're spoken to, your cubes spin into a crown while you think, you "
            "pulse as you speak, you shrug when it wasn't meant for you, you go quiet and grey for voices you don't "
            "know, and you sleep when muted. It is you, so speak of it as I/me/my, and only when asked. Example: "
            "\"What do you look like?\" -> \"I'm a little floating cloud of glowing cubes, with pixel eyes and tiny "
            "cube hands.\"",
            "Most replies need no tag. When a reaction clearly fits, start with one tag like <r:nod>, from: "
            + ", ".join(r for r in REACTIONS if r != "thinking_hard") + ". The tag is silent; never put tags "
            "anywhere else.",
        ]
    if driving:
        lines.append(f"{USER_NAME} is driving right now: keep answers especially brief and never ask {USER_NAME} to look at "
                     "the screen." + (" Don't use any tags." if avatar else ""))
    if ctx:
        lines.append(f"Context from {USER_NAME}'s devices: " + json.dumps(ctx, ensure_ascii=False))
    return "\n".join(lines)

app = FastAPI()
if LAURA_ENABLED:
    mount_laura(app)
torch.set_num_threads(int(os.environ.get("TORCH_THREADS", "2")))
ASR = {}


@app.on_event("startup")
async def startup():
    rec = await asyncio.to_thread(load_recognizer, ASR_DEVICE)
    if ASR_DEVICE == "cuda":
        torch.cuda.empty_cache()
    ASR.update(rec=rec, vad=load_silero_vad())
    log.info("ASR ready")
    try:
        ASR["spk"] = await asyncio.to_thread(speaker.SpeakerModel, SPEAKER_MODEL, SPEAKER_DEVICE)
        ASR["profile"] = speaker.VoiceProfile.load(PROFILE_PATH)
        log.info("speaker model %s on %s, profile %s", SPEAKER_MODEL, SPEAKER_DEVICE,
                 "loaded" if ASR["profile"] else "not enrolled")
    except Exception:  # noqa: BLE001
        log.exception("speaker verification unavailable")
        ASR["spk"], ASR["profile"] = None, None
    asyncio.create_task(warm_up())


async def warm_up():
    """First requests after a start are slow (CUDA kernels, TTS graphs, LLM prompt cache): pay that up front."""
    noise = (np.random.default_rng(0).standard_normal(SR * 3) * 0.01).astype(np.float32)
    try:
        await asyncio.to_thread(ASR["rec"].transcribe, noise, "auto")
        if ASR.get("spk"):
            await asyncio.to_thread(ASR["spk"].embed, noise)
        async with httpx.AsyncClient(timeout=60) as http:
            for lang, text in (("en", "Okay."), ("ko", "네.")):
                await http.post(TTS_URL, json={"text": text, "lang": lang, "voice": TTS_VOICE, "steps": TTS_STEPS})
            await addressee.AddresseeClassifier(http, LLM_URL, LLM_MODEL, USER_NAME, NAME).p_addressed("warm up")
        log.info("warm-up done")
    except Exception as e:  # noqa: BLE001
        log.warning("warm-up failed: %s", e)


SCRIPT_JUNK = re.compile(r"[Ѐ-ӿ؀-ۿऀ-ॿ぀-ヿ一-鿿]+")


def clean(text):
    """Auto-language mode can emit a few words in the wrong script at utterance start; keep Hangul/Latin."""
    return re.sub(r"\s+", " ", SCRIPT_JUNK.sub(" ", text)).strip()


def lang_of(text):
    hangul = len(re.findall(r"[가-힣]", text))
    latin = len(re.findall(r"[A-Za-z]", text))
    return "ko" if hangul >= latin * 0.5 and hangul > 0 else "en"


class OpusIn:
    """Raw Opus packets from the client (WebCodecs, one packet per message) -> float32 mono 16 kHz."""

    def __init__(self):
        import av
        self.av = av
        self.dec = av.CodecContext.create("opus", "r")
        self.dec.sample_rate = 48000
        self.dec.layout = "mono"
        self.rs = av.AudioResampler(format="s16", layout="mono", rate=SR)

    def decode(self, data: bytes) -> np.ndarray:
        out = []
        for fr in self.dec.decode(self.av.Packet(data)):
            for g in self.rs.resample(fr):
                out.append(g.to_ndarray().reshape(-1))
        return (np.concatenate(out).astype(np.float32) / 32768.0) if out else np.zeros(0, np.float32)


class AudioOut:
    """One reply's audio stream: TTS PCM16 -> Opus 24 kbps packets (20 ms) or PCM16 16 kHz in 100 ms pieces."""

    def __init__(self, codec: str):
        import av
        self.av = av
        self.codec = codec
        self.rate = 24000 if codec == "opus" else SR
        self.rs = av.AudioResampler(format="s16", layout="mono", rate=self.rate)
        if codec == "opus":
            self.enc = av.CodecContext.create("libopus", "w")
            self.enc.sample_rate, self.enc.layout, self.enc.format, self.enc.bit_rate = self.rate, "mono", "s16", 24000

    def packets(self, pcm: bytes, src_rate: int) -> list[bytes]:
        f = self.av.AudioFrame.from_ndarray(np.frombuffer(pcm, "<i2").reshape(1, -1), format="s16", layout="mono")
        f.sample_rate = src_rate
        frames = self.rs.resample(f)
        if self.codec == "opus":
            return [bytes(p) for fr in frames for p in self.enc.encode(fr)]
        x = np.concatenate([fr.to_ndarray().reshape(-1) for fr in frames]) if frames else np.zeros(0, np.int16)
        step = self.rate // 10
        return [x[i:i + step].astype("<i2").tobytes() for i in range(0, x.size, step)]

    def flush(self) -> list[bytes]:
        return [bytes(p) for p in self.enc.encode(None)] if self.codec == "opus" else []


LOOP = re.compile(r"(.{6,60}?)(?:\s*\1){2}", re.S)       # the same 6+ char chunk three times in a row
LAST_SENTENCE = re.compile(r".*(?:[.!?。！？]|[요다까죠](?=\s|$))", re.S)
SAMPLING = {"repeat_penalty": float(os.environ.get("LLM_REPEAT_PENALTY", "1.1")),
            "dry_multiplier": float(os.environ.get("LLM_DRY_MULTIPLIER", "0.8")), "dry_base": 1.75,
            "dry_allowed_length": 2}


def cut_loop(full, pending, window=400):
    """If the reply started looping, cut it back to the last complete sentence before the loop.
    -> (full, pending) or None. `pending` is the tail of `full` not yet sent to TTS."""
    start = max(0, len(full) - window)
    m = LOOP.search(full, start)
    if not m:
        return None
    spoken = len(full) - len(pending)
    keep = full[spoken:m.start()] if m.start() > spoken else ""
    s_ = LAST_SENTENCE.match(keep)
    keep = s_.group(0) if s_ else ""
    return full[:spoken] + keep, keep


SENT_END = re.compile(r"(.+?(?:[.!?。！？]+|[요다까죠네]\s|\n))(\s*)", re.S)
FIRST_CLAUSE = re.compile(r"(.{18,}?[,，])(\s+)", re.S)   # start speaking at the first comma of a long opener


class Session:
    def __init__(self, ws: WebSocket):
        self.ws = ws
        self.id = uuid.uuid4().hex[:8]
        self.mode = "handsfree"
        self.lang = "auto"
        self.context = {}
        self.history = []
        self.vad_buf = np.zeros(0, np.float32)
        self.preroll = []
        self.in_speech = False
        self.last_frame_t = 0.0
        self.speech_s = 0.0
        self.silence_s = 0.0
        self.utt = None
        self.agc = None
        self.last_partial = ""
        self.reply_task = None
        self.reply_turn = None
        self.last_reply_turn = None
        self.speaking = False
        self.http = httpx.AsyncClient(timeout=120)
        self.opus_in = None          # set from hello: client sends Opus packets instead of PCM16
        self.down_codec = "pcm"      # "opus" if the client can decode it
        self.out = None
        self.turn_audio = []         # raw audio of the current turn, for speaker verification
        self.turn_voiced = 0.0
        self.echo_db = None          # level of her residual echo on this device (dBFS), once seen
        self.bi = None               # barge-in in progress: speech over a reply, ducked until decided
        self.replies = {}            # turn_id -> interrupts.Reply (recent replies: segments, cut point)
        self.cut_tasks = {}
        self.resumable = None        # (Reply, char offset) that "go on" continues from
        self.gate_on = True
        self.gate = addressee.Gate(addressee.AddresseeClassifier(self.http, LLM_URL, LLM_MODEL, USER_NAME, NAME))
        self.others_seen = 0.0
        self.enroll = None
        self.turn_metrics = {}
        self.ignored = []            # recent not-addressed turns that "that was for you" can bring back
        self.reply_source = LOCAL_SOURCE
        self.wake_at, self.turn_wake = None, None   # client wake word: (time, score) pending; score of this turn

    async def send(self, **msg):
        if msg.get("type") in SOURCE_TYPES and "source" not in msg:
            msg["source"] = self.reply_source
        await self.ws.send_text(json.dumps(msg, ensure_ascii=False))

    async def state(self, value):
        await self.send(type="state", value=value)

    async def idle(self):
        await self.state("listening" if self.mode == "handsfree" else "idle")

    # ---- turn handling -------------------------------------------------
    def _start_turn(self):
        self.t_turn_start = time.time() - PREROLL_S
        self.turn_wake = None
        if self.wake_at and time.time() - self.wake_at[0] <= WAKE_HOLD_S:
            self.turn_wake = self.wake_at[1]
        self.wake_at = None
        self.utt = ASR["rec"].utterance(self.lang)
        self.agc = Leveler()
        self.last_partial = ""
        self.turn_audio, self.turn_voiced = list(self.preroll), 0.0
        for chunk in self.preroll:
            self.utt.feed(self.agc.apply(chunk))
        self.preroll = []

    async def _feed(self, frame, voiced=True):
        if sum(a.size for a in self.turn_audio) < MAX_TURN_S * SR:
            self.turn_audio.append(frame)
        self.turn_voiced += frame.size / SR if voiced else 0.0
        text = await asyncio.to_thread(self.utt.feed, self.agc.apply(frame))
        if text is not None and text != self.last_partial:
            self.last_partial = text
            await self.send(type="partial", text=clean(text))

    def _embed_turn(self, audio):
        if not ASR.get("spk") or not audio:
            return None
        t = time.time()
        emb = ASR["spk"].embed(np.concatenate(audio))
        self.turn_metrics["spk_ms"] = round((time.time() - t) * 1000)
        return emb

    async def _end_turn(self):
        if not self.utt:
            return
        tr, self.utt = self.utt, None
        self.t_turn_end = time.time()
        agc, voiced = self.agc, self.turn_voiced
        audio, self.turn_audio = self.turn_audio, []
        self.in_speech, self.speech_s, self.silence_s = False, 0.0, 0.0
        bi, self.bi = self.bi, None
        self.turn_metrics = {}
        t_end = time.time()
        profile = ASR.get("profile")
        need_emb = self.enroll is not None or (profile is not None and self.mode == "handsfree")

        def finish():
            tr.feed(agc.drain())
            return tr.close()

        async def nothing():
            return None

        text, emb = await asyncio.gather(asyncio.to_thread(finish),
                                         asyncio.to_thread(self._embed_turn, audio) if need_emb else nothing())
        self.asr_ms = round((time.time() - t_end) * 1000)
        text = clean(text)
        if self.enroll is not None:
            await self._enroll_clip(emb, voiced, text)
            return
        if bi is not None:
            rec = self.replies.get(bi["turn"])
            verdict = interrupts.classify(text, rec.text if rec else "")
            if bi["echo"] and verdict != "stop":
                fresh = interrupts.new_words(text, bi["text"])
                verdict = interrupts.classify(fresh, rec.text if rec else "", fresh=True) if fresh else "echo"
            if bi["echo"] and self.echo_db is not None and not bi.get("voice"):
                verdict = "echo"
            if bi["committed"]:
                self.turn_metrics["barge_stop_ms"] = bi.get("stop_ms")
                if verdict == "echo":
                    log.info("barge-in turned out to be echo: %r", text)
                    await self.send(type="ignored", turn_id=uuid.uuid4().hex[:8], reason="echo", text=text)
                    await self.idle()
                    return
            else:
                if verdict in ("backchannel", "echo", "empty") or len(text) < 2:
                    if bi["ducked"]:
                        await self.send(type="duck", turn_id=bi["turn"], on=False, gain_db=DUCK_DB)
                    log.info("over the reply, not an interruption (%s, %.2f s): %r", verdict, voiced, text)
                    if text:
                        await self.send(type="ignored", turn_id=uuid.uuid4().hex[:8],
                                        reason="echo" if verdict == "echo" else "backchannel", text=text)
                    await (self.state("speaking") if self.speaking else self.idle())
                    return
                log.info("barge-in commit at end of turn (%s): %r", verdict, text)
                await self.interrupt_reply("barge_in")
        if len(text) < 2:
            await self.idle()
            return
        turn_id = uuid.uuid4().hex[:8]
        info = {"voiced_s": round(voiced, 2)}
        if emb is not None and profile is not None:
            score = profile.score(emb)
            info["speaker_score"] = round(score, 3)
            th = profile.threshold - (SHORT_TURN_SLACK if voiced < SHORT_TURN_S else 0.0)
            if score < th:
                if voiced >= 1.0 and score < th - 0.05:
                    self.others_seen = time.time()
                log.info("turn %s ignored not_owner %s %r", turn_id, info, text)
                await self.send(type="ignored", turn_id=turn_id, reason="not_owner", text=text,
                                score=info["speaker_score"])
                await self.idle()
                return
        kind, extra = addressee.recall_intent(text)
        if kind and not self.gate.muted and self._recallable() and (
                kind == "sure" or addressee.mentions_name(text)
                or self.gate.in_window(getattr(self, "t_turn_start", None))):
            await self.recall(text, extra, "phrase_" + kind)
            return
        wake, self.turn_wake = self.turn_wake, None
        if wake is not None and addressee.lora_jargon(text):
            info["wake_jargon"] = wake                  # "...a new LoRA for that": let the gate decide
            wake = None
        if self.mode == "handsfree" and wake is not None and not self.gate.muted:
            ok, gi = True, {"why": "wake", "wake": wake}
            if addressee.command(text) == "mute":
                gi["command"] = "mute"
            info.update(gi)
        elif self.mode == "handsfree" and self.gate_on:
            others = profile is not None and time.time() - self.others_seen < OTHERS_MEMORY_S
            t = time.time()
            ok, gi = await self.gate.decide(text, self.context, others, started=getattr(self, "t_turn_start", None))
            info.update(gi, gate_ms=round((time.time() - t) * 1000))
        else:
            ok, gi = True, {"why": "ptt" if self.mode == "ptt" else "gate_off"}
            info.update(gi)
        self.turn_metrics.update({k: info[k] for k in ("gate_ms", "classifier_ms", "p", "speaker_score")
                                  if k in info})
        if not ok:
            reason = "muted" if gi["why"] == "muted" else "not_addressed"
            log.info("turn %s ignored %s %s %r", turn_id, reason, info, text)
            await self.send(type="ignored", turn_id=turn_id, reason=reason, text=text, why=gi["why"])
            if reason == "not_addressed":
                self.ignored = (self.ignored + [{"turn_id": turn_id, "text": text, "t": time.time(),
                                                 "why": gi["why"], "p": gi.get("p")}])[-RECALL_KEEP:]
            await self.idle()
            return
        if (self._recallable() and not gi.get("command") and len(text.split()) <= 8
                and gi["why"] in ("name", "window", "wake")):
            try:
                p_rc, _ = await self.gate.classifier.p_recall(text, self.ignored[-1]["text"])
            except Exception:  # noqa: BLE001
                p_rc = 0.0
            if p_rc >= 0.7:
                await self.recall(text, "", f"classifier_{p_rc:.2f}")
                return
        log.info("turn %s addressed %s %r", turn_id, info, text)
        self.gate.reply_end = max(self.gate.reply_end, time.time())
        await self.send(type="user_turn", turn_id=turn_id, text=text, lang=lang_of(text))
        await self.cancel_reply()
        await self._settle_cuts()
        cmd = gi.get("command")
        if not cmd and bi is not None and interrupts.is_hold(text):
            log.info("turn %s hold: %r", turn_id, text)       # "wait": she stopped; nothing to answer
            await self.idle()
            return
        if not cmd and (intent := interrupts.resume_intent(text)):
            say = self._resume_text(intent)
            if say:
                log.info("turn %s %s: %r", turn_id, intent, say[:80])
                self.reply_task = asyncio.create_task(self.reply(turn_id, text, canned=say, resume=intent))
                return
        if cmd:
            self.gate.muted = cmd == "mute"
            await self.send(type="muted", value=self.gate.muted)
            ko = lang_of(text) == "ko"
            say = {("mute", True): "네, 그만 들을게요.", ("mute", False): "Okay, I'll stop listening.",
                   ("resume", True): "네, 다시 듣고 있어요.", ("resume", False): "I'm listening again."}[(cmd, ko)]
            self.reply_task = asyncio.create_task(self.reply(turn_id, text, canned=say))
            return
        self.reply_task = asyncio.create_task(self.reply(turn_id, text))

    def _recallable(self):
        now = time.time()
        self.ignored = [x for x in self.ignored if now - x["t"] <= RECALL_MAX_AGE_S]
        return bool(self.ignored)

    async def recall(self, text="", extra="", how="button"):
        """Answer what the gate ignored: the latest ignored turn plus the ones just before it."""
        if not self._recallable():
            await self.send(type="recall", ok=False, reason="nothing_to_recall")
            return
        group = [self.ignored[-1]]
        for x in reversed(self.ignored[:-1]):
            if group[0]["t"] - x["t"] > RECALL_JOIN_S:
                break
            group.insert(0, x)
        self.ignored = [x for x in self.ignored if x not in group]
        question = " ".join(x["text"] for x in group) + (" " + extra if extra else "")
        turn_id = uuid.uuid4().hex[:8]
        log.info("turn %s recall (%s) of %s: %r", turn_id, how, [x["turn_id"] for x in group], question)
        try:
            RECALL_LOG.parent.mkdir(parents=True, exist_ok=True)
            with RECALL_LOG.open("a", encoding="utf-8") as f:
                for x in group:
                    f.write(json.dumps({"t": round(x["t"]), "text": x["text"], "why": x["why"], "p": x["p"],
                                        "recall": how, "recall_text": text, "label": 1}, ensure_ascii=False) + "\n")
        except OSError:
            log.exception("recall log")
        self.gate.reply_end = max(self.gate.reply_end, time.time())
        await self.send(type="recall", ok=True, turn_id=turn_id, recalled=[x["turn_id"] for x in group],
                        text=question)
        await self.send(type="user_turn", turn_id=turn_id, text=question, lang=lang_of(question))
        await self.cancel_reply()
        self.reply_task = asyncio.create_task(self.reply(turn_id, question))

    def playing(self):
        """Server still streaming a reply, or the client presumably still playing the last one."""
        return self.speaking or time.time() < self.gate.reply_end

    def _barge_turn(self):
        return self.reply_turn or self.last_reply_turn

    async def interrupt_reply(self, reason, played_ms=None, turn=None):
        turn = turn or self._barge_turn()
        at = time.time()
        rec = self.replies.get(turn)
        if rec is not None and rec.cut_at is None:
            rec.cut_at = at
        if self.playing() and turn:
            await self.send(type="interrupted", turn_id=turn, reason=reason)
        await self.cancel_reply()
        self.gate.reply_end = min(self.gate.reply_end, at)
        if rec is not None and (played_ms is not None or not rec.reported):
            if played_ms is not None:
                await self.apply_cut(rec, played_ms / 1000, True)
            elif rec.turn_id not in self.cut_tasks:
                self.cut_tasks[rec.turn_id] = asyncio.create_task(self._cut_fallback(rec, at))

    async def _cut_fallback(self, rec, at):
        """The client reports how much it played; old clients don't, so estimate from when audio was sent."""
        await asyncio.sleep(CUT_REPORT_WAIT_S)
        if not rec.reported:
            await self.apply_cut(rec, rec.estimate_played(at), False)

    async def apply_cut(self, rec, played_s, reported):
        rec.set_cut(min(played_s, rec.audio_s), reported)
        self.cut_tasks.pop(rec.turn_id, None)
        if rec.hist is not None:
            rec.hist["content"] = rec.tags + rec.history_text()
        if rec.was_cut():
            self.resumable = (rec, rec.cut)
        log.info("turn %s cut at %.2f/%.2f s (%s): heard %r, unsaid %r", rec.turn_id, rec.played_s, rec.audio_s,
                 "client" if reported else "estimate", rec.heard()[-60:], rec.unsaid()[:60])
        await self.send(type="reply_cut", turn_id=rec.turn_id, played_ms=round(rec.played_s * 1000),
                        heard=rec.heard(), reported=reported)

    @staticmethod
    def _played_ms(msg):
        v = msg.get("played_ms")
        return float(v) if isinstance(v, (int, float)) and v >= 0 else None

    async def _settle_cuts(self):
        for t in list(self.cut_tasks.values()):
            try:
                await t
            except Exception:  # noqa: BLE001
                pass

    # ---- two-stage barge-in: duck on voice onset, stop once it is clearly not a backchannel ---------
    async def _duck(self, on):
        bi = self.bi
        if bi is None or bi["ducked"] == on:
            return
        bi["ducked"] = on
        if on and bi.get("t_duck") is None:
            bi["t_duck"] = time.time()
        await self.send(type="duck", turn_id=bi["turn"], on=on, gain_db=DUCK_DB)

    async def _barge_start(self, onset):
        self.bi = {"turn": self._barge_turn(), "onset": onset, "ducked": False, "sil": 0.0, "next": BARGE_QUICK_S,
                   "quick": None, "verdict": None, "text": "", "spk": None, "owner": None, "committed": False,
                   "t_duck": None, "echo": False}
        await self._duck(True)

    def _learn_echo(self, audio):
        """Remember how loud her own echo is on this device: later onsets must be clearly louder than it."""
        x = audio[int(PREROLL_S * SR):]
        n = x.size // 320
        if n < 5:
            return
        db = 10 * np.log10((x[:n * 320].reshape(n, 320) ** 2).mean(1) + 1e-10)
        level = float(np.percentile(db, 95))
        self.echo_db = max(level, self.echo_db - 3.0) if self.echo_db is not None else level
        log.info("echo level %.1f dBFS", self.echo_db)

    def _own_text(self):
        rec = self.replies.get(self.bi["turn"]) if self.bi else None
        return rec.text_near(rec.estimate_played(time.time())) if rec else ""

    async def _barge_quick(self, audio):
        """Look at the speech so far with a one-shot transcription (streaming partials come only every ~1 s)."""
        bi = self.bi
        t = time.time()

        def run():
            agc = Leveler()
            x = np.concatenate([agc.apply(audio), agc.drain()])
            return ASR["rec"].transcribe(x, self.lang)

        voiced = self.turn_voiced
        text = clean(await asyncio.to_thread(run))
        if self.bi is not bi or bi["committed"]:
            return
        own = self._own_text()
        if bi["echo"]:
            fresh = interrupts.new_words(text, bi["text"])
            verdict = interrupts.classify(fresh, own, fresh=True) if fresh else "echo"
        else:
            verdict = interrupts.classify(text, own)
        if verdict == "content" and len(interrupts.content_words(text)) < 2 and voiced < BARGE_CONTENT_S:
            verdict = "unsure"                 # one word could still be a backchannel or her echo
        if verdict == "echo":
            if not bi["echo"]:
                self._learn_echo(audio)
                bi["echo"] = True
            if not bi.get("loud"):
                await self._duck(False)
        bi.update(text=text, verdict=verdict)
        log.info("barge-in look %.2f s speech: %r -> %s (%d ms)", voiced, text, verdict, (time.time() - t) * 1000)
        await self._barge_maybe_commit()

    async def _barge_verify(self, audio):
        bi = self.bi
        emb = await asyncio.to_thread(ASR["spk"].embed, audio)
        profile = ASR.get("profile")
        score = profile.score(emb) if profile else 1.0
        log.info("barge-in voice check %.3f", score)
        if self.bi is bi:
            bi["owner"] = profile is None or score >= profile.threshold - SHORT_TURN_SLACK
            await self._barge_maybe_commit()

    async def _barge_maybe_commit(self):
        bi = self.bi
        if bi is None or bi["committed"] or bi["verdict"] not in ("stop", "content"):
            return
        if bi["echo"] and self.echo_db is not None and not bi.get("voice"):
            return                             # nothing louder than her own echo has been heard
        if ASR.get("spk") and ASR.get("profile") and not bi["owner"]:
            return
        bi["committed"] = True
        now = time.time()
        log.info("barge-in commit %s %r: duck %s ms, stop %d ms after onset", bi["verdict"], bi["text"],
                 round((bi["t_duck"] - bi["onset"]) * 1000) if bi["t_duck"] else None, (now - bi["onset"]) * 1000)
        bi["stop_ms"] = round((now - bi["onset"]) * 1000)
        await self.interrupt_reply("barge_in")

    def _resume_text(self, intent):
        """Deterministic 'go on' / 'repeat that' from the last reply; None leaves it to the model."""
        last = self.replies.get(self.last_reply_turn)
        if intent == "continue":
            if not self.resumable or time.time() - self.resumable[0].t0 > RESUME_MAX_AGE_S:
                return None
            rec, off = self.resumable
            if not rec.complete:
                return None                  # cut before the model finished: it continues from the marker
            return rec.text[off:].strip() or None
        if last is None or time.time() - last.t0 > RESUME_MAX_AGE_S:
            return None
        if self.resumable and self.resumable[0] is last:
            span = last.current_sentence(self.resumable[1])
            if span:
                return last.text[span[0]:span[1]].strip()
        sentences = [last.text[s.c0:s.c1].strip() for s in last.segments if last.text[s.c0:s.c1].strip()]
        if not sentences:
            return None
        return " ".join(sentences) if len(sentences) <= 2 else sentences[-1]

    async def _barge_step(self, p, dt, win):
        bi = self.bi
        if bi is None or bi["committed"]:
            return
        bi["sil"] = bi["sil"] + dt if p < END_PROB else 0.0
        if bi["echo"] and self.echo_db is not None:
            # her echo is going on: only a voice clearly louder than it ducks her again
            loud = p >= START_PROB and 10 * np.log10(float((win ** 2).mean()) + 1e-10) >= self.echo_db + ECHO_MARGIN_DB
            bi["loud"] = bi.get("loud", 0.0) + dt if loud else 0.0
            bi["quiet"] = 0.0 if loud else bi.get("quiet", 0.0) + dt
            if bi["loud"] >= BARGE_ONSET_S:
                bi["voice"] = True
            if bi["loud"] >= BARGE_ONSET_S and not bi["ducked"]:
                await self._duck(True)
                bi["next"] = min(bi["next"], self.turn_voiced)
            elif bi["quiet"] >= UNDUCK_SILENCE_S:
                await self._duck(False)
        elif bi["sil"] >= UNDUCK_SILENCE_S:
            await self._duck(False)
        elif bi["sil"] == 0.0 and p >= START_PROB:
            await self._duck(True)
        if bi["sil"] < 0.1 and self.turn_voiced >= bi["next"] and (bi["quick"] is None or bi["quick"].done()):
            bi["next"] = self.turn_voiced + BARGE_QUICK_EVERY_S
            bi["quick"] = asyncio.create_task(self._barge_quick(np.concatenate(self.turn_audio)))
        if (bi["spk"] is None and ASR.get("spk") and ASR.get("profile")
                and self.turn_voiced >= BARGE_VERIFY_S):
            bi["spk"] = asyncio.create_task(self._barge_verify(np.concatenate(self.turn_audio)))

    async def watchdog(self):
        """End a turn whose audio stopped arriving (the server only ends turns on received silence), cap turn
        length, and reset a reply that never finishes. Runs for the life of the session."""
        busy_since = None
        while True:
            await asyncio.sleep(0.25)
            try:
                now = time.time()
                if self.bi is not None and self.bi["ducked"] and now - self.last_frame_t >= UNDUCK_SILENCE_S + 0.15:
                    await self._duck(False)         # the client stopped sending: don't leave her ducked
                if self.in_speech and self.utt is not None and (
                        now - self.last_frame_t >= FRAME_GAP_END_S
                        or now - getattr(self, "t_turn_start", now) >= TURN_CAP_S):
                    log.info("session %s: turn closed by watchdog (%.1f s since last audio)", self.id,
                             now - self.last_frame_t)
                    await self._end_turn()
                if self.reply_task is not None and not self.reply_task.done():
                    busy_since = busy_since or now
                    if now - busy_since >= BUSY_RESET_S:
                        log.warning("session %s: reply busy for %.0f s, resetting", self.id, now - busy_since)
                        await self.cancel_reply()
                        self.utt, self.in_speech, self.speech_s, self.silence_s = None, False, 0.0, 0.0
                        self.bi, busy_since = None, None
                        await self.idle()
                else:
                    busy_since = None
            except asyncio.CancelledError:
                raise
            except Exception:  # noqa: BLE001
                log.exception("session %s: watchdog", self.id)

    async def on_audio(self, data: bytes):
        self.last_frame_t = time.time()
        if self.opus_in:
            frame = self.opus_in.decode(data)
            if not frame.size:
                return
        else:
            frame = np.frombuffer(data, "<i2").astype(np.float32) / 32768.0
        if self.mode == "ptt":
            if self.utt is None:
                self._start_turn()
                await self.state("listening")
            await self._feed(frame)
            return
        # hands-free: Silero VAD decides where turns start and end
        self.vad_buf = np.concatenate([self.vad_buf, frame])
        while self.vad_buf.size >= VAD_WIN:
            win, self.vad_buf = self.vad_buf[:VAD_WIN], self.vad_buf[VAD_WIN:]
            p = float(ASR["vad"](torch.from_numpy(win), SR))
            dt = VAD_WIN / SR
            if not self.in_speech:
                self.preroll.append(win)
                self.preroll = self.preroll[-int(PREROLL_S / dt):]
                over = self.playing() and self.enroll is None and self._barge_turn() is not None
                if over and p >= START_PROB and self.echo_db is not None:
                    loud = 10 * np.log10(float((win ** 2).mean()) + 1e-10)
                    if loud < self.echo_db + ECHO_MARGIN_DB:
                        p = 0.0                      # as loud as her own echo: not him
                if p >= START_PROB:
                    self.speech_s += dt
                    if self.speech_s >= (BARGE_ONSET_S if over else MIN_SPEECH_S):
                        onset = time.time() - self.speech_s
                        self.in_speech, self.silence_s, self.bi = True, 0.0, None
                        if over:
                            await self._barge_start(onset)
                        self._start_turn()
                        await self.state("listening")
                else:
                    self.speech_s = 0.0
            else:
                await self._feed(win, p >= END_PROB)
                await self._barge_step(p, dt, win)
                self.silence_s = self.silence_s + dt if p < END_PROB else 0.0
                if self.silence_s >= END_SILENCE_S:
                    await self._end_turn()

    # ---- enrollment ----------------------------------------------------
    async def _enroll_prompt(self):
        i = self.enroll["index"]
        lang, text = speaker.ENROLL_PROMPTS[i]
        await self.send(type="enroll_prompt", index=i, total=len(speaker.ENROLL_PROMPTS), text=text, lang=lang)

    async def _enroll_clip(self, emb, voiced, text):
        e = self.enroll
        total = len(speaker.ENROLL_PROMPTS)
        if emb is None or voiced < speaker.ENROLL_MIN_S:
            await self.send(type="enroll_progress", index=e["index"], total=total, ok=False, reason="too_short",
                            seconds=round(voiced, 1), text=text)
            await self._enroll_prompt()
            await self.idle()
            return
        e["embs"].append(emb)
        e["seconds"] += voiced
        await self.send(type="enroll_progress", index=e["index"], total=total, ok=True, seconds=round(voiced, 1),
                        text=text)
        e["index"] += 1
        if e["index"] < total:
            await self._enroll_prompt()
            await self.idle()
            return
        self.enroll = None
        embs = np.stack(e["embs"])
        profile = speaker.VoiceProfile(embs, SPEAKER_THRESHOLD, ASR["spk"].name, e["seconds"])
        consistency = [round(float(profile.centroid @ x), 3) for x in embs]
        if min(consistency) < SPEAKER_THRESHOLD:
            await self.send(type="enroll_done", ok=False, reason="inconsistent", consistency=consistency)
        else:
            await asyncio.to_thread(profile.save, PROFILE_PATH)
            ASR["profile"] = profile
            log.info("voice profile enrolled: %.1f s speech, consistency %s", e["seconds"], consistency)
            await self.send(type="enroll_done", ok=True, seconds=round(e["seconds"], 1), clips=len(embs),
                            threshold=profile.threshold, consistency=consistency)
        await self.send_profile()
        await self.idle()

    async def send_profile(self):
        p = ASR.get("profile")
        await self.send(type="speaker_profile", available=bool(ASR.get("spk")), enrolled=p is not None,
                        seconds=round(p.seconds, 1) if p else None, created=int(p.created) if p else None,
                        threshold=p.threshold if p else None)

    # ---- reply: LLM stream -> sentences -> TTS -------------------------
    async def cancel_reply(self):
        if self.reply_task and not self.reply_task.done():
            self.reply_task.cancel()
            try:
                await self.reply_task
            except (asyncio.CancelledError, Exception):
                pass
        self.speaking = False

    def _messages(self, text):
        language = "Korean" if lang_of(text) == "ko" else "English"
        system = system_prompt(self.context) + f"\nThe latest message is in {language}: reply in {language} only."
        last = self.replies.get(self.last_reply_turn)
        if last is not None and last.was_cut() and last.hist is not None and last.hist in self.history[-2:]:
            system += "\n" + last.cut_note(USER_NAME)
        return LAURA.messages(self, system, self.history[-MAX_HISTORY:], text)

    async def _llm_deltas(self, body, text):
        """Reply text deltas: the local model's SSE stream, or via the Laura link when Hermes is linked."""
        if LAURA.up:
            async for delta in LAURA.stream(self, self.http, LLM_URL, body, text, lang_of(text)):
                yield delta
            return
        async with self.http.stream("POST", LLM_URL, json=body) as resp:
            async for line in resp.aiter_lines():
                if not line.startswith("data: ") or line.endswith("[DONE]"):
                    continue
                delta = json.loads(line[6:])["choices"][0]["delta"].get("content") or ""
                if delta:
                    yield delta

    def _new_reply(self, turn_id):
        rec = self.replies[turn_id] = interrupts.Reply(turn_id)
        for old in list(self.replies)[:-6]:
            del self.replies[old]
        return rec

    async def _speak_text(self, turn_id, text, started):
        """Speak a fixed text sentence by sentence (canned replies, resumes, announcements)."""
        pending, pos = text + "\n", 0
        while (m := SENT_END.match(pending)):
            sentence, pending = m.group(1).strip(), pending[m.end():]
            if sentence and await self._speak(turn_id, sentence, started, (pos, pos + m.end())):
                started = True
            pos += m.end()
        if pending.strip() and await self._speak(turn_id, pending.strip(), started, (pos, len(text))):
            started = True
        return started

    async def _speak(self, turn_id, sentence, started, span=None):
        t = time.time()
        lang = lang_of(sentence)
        spoken = speakable(ANY_TAG.sub("", sentence).strip(), lang)
        if not spoken:
            return False
        body = {"text": spoken, "lang": lang, "voice": TTS_VOICE, "steps": TTS_STEPS, "speed": TTS_SPEED}
        if not TTS_STREAM:
            r = await self.http.post(TTS_URL, json=body)
            r.raise_for_status()
            self._segment(turn_id, sentence, span, r.content, int(r.headers["X-Sample-Rate"]), True)
            await self._play(turn_id, r.content, int(r.headers["X-Sample-Rate"]), started, t)
            return True
        async with self.http.stream("POST", TTS_URL + "_stream", json=body) as r:
            r.raise_for_status()
            rate, odd = int(r.headers["X-Sample-Rate"]), b""
            async for data in r.aiter_bytes():
                data, odd = odd + data, b""
                if len(data) % 2:
                    data, odd = data[:-1], data[-1:]
                if data:
                    self._segment(turn_id, sentence, span, data, rate, t is not None)
                    await self._play(turn_id, data, rate, started, t)
                    started, t = True, None
        return started

    def _segment(self, turn_id, sentence, span, pcm, rate, first):
        rec = self.replies.get(turn_id)
        if rec is not None and span is not None:
            if first:
                rec.add(sentence, span[0], span[1], pcm, rate)
            else:
                rec.extend(pcm, rate)

    async def _play(self, turn_id, pcm, rate, started, t):
        """Send one piece of TTS audio; the first piece of a reply opens the audio stream."""
        if t is not None:
            self.tts_ms = round((time.time() - t) * 1000)
        if not started:
            self.audio_t0, self.audio_s = time.time(), 0.0
            self.out = AudioOut(self.down_codec)
            await self.send(type="audio_start", turn_id=turn_id, sample_rate=self.out.rate, codec=self.out.codec)
            cue, self.avatar_cue = dict(getattr(self, "avatar_cue", None) or {}), None
            name = cue.pop("reaction", None)
            if name in SHORT_ONLY_REACTIONS:
                self.deferred_reaction = name           # decided once the whole reply is known
            elif name and self._reaction_ok(name):
                cue["reaction"] = name
                self._reacted(name)
            if cue:
                await self.send(type="avatar", turn_id=turn_id, **cue)
            self.speaking = True
            await self.state("speaking")
        self.audio_s += len(pcm) / 2 / rate
        for pkt in self.out.packets(pcm, rate):
            await self.ws.send_bytes(pkt)

    def _reaction_ok(self, name):
        """At most one reaction every two replies, never the same one twice in a row."""
        return not getattr(self, "reacted_prev", False) and name != getattr(self, "last_reaction", None)

    def _reacted(self, name):
        self.last_reaction, self.reacted_now = name, True

    @staticmethod
    def _hold_partial_tag(buf, final):
        """Split streamed text into (safe to emit, held back): a '<' with no '>' yet may be the start of a tag."""
        i = buf.rfind("<")
        if final or i < 0 or ">" in buf[i:] or len(buf) - i > TAG_WAIT_CHARS:
            return ANY_TAG.sub("", buf), ""
        return ANY_TAG.sub("", buf[:i]), buf[i:]

    def _lead_tags(self, buf, tags, final):
        """Peel reaction/mood tags off the start of the reply. -> (still-buffered or None, text to pass on)."""
        while True:
            body = buf.lstrip()
            if not body:
                return (None, "") if final else (buf, "")
            if not body.startswith("<"):
                break
            m = LEAD_TAG.match(buf)
            if m:
                kind, name = m.group(1), m.group(2).lower()
                if kind is None:                # bare <nod>: infer the kind from the name
                    kind = "r" if name in REACTIONS else "e" if name in EMOTIONS else "x"
                if kind not in tags:
                    if kind == "r" and name in REACTIONS and not self.context.get("car"):
                        tags["r"] = name
                    elif kind == "e" and name in EMOTIONS:
                        tags["e"] = name
                buf = buf[m.end():]
                continue
            if ">" in body:                     # malformed or unknown tag: drop it
                buf = body[body.index(">") + 1:]
                continue
            if len(body) > TAG_WAIT_CHARS or final:
                break
            return buf, ""
        cue = {}
        if "r" in tags:
            cue["reaction"] = tags["r"]
        if "e" in tags:
            cue["emotion"] = tags["e"]
        self.avatar_cue = cue or None
        return None, buf.lstrip()

    async def reply(self, turn_id, text, canned=None, resume=None):
        self.reply_source = LOCAL_SOURCE
        await self.state("thinking")
        self.reply_turn = turn_id
        rec = self._new_reply(turn_id)
        full, pending, started = "", "", False
        lead, tags = "", {}             # leading <r:...>/<e:...> tags, held back until resolved
        hold = ""                       # a possible tag split across tokens later in the reply
        looped = False
        self.avatar_cue, self.deferred_reaction, self.reacted_now = None, None, False
        t0 = time.time()
        t_end = getattr(self, "t_turn_end", t0)
        try:
            if canned:
                full = rec.text = canned
                await self.send(type="assistant_delta", turn_id=turn_id, text=canned)
                started = await self._speak_text(turn_id, canned, False)
                rec.complete = True
                await self.send(type="assistant_done", turn_id=turn_id, text=canned)
                if resume:
                    self._resumed(resume, text, rec)
                return
            await self._settle_cuts()
            body = {"model": LLM_MODEL, "stream": True, "max_tokens": 300, "temperature": 0.6, **SAMPLING,
                    "messages": self._messages(text), "chat_template_kwargs": {"enable_thinking": False}}
            async with aclosing(self._llm_deltas(body, text)) as deltas:
                async for delta in deltas:
                    if lead is not None:
                        lead, delta = self._lead_tags(lead + delta, tags, final=False)
                        if lead is not None or not delta:
                            continue
                    delta, hold = self._hold_partial_tag(hold + delta, final=False)
                    if not delta:
                        continue
                    full += delta
                    pending += delta
                    if (cut := cut_loop(full, pending)) is not None:
                        log.warning("turn %s: reply started repeating itself, cut at %d chars", turn_id, len(cut[0]))
                        full, pending = cut
                        hold, looped = "", True
                        break
                    await self.send(type="assistant_delta", turn_id=turn_id, text=delta)
                    while (m := SENT_END.match(pending) or (not started and FIRST_CLAUSE.match(pending))):
                        c0 = len(full) - len(pending)
                        sentence, pending = m.group(1).strip(), pending[m.end():]
                        rec.text = full
                        if sentence:
                            llm_ms = round((time.time() - t0) * 1000)
                            if not await self._speak(turn_id, sentence, started, (c0, c0 + m.end())):
                                continue
                            if not started:
                                m_ = {"asr_ms": getattr(self, "asr_ms", None), "llm_first_ms": llm_ms,
                                      "tts_first_ms": self.tts_ms,
                                      "server_first_audio_ms": round((self.audio_t0 - t_end) * 1000),
                                      **self.turn_metrics}
                                await self.send(type="metrics", turn_id=turn_id, **m_)
                                log.info("turn %s metrics %s", turn_id, m_)
                            started = True
            rest = hold
            if lead and not looped:
                rest = self._lead_tags(lead, tags, final=True)[1]
            rest = self._hold_partial_tag(rest, final=True)[0]
            if rest:
                full += rest
                pending += rest
                await self.send(type="assistant_delta", turn_id=turn_id, text=rest)
            rec.text = full
            if pending.strip() and await self._speak(turn_id, pending.strip(), started,
                                                     (len(full) - len(pending), len(full))):
                started = True
            rec.complete = True
            full = re.sub(r"\s+", " ", ANY_TAG.sub("", full)).strip()
            name = self.deferred_reaction
            if (started and name and len(full.split()) < SHORT_REPLY_WORDS and self._reaction_ok(name)):
                self._reacted(name)
                await self.send(type="avatar", turn_id=turn_id, reaction=name)
            await self.send(type="assistant_done", turn_id=turn_id, text=full)
            rec.tags = "".join(f"<{k}:{v}>" for k, v in tags.items())
            rec.hist = {"role": "assistant", "content": rec.tags + (rec.history_text() if rec.cut is not None
                                                                    else full.strip())}
            self.history += [{"role": "user", "content": text}, rec.hist]
        except asyncio.CancelledError:
            rec.text = full
            if rec.cut_at is None:           # superseded by a new turn rather than a barge-in
                rec.set_cut(rec.estimate_played(time.time()), False)
            rec.tags = "".join(f"<{k}:{v}>" for k, v in tags.items())
            rec.hist = {"role": "assistant", "content": rec.tags + rec.history_text()}
            self.history += [{"role": "user", "content": text}, rec.hist]
            raise
        except Exception as e:  # noqa: BLE001
            log.exception("reply failed")
            await self.send(type="error", message=str(e)[:200])
        finally:
            if started:
                self.gate.note_reply(full.strip(), max(self.audio_t0 + self.audio_s, rec.play_end()))
                try:
                    for pkt in self.out.flush():
                        await self.ws.send_bytes(pkt)
                except Exception:  # noqa: BLE001
                    pass
                await self.send(type="audio_end", turn_id=turn_id)
            self.speaking = False
            self.reacted_prev = self.reacted_now
            self.last_reply_turn, self.reply_turn = turn_id, None
            await self.idle()

    def _resumed(self, intent, said, rec):
        """After a deterministic 'go on' / 'repeat that': move the resume point and tidy the cut reply's history."""
        if not self.resumable:
            return
        old, off = self.resumable
        if intent == "continue":
            self.resumable = None
            if old.hist is not None:
                old.hist["content"] = old.tags + old.text[:off].rstrip() + " [Hector interrupted me here]"
        else:
            span = old.current_sentence(off)
            if span and old.text[span[1]:].strip():
                self.resumable = (old, span[1])

    async def announce(self, text, source=None):
        """Speak something Laura says on her own (a finished task), outside a user turn; barge-in stops it."""
        await self.cancel_reply()
        self.reply_source = source or LOCAL_SOURCE
        self.reply_task = asyncio.create_task(self._announce(uuid.uuid4().hex[:8], text))
        try:
            await self.reply_task
        except asyncio.CancelledError:
            pass

    async def _announce(self, turn_id, text):
        started = False
        rec = self._new_reply(turn_id)
        rec.text, rec.complete = text, True
        self.reply_turn = turn_id
        try:
            await self.send(type="assistant_done", turn_id=turn_id, text=text)
            started = await self._speak_text(turn_id, text, started)
        finally:
            self.last_reply_turn, self.reply_turn = turn_id, None
            if started:
                try:
                    for pkt in self.out.flush():
                        await self.ws.send_bytes(pkt)
                except Exception:  # noqa: BLE001
                    pass
                await self.send(type="audio_end", turn_id=turn_id)
                self.gate.note_reply(text, max(self.audio_t0 + self.audio_s, rec.play_end()))
            self.speaking = False
            await self.idle()

    async def on_control(self, msg):
        t = msg.get("type")
        if t == "hello":
            self.mode = msg.get("mode", self.mode)
            self.lang = msg.get("lang", self.lang)
            self.context = msg.get("context") or {}
            audio = msg.get("audio") or {}
            self.opus_in = OpusIn() if audio.get("up") == "opus" else None
            self.down_codec = "opus" if "opus" in (audio.get("down") or []) else "pcm"
            self.gate_on = msg.get("gate", True) is not False
            self.gate.reply_end = max(self.gate.reply_end, time.time())   # app just opened: no trigger needed
            log.info("session %s audio up=%s down=%s gate=%s", self.id, "opus" if self.opus_in else "pcm",
                     self.down_codec, self.gate_on)
            await self.send(type="session", id=self.id)
            await self.send_profile()
            await self.send(type="muted", value=self.gate.muted)
            await self.idle()
            await LAURA.on_hello(self)
        elif t == "end_turn":
            await self._end_turn()
        elif t == "interrupt":
            await self.interrupt_reply("client", self._played_ms(msg), msg.get("turn_id") or None)
            await self.idle()
        elif t == "playback":
            rec = self.replies.get(msg.get("turn_id"))
            played = self._played_ms(msg)
            if msg.get("state") == "done":
                if not self.speaking:
                    self.gate.reply_end = time.time()
            elif rec is not None and played is not None:
                await self.apply_cut(rec, played / 1000, True)
        elif t == "recall":
            self.t_turn_end = time.time()
            await self.recall()
        elif t == "mute":
            self.gate.muted = bool(msg.get("value", True))
            await self.send(type="muted", value=self.gate.muted)
        elif t == "gate":
            self.gate_on = msg.get("value", True) is not False
        elif t == "enroll_start":
            if not ASR.get("spk"):
                await self.send(type="error", message="speaker verification is not available")
                return
            await self.cancel_reply()
            self.enroll = {"index": 0, "embs": [], "seconds": 0.0}
            await self._enroll_prompt()
        elif t == "enroll_cancel":
            if self.enroll is not None:
                self.enroll = None
                await self.send(type="enroll_done", ok=False, reason="cancelled")
        elif t == "enroll_delete":
            PROFILE_PATH.unlink(missing_ok=True)
            ASR["profile"] = None
            log.info("voice profile deleted")
            await self.send_profile()
        elif t == "mode":
            self.mode = msg.get("value", self.mode)
            self.utt, self.in_speech = None, False
        elif t == "lang":
            self.lang = msg.get("value", self.lang)
        elif t == "context":
            self.context = msg.get("data") or {}
        elif t == "ping":
            await self.send(type="pong", t=msg.get("t"))
        elif t == "wake":
            try:
                score = round(float(msg.get("score", 1.0)), 3)
            except (TypeError, ValueError):
                score = 1.0
            log.info("session %s wake word %.3f", self.id, score)
            if self.in_speech and self.utt is not None:
                self.turn_wake = score
            else:
                self.wake_at = (time.time(), score)
        elif t in LAURA_CONTROLS:
            await LAURA.on_control(self, msg)
        elif t == "client_stats":
            msg.pop("type", None)
            log.info("client %s stats %s", self.id, json.dumps(msg))


ACTIVE = {"session": None}


def client_key(ws: WebSocket):
    """Access key and the subprotocol to accept. Preferred: `Authorization: Bearer <key>` (native clients) or the
    subprotocols `laura.v1, key.<key>` (browsers); `?k=` stays for older clients but puts the key in URLs."""
    protocols = [p.strip() for p in ws.headers.get("sec-websocket-protocol", "").split(",") if p.strip()]
    for p in protocols:
        if p.startswith("key."):
            return p[4:], "laura.v1" if "laura.v1" in protocols else None
    auth = ws.headers.get("authorization", "")
    if auth.startswith("Bearer "):
        return auth[7:], None
    return ws.query_params.get("k", ""), None


@app.websocket("/ws")
async def ws_endpoint(ws: WebSocket):
    key, subprotocol = client_key(ws)
    if ACCESS_KEY and not secrets.compare_digest(key, ACCESS_KEY):
        await ws.close(code=4401)
        return
    await ws.accept(subprotocol=subprotocol)
    old = ACTIVE["session"]
    if old:  # single user: a new device takes over
        await old.cancel_reply()
        try:
            await old.ws.close(code=4000)
        except Exception:  # noqa: BLE001
            pass
    s = ACTIVE["session"] = Session(ws)
    if old:
        s.gate.muted = old.gate.muted
    await LAURA.attach(s)
    watchdog = asyncio.create_task(s.watchdog())
    log.info("session %s connected", s.id)
    try:
        while True:
            msg = await ws.receive()
            if msg.get("bytes") is not None:
                await s.on_audio(msg["bytes"])
            elif msg.get("text") is not None:
                await s.on_control(json.loads(msg["text"]))
            elif msg.get("type") == "websocket.disconnect":
                break
    except WebSocketDisconnect:
        pass
    finally:
        watchdog.cancel()
        await s.cancel_reply()
        LAURA.detach(s)
        await s.http.aclose()
        if ACTIVE["session"] is s:
            ACTIVE["session"] = None
        log.info("session %s closed", s.id)


@app.get("/health")
def health():
    return {"asr": bool(ASR), "session": ACTIVE["session"].id if ACTIVE["session"] else None}


if CLIENT_DIR.exists():
    app.mount("/", StaticFiles(directory=str(CLIENT_DIR), html=True), name="client")
