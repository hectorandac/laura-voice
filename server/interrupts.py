"""Interruptions: where a reply was cut, what was heard, and whether speech over a reply is a real barge-in.

A reply is a list of spoken segments (sentence text, its span in the reply text, offset and length in the reply
audio, voiced span inside the segment). The client reports how much reply audio it actually played; that maps to a
word inside the current sentence, proportional to letters within the segment's voiced span (Supertonic pads each
sentence with ~0.5 s of silence on both ends).
"""
import re
import time
import unicodedata
from difflib import SequenceMatcher
from dataclasses import dataclass, field

import numpy as np


def voiced_span(pcm: bytes, rate: int, win_s=0.01, rel=0.05):
    """(start_s, end_s) of the audible part of a TTS clip."""
    x = np.frombuffer(pcm, "<i2").astype(np.float32)
    n = max(1, int(rate * win_s))
    k = x.size // n
    if k == 0:
        return 0.0, x.size / rate
    e = np.sqrt((x[:k * n].reshape(k, n) ** 2).mean(1))
    v = np.flatnonzero(e > e.max() * rel)
    if v.size == 0:
        return 0.0, x.size / rate
    return v[0] * win_s, (v[-1] + 1) * win_s


@dataclass
class Segment:
    text: str          # sentence as written
    c0: int            # span in Reply.text
    c1: int
    start: float       # offset in the reply audio (s)
    dur: float
    v0: float          # voiced span inside the segment (s)
    v1: float
    sent_at: float = 0.0


WORD = re.compile(r"\S+")
PAUSE = {",": 3.0, "，": 3.0, ";": 3.0, ":": 3.0, ".": 4.0, "!": 4.0, "?": 4.0, "…": 4.0, "—": 3.0}


def _weight(tok):
    letters = sum(2.0 if "가" <= ch <= "힣" else 1.0 if ch.isalnum() else 0.0 for ch in tok)
    return letters + 1.0, PAUSE.get(tok[-1], 0.0)   # +1: word onset; trailing punctuation adds a pause


def word_cut(text, frac):
    """Char offset in `text` after the last word Hector heard at least half of, at fraction `frac` of the
    voiced audio."""
    toks = list(WORD.finditer(text))
    if not toks or frac <= 0:
        return 0
    if frac >= 1:
        return len(text)
    ws = [_weight(m.group()) for m in toks]
    total = sum(a + b for a, b in ws)
    pos, cut = 0.0, 0
    for m, (a, b) in zip(toks, ws):
        mid = (pos + a / 2) / total
        if frac < mid:
            break
        cut = m.end()
        pos += a + b
    return cut


