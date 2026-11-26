"""Unit tests for server/interrupts.py (no models needed): python -m pytest tests/interrupts_unit_test.py"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import interrupts as I  # noqa: E402


def clip(voiced_s, lead=0.5, trail=0.7, rate=44100):
    x = np.zeros(int((lead + voiced_s + trail) * rate), np.float32)
    x[int(lead * rate):int((lead + voiced_s) * rate)] = 0.3 * np.sin(np.arange(int(voiced_s * rate)) / 9)
    return (x * 32767).astype("<i2").tobytes()


def make_reply():
    text = "There are two options. The second option is cheaper, but the first one is faster. Pick one."
    r = I.Reply("t1", text=text, complete=True)
    pos = 0
    for sent, dur in (("There are two options. ", 1.2), ("The second option is cheaper, but the first one is faster. ", 3.0),
                      ("Pick one.", 0.8)):
        r.add(sent.strip(), pos, pos + len(sent), clip(dur), 44100, now=100.0)
        pos += len(sent)
    return r


def test_classify():
    own = "The second option is cheaper, but the first one is faster."
    for t in ("uh-huh", "Yeah.", "mm hmm", "Okay okay", "haha", "응", "네네", "아 그렇구나", "맞아요"):
        assert I.classify(t, own) == "backchannel", t
    for t in ("wait", "stop", "No, the other one", "잠깐만", "아니 다른 거", "그만", "what about tea"):
        assert I.classify(t, own) == "stop", t
    assert I.classify("tell me the time instead", own) == "content"
    assert I.classify("yeah but the bus is cheaper right", own) == "content"
    assert I.classify("the second option is cheaper", own) == "echo"
    assert I.classify("", own) == "empty"


def test_resume_intent():
    for t in ("go on", "Laura, go on", "okay go on", "continue", "계속해", "계속해 줘", "그래서?", "what were you saying"):
        assert I.resume_intent(t) == "continue", t
    for t in ("repeat that", "say that again", "다시 말해줘", "뭐라고?", "한 번 더"):
        assert I.resume_intent(t) == "repeat", t
    assert I.resume_intent("go on and tell me about the bus") is None
    assert I.resume_intent("what time is it") is None


def test_locate():
    r = make_reply()
    s1 = r.segments[1]
    assert r.locate(0.2) == 0                                     # still in the leading silence
    assert r.text[:r.locate(s1.start + 0.3)].rstrip().endswith("options.")  # second sentence padding only
    mid = s1.start + s1.v0 + 0.5 * (s1.v1 - s1.v0)
    heard = r.text[:r.locate(mid)]
    assert "cheaper," in heard and "faster" not in heard, heard
    assert r.locate(r.audio_s) == len(r.text)


def test_history_and_resume():
    r = make_reply()
    s1 = r.segments[1]
    r.set_cut(s1.start + s1.v0 + 0.3 * (s1.v1 - s1.v0), True)
    h = r.history_text()
    assert h.startswith("There are two options. The second") and h.endswith("...") and "Pick one." not in h
    assert r.unsaid().endswith("Pick one.") and "Pick one." in r.cut_note()
    assert r.current_sentence() == (s1.c0, s1.c1)


def test_estimate_played():
    r = make_reply()
    for s in r.segments:
        s.sent_at = 100.0
    assert abs(r.estimate_played(101.15) - 1.0) < 1e-6
    assert r.estimate_played(1000) == r.audio_s
