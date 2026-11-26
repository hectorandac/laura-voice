"""Text -> phoneme ids for the small Laura voice (Piper/VITS symbol table).

English: IPA from the system espeak-ng library (en-us), clause by clause.
Korean: g2pk2 applies the pronunciation rules (linking, assimilation, tensing), then each Hangul syllable
is spelled in IPA from its jamo. Two symbols Piper's table lacks get free ids: tense (U+0348), unreleased (U+031A).
Latin words inside Korean text go through espeak.
"""
import ctypes
import ctypes.util
import re
import threading
import unicodedata

PAD, BOS, EOS = "_", "^", "$"
TENSE, UNRELEASED = "͈", "̚"
EXTRA_IDS = {TENSE: 157, UNRELEASED: 158}

ONSET = ["k", "k" + TENSE, "n", "t", "t" + TENSE, "ɾ", "m", "p", "p" + TENSE, "s", "s" + TENSE, "", "tɕ",
         "tɕ" + TENSE, "tɕʰ", "kʰ", "tʰ", "pʰ", "h"]
NUCLEUS = ["a", "ɛ", "ja", "jɛ", "ʌ", "e", "jʌ", "je", "o", "wa", "wɛ", "we", "jo", "u", "wʌ", "we", "wi", "ju",
           "ɯ", "ɯi", "i"]
K, T, P = "k" + UNRELEASED, "t" + UNRELEASED, "p" + UNRELEASED
CODA = ["", K, K, K, "n", "n", "n", T, "l", K, "m", "l", "l", "l", P, "l", "m", P, P, T, T, "ŋ", T, T, K, T, P, T]

CLAUSE = re.compile(r"([,.!?;:…]+)(?=\s|$)")
RUN = re.compile(r"[가-힣]+|[A-Za-z0-9'][A-Za-z0-9'\-]*")


class Espeak:
    """Minimal ctypes binding to libespeak-ng (synchronous, phonemes only)."""

    def __init__(self, voice="en-us"):
        path = ctypes.util.find_library("espeak-ng") or "libespeak-ng.so.1"
        self.lib = ctypes.cdll.LoadLibrary(path)
        self.lib.espeak_Initialize.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_char_p, ctypes.c_int]
        self.lib.espeak_TextToPhonemes.restype = ctypes.c_char_p
        self.lib.espeak_TextToPhonemes.argtypes = [ctypes.POINTER(ctypes.c_char_p), ctypes.c_int, ctypes.c_int]
        if self.lib.espeak_Initialize(2, 0, None, 0) < 0:          # AUDIO_OUTPUT_SYNCHRONOUS
            raise RuntimeError("espeak-ng init failed")
        self.lib.espeak_SetVoiceByName(voice.encode())
        self.lock = threading.Lock()

    def __call__(self, text):
        buf = ctypes.c_char_p(text.encode())
        ptr = ctypes.pointer(buf)
        out = []
        with self.lock:
            while ptr.contents.value:
                ph = self.lib.espeak_TextToPhonemes(ptr, 1, 0x02)  # UTF-8 in, IPA out
                if ph:
                    out.append(ph.decode())
        return " ".join(out)


_espeak = None
_g2pk = None


def espeak(text):
    global _espeak
    if _espeak is None:
        _espeak = Espeak()
    return _espeak(text)


def korean_rules(text):
    global _g2pk
    if _g2pk is None:
        from g2pk2 import G2p
        _g2pk = G2p()
    return _g2pk(text)


def hangul_ipa(word):
    out = []
    for ch in word:
        c = ord(ch) - 0xAC00
        out.append(ONSET[c // 588] + NUCLEUS[c % 588 // 28] + CODA[c % 28])
    return "".join(out)


TAG = re.compile(r"<[^<>]{0,40}>")
URL = re.compile(r"https?://\S+|www\.\S+")
BULLET = re.compile(r"^\s*(?:[-*•]|\d+[.)])\s+|\s[-–—]\s", re.M)
SILENT = re.compile(r"[*_`#~|\\^<>\[\]{}=\"“”]+")
SLASH = re.compile(r"(?<=\w)/(?=\w)")


def speakable(text):
    """Drop markup and symbols that espeak would read aloud ("asterisk", "colon", emoji names)."""
    text = BULLET.sub(", ", text)
    text = URL.sub(" ", TAG.sub(" ", re.sub(r"\s+", " ", text)))
    text = SLASH.sub(" ", SILENT.sub(" ", text))
    text = "".join(ch for ch in text if unicodedata.category(ch)[0] not in "SC" or ch in "$%&+@°₩")
    text = re.sub(r"\s+([,.!?;:])", r"\1", text)
    text = re.sub(r"([,.!?;:])[,.!?;:]+", r"\1", text)
    return re.sub(r"\s+", " ", text).strip(" ,")


def phonemize(text, lang="en"):
    """-> IPA string with spaces between words and clause punctuation kept."""
    text = speakable(text)
    korean = lang == "ko" or re.search("[가-힣]", text)
    if korean:
        text = korean_rules(text)
    parts = []
    for piece in CLAUSE.split(text):
        if not piece.strip():
            continue
        if CLAUSE.fullmatch(piece):
            parts.append(piece[0] if piece[0] != "…" else ".")
            continue
        if not korean:
            parts.append(" " + espeak(piece.strip()))
            continue
        words, latin = [], []
        for run in RUN.findall(piece):
            if re.match("[가-힣]", run):
                if latin:
                    words.append(espeak(" ".join(latin))); latin = []
                words.append(hangul_ipa(run))
            else:
                latin.append(run)
        if latin:
            words.append(espeak(" ".join(latin)))
        parts.append(" " + " ".join(words))
    return "".join(parts).strip()


def to_ids(phonemes, id_map):
    """Piper layout: BOS, then each symbol followed by PAD, then EOS. Unknown symbols are dropped."""
    ids = [id_map[BOS][0], id_map[PAD][0]]
    for ch in unicodedata.normalize("NFD", phonemes):
        if ch in id_map:
            ids += [id_map[ch][0], id_map[PAD][0]]
    ids.append(id_map[EOS][0])
    return ids


def id_map(base):
    m = dict(base)
    for ch, i in EXTRA_IDS.items():
        m[ch] = [i]
    return m


if __name__ == "__main__":
    import sys
    for line in sys.argv[1:] or ["Hi Hector, I'm here. What should we work on tonight?",
                                 "안녕하세요 헥터, 저 여기 있어요. 오늘 밤엔 뭘 해볼까요?", "GPU 메모리가 부족해요."]:
        print(line, "->", phonemize(line))