@dataclass
class Reply:
    turn_id: str
    text: str = ""                       # full reply text generated so far
    segments: list = field(default_factory=list)
    complete: bool = False               # the LLM finished (text is the whole reply)
    t0: float = 0.0                      # wall time the first audio was sent
    played_s: float | None = None        # reported or estimated audio played when it stopped
    cut: int | None = None               # char offset in text where Hector stopped hearing it
    reported: bool = False
    cut_at: float | None = None          # wall time a barge-in stopped it (client report pending)
    hist: dict | None = None             # its history message, rewritten once the cut point is known
    tags: str = ""

    @property
    def audio_s(self):
        return self.segments[-1].start + self.segments[-1].dur if self.segments else 0.0

    def add(self, text, c0, c1, pcm, rate, now=None):
        dur = len(pcm) / 2 / rate
        v0, v1 = voiced_span(pcm, rate)
        if not self.segments:
            self.t0 = now or time.time()
        self.segments.append(Segment(text, c0, c1, self.audio_s, dur, v0, v1, now or time.time()))
        self._pcm = [pcm]

    def extend(self, pcm, rate):
        """More audio of the last segment (streaming TTS)."""
        s = self.segments[-1]
        self._pcm.append(pcm)
        whole = b"".join(self._pcm)
        s.dur = len(whole) / 2 / rate
        s.v0, s.v1 = voiced_span(whole, rate)

    def estimate_played(self, at, lead=0.15):
        """Without a client report: play each segment once it has arrived, back to back, after `lead`."""
        head, played = None, 0.0
        for s in self.segments:
            start = s.sent_at + lead if head is None else max(head, s.sent_at + lead)
            played += min(max(at - start, 0.0), s.dur)
            head = start + s.dur
        return played

    def play_end(self, lead=0.15):
        """Wall time the client should finish playing it (same model as estimate_played)."""
        head = None
        for s in self.segments:
            head = (s.sent_at + lead if head is None else max(head, s.sent_at + lead)) + s.dur
        return head or 0.0

    def text_near(self, played_s, before_s=8.0, after_s=4.0):
        """What she is saying around playback position `played_s` (for recognising her own echo)."""
        return " ".join(s.text for s in self.segments
                        if s.start + s.dur >= played_s - before_s and s.start <= played_s + after_s)

    def locate(self, played_s):
        """-> char offset in self.text up to which Hector heard the reply."""
        if not self.segments:
            return 0
        if played_s >= self.audio_s - 0.05:
            seg = self.segments[-1]
            return seg.c1 if not self.complete or seg.c1 < len(self.text.rstrip()) else len(self.text)
        for s in self.segments:
            if played_s < s.start + s.dur:
                into = played_s - s.start
                frac = (into - s.v0) / max(s.v1 - s.v0, 1e-3)
                if frac <= 0:
                    return s.c0
                piece = self.text[s.c0:s.c1]
                lead = len(piece) - len(piece.lstrip())
                return s.c0 + lead + word_cut(piece.strip(), frac)
        return self.segments[-1].c1

    def set_cut(self, played_s, reported):
        self.played_s, self.reported = played_s, reported
        self.cut = self.locate(played_s)

    def heard(self):
        return self.text[:self.cut].rstrip() if self.cut is not None else self.text.strip()

    def unsaid(self):
        return self.text[self.cut:].strip() if self.cut is not None else ""

    def was_cut(self):
        return self.cut is not None and bool(self.unsaid())

    def current_sentence(self, at=None):
        """(start, end) chars of the sentence playing at char offset `at` (default: the cut)."""
        at = (self.cut or 0) if at is None else at
        for s in self.segments:
            if s.c0 < at <= s.c1 or (at <= s.c0 and s is self.segments[0]):
                return s.c0, s.c1
        return None

    def history_text(self):
        """Her message as Hector heard it: cut off mid-way when he interrupted."""
        if not self.was_cut():
            return self.text.strip()
        return (self.heard() + "...") if self.heard() else "..."

    def cut_note(self, user="Hector", max_words=28):
        """Context for the next turn only, in the system prompt (an inline marker in her own message, or a note in
        his, makes the model imitate it or answer about it)."""
        rest = self.unsaid().split()
        short = " ".join(rest[:max_words]) + (" ..." if len(rest) > max_words else "")
        if not self.complete:
            short += " ... (you hadn't finished it)"
        return (f"{user} just talked over your last reply, so it ends where he cut you off. He did not hear the "
                f'rest: "{short}". Answer what he says now, as yourself, speaking to him.')


# ---- what was said over the reply -------------------------------------------------------------------

def norm(text):
    t = text.lower().replace("’", "'")
    t = re.sub(r"[^\w\s'가-힣ㄱ-ㅎ]", " ", t)
    return re.sub(r"\s+", " ", t).strip()


BACKCHANNEL = {
    # English
    "uh huh", "uh-huh", "uhhuh", "mhm", "mm", "mmm", "mm hmm", "mhmm", "hmm", "hm", "yeah", "yea", "yep", "yup",
    "yes", "ok", "okay", "k", "right", "sure", "i see", "got it", "oh", "ah", "aha", "ooh", "wow", "nice", "cool",
    "really", "haha", "ha", "hah", "hehe", "true", "exactly", "totally", "alright", "all right", "uh", "um",
    "huh", "oh okay", "great", "interesting", "makes sense", "fair", "fair enough", "good", "perfect", "indeed",
    "go on", "keep going", "continue", "of course", "definitely", "i know", "oh really", "is that so", "damn", "gotcha", "awesome",
    # Korean
    "응", "어", "엉", "네", "넵", "예", "아", "음", "오", "와", "우와", "헐", "그래", "그래요", "그렇구나", "그렇군요",
    "맞아", "맞아요", "맞네", "그렇죠", "그치", "그쵸", "아하", "아아", "오오", "진짜", "정말", "대박", "좋아", "좋네",
    "계속", "계속해", "그렇네", "그러네", "알겠어", "알겠어요", "오케이", "흠", "하하", "ㅋㅋ", "히히", "에이", "와우", "그렇지",
}
BC_TOKENS = {t for p in BACKCHANNEL for t in p.split()}
STOP = re.compile(
    r"^(?:(?:hey )?laura\b|stop|wait|hold on|hang on|no\b|nope|nah|actually|sorry|excuse me|enough|skip|shut up|"
    r"quiet|pause|cancel|never ?mind|but\b|what\b|repeat|go back|"
    r"로라|잠깐|잠시만|그만|아니|아뇨|스톱|멈춰|됐어|됐어요|근데|그런데|다시|뭐라고|취소)")


STOP_ANY = re.compile(r"(?:^|\s)(?:wait|stop|hold on|hang on|no|nope|laura|잠깐|잠시만|그만|아니|로라|스톱|멈춰)(?:\s|$)")


FILLER = r"(?:ha|he|ㅋ|하|히|응|네|음|어|아|오|흠|m+|h+m+|u+h*m*|e+r*m+|a+h+|o+h+|e+h+|hu+h)+"


def is_backchannel(t):
    t = norm(t)
    if not t:
        return False
    if t in BACKCHANNEL:
        return True
    toks = re.sub(r"(.)\1{2,}", r"\1\1", t).split()     # "yeahhh" "응응응"
    return len(toks) <= 4 and all(x in BC_TOKENS or re.fullmatch(FILLER, x) for x in toks)


def _jamo(w):
    return unicodedata.normalize("NFD", w)


def _same_word(a, b):
    """Same word, allowing ASR slips on low-level echo ('역시'/'역사는', 'sai'/'stay') and Korean particles."""
    if a == b:
        return True
    if "가" <= a[0] <= "힣":
        a, b = _jamo(a), _jamo(b)
        n = min(len(a), len(b), 6)
        return n >= 4 and SequenceMatcher(None, a[:n], b[:n]).ratio() >= 0.75
    return len(a) >= 3 and SequenceMatcher(None, a, b).ratio() >= 0.75


FUNCTION_WORDS = set("a an the is are was were be been to of and or but in on at it its that this these those i you "
                     "we he she they me my your so for with as not no yes do does did can will would just right very "
                     "really there here what then than if about from by up out".split())


def is_echo(heard, own_text, short_ok=False):
    """Does what the microphone heard look like the reply being played (residual echo)?"""
    h, own = norm(heard).split(), norm(own_text).split()
    if not h or not own:
        return False
    h = [w for w in h if w not in FUNCTION_WORDS] or h
    if not short_ok and len(h) == 1 and len(h[0]) < (2 if "가" <= h[0][0] <= "힣" else 4):
        return False
    hits = sum(any(_same_word(w, o) for o in own) for w in h)
    return hits / len(h) >= 0.6 or (hits >= 4 and hits / len(h) >= 0.4)


def new_words(text, before):
    """The words of `text` beyond the ones already in `before` (a streaming hypothesis that grew)."""
    a, b = text.split(), before.split()
    i = 0
    while i < min(len(a), len(b)) and norm(a[i]) == norm(b[i]):
        i += 1
    return " ".join(a[i:])


def content_words(text):
    return [w for w in norm(text).split() if w not in FUNCTION_WORDS and w not in BC_TOKENS]


def classify(text, own_text="", fresh=False):
    """'stop' | 'content' | 'backchannel' | 'echo' | 'empty' for speech heard over a reply. `fresh`: words that
    came after speech already judged to be her echo, so even one short matching word counts as echo."""
    t = norm(text)
    if not t:
        return "empty"
    if STOP.match(t) and len(t.split()) <= 5 or fresh and STOP_ANY.search(t):
        return "stop"
    if own_text and is_echo(t, own_text, short_ok=fresh):
        return "echo"
    if STOP.match(t):
        return "stop"
    if is_backchannel(t):
        return "backchannel"
    return "content"


# ---- go on / repeat ------------------------------------------------------------------------------------

_LEAD = r"^(?:(?:hey |ok(?:ay)? |sorry |so |yes |yeah |no |uh |um |actually |oh )*(?:laura|로라)?[ ,]*)"
CONTINUE = re.compile(_LEAD + r"(?:please )?(?:go on|continue|keep going|carry on|go ahead|and then|then what|and|"
                      r"what were you saying|you were saying|finish (?:that|what you were saying)|"
                      r"계속(?:해|해요|해줘|해 줘|하세요|해봐|말해|말해줘|말해 줘)?|이어서(?: 말해(?:줘|요)?| 해(?:줘|요)?)?|"
                      r"그래서(?:요)?|하던 (?:말|얘기)(?: 계속)?(?: 해(?:줘|요)?)?|무슨 말 하고 있었(?:어|지|어요)?|"
                      r"뭐라고 하고 있었(?:어|지|어요)?)(?: please)?$")
REPEAT = re.compile(_LEAD + r"(?:please )?(?:repeat(?: that| it| the last (?:part|sentence|bit))?|say (?:that|it) again|"
                    r"what did you (?:just )?say|come again|pardon|again|one more time|"
                    r"다시(?: 한 ?번)?(?: 말해(?:줘|줘요|요|주세요)?| 해(?:줘|줘요)?)?|뭐라고(?:요|했어|했어요|하셨어요)?|"
                    r"한 ?번 더(?: 말해(?:줘|요)?)?)(?: please)?$")


def resume_intent(text):
    t = norm(text)
    t = re.sub(r"(해|말해) (줘|주세요|봐)", r"\1\2", re.sub(r"계속 (해|말)", r"계속\1", t))   # ASR spacing
    if not t or len(t.split()) > 7:
        return None
    if CONTINUE.match(t):
        return "continue"
    if REPEAT.match(t):
        return "repeat"
    return None


HOLD = re.compile(_LEAD + r"(?:wait|stop|hold on|hang on|one sec(?:ond)?|just a (?:sec(?:ond)?|moment|minute)|pause|"
                  r"never ?mind|forget it|enough|shh+|quiet|"
                  r"잠깐(?:만|만요|요)?|잠시만(?:요)?|그만(?:해|해요)?|스톱|멈춰(?:요)?|됐어(?:요)?|조용히 해(?:요)?)"
                  r"(?: (?:wait|please|요))*$")


def is_hold(text):
    """Only "wait" / "stop" / "잠깐만": she stops and waits, without answering."""
    return bool(HOLD.match(norm(text)))


INTERRUPT_RULES = (
    "When Hector talks over you, your reply ends in \"...\" where he cut you off; he only heard the part before "
    "it. Respond to what he said instead: don't repeat what he already heard, acknowledge briefly "
    "or just pivot. Resolve \"the other one\", \"that\" or \"no\" against what he actually heard. If he says "
    "\"go on\", \"continue\" or \"계속해\", pick up exactly where you were cut off, without starting over."
)
