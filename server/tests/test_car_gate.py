"""Car mode: the gate never drops her out of the conversation, and the reply model's silence is swallowed."""
import asyncio

import addressee
import car_mode
import pytest
from car_mode import SILENT_TOKEN, silence


class CountingClassifier:
    """Stands in for the LLM yes/no; says "no" to everything so any pass-through is the gate's doing."""

    def __init__(self, p=0.0):
        self.p, self.calls = p, 0

    async def p_addressed(self, text, last_reply=None, ago_s=None):
        self.calls += 1
        return self.p, 1


def decide(gate, text, context=None, others=False):
    return asyncio.run(gate.decide(text, context, others))


CAR = {"car": True}
OVERHEARD = [
    "Honey, can you grab my jacket?",          # pet name: other_person outside the car
    "Mom, I'm leaving now.",
    "아 진짜 왜 이래",                            # muttering
    "Coming up next, the evening news.",       # radio
    "What's the capital of Australia?",
    "Turn left at the next light, right?",
]


@pytest.mark.parametrize("text", OVERHEARD)
def test_car_lets_every_turn_through(text):
    clf = CountingClassifier(p=0.0)
    gate = addressee.Gate(clf)
    gate.reply_end = 0                           # long after the last reply: no window
    ok, info = decide(gate, text, CAR, others=True)
    assert ok and info["why"] in ("car", "name")
    assert clf.calls == 0                        # the reply model decides, not the yes/no classifier


def test_car_has_no_window_to_expire():
    gate = addressee.Gate(CountingClassifier(p=0.0))
    gate.note_reply("It will be sunny.", 1.0)    # a reply that ended long ago
    assert not gate.in_window()
    ok, info = decide(gate, "and what about Sunday", CAR)
    assert ok and info["why"] == "car" and not info["window"]


def test_car_name_still_counts():
    ok, info = decide(addressee.Gate(None), "Laura, how far is Busan?", CAR)
    assert ok and info["why"] == "name"
    ok, info = decide(addressee.Gate(None), "로라, 부산까지 얼마나 걸려?", CAR)
    assert ok and info["why"] == "name"


def test_car_mute_still_works():
    gate = addressee.Gate(None)
    ok, info = decide(gate, "Laura, stop listening", CAR)
    assert ok and info.get("command") == "mute"
    gate.muted = True
    ok, info = decide(gate, "What's the capital of Australia?", CAR)
    assert not ok and info["why"] == "muted"
    ok, info = decide(gate, "Laura, start listening", CAR)
    assert ok and info["why"] == "resume"


@pytest.mark.parametrize("context", [None, {}, {"car": False}])
def test_outside_car_unchanged(context):
    gate = addressee.Gate(CountingClassifier(p=0.1))
    gate.reply_end = 0
    ok, info = decide(gate, "Honey, can you grab my jacket?", context)
    assert not ok and info["why"] == "other_person"
    ok, info = decide(gate, "What's the capital of Australia?", context)
    assert not ok and info["why"] == "not_addressed" and info["threshold"] == 0.6
    ok, info = decide(gate, "What's the capital of Australia?", context, others=True)
    assert not ok and info["why"] == "others_present"
    ok, info = decide(addressee.Gate(None), "What's the capital of Australia?", context)
    assert not ok and info["why"] == "not_addressed"


def stream(tokens):
    """Feed a token split the way the gateway does; -> (verdict, characters held before deciding)."""
    buf = ""
    for t in tokens:
        buf += t
        v = silence(buf, final=False)
        if v:
            return v, len(buf)
    return silence(buf, final=True), len(buf)


@pytest.mark.parametrize("tokens", [
    [SILENT_TOKEN],
    ["<", "sil", "ent", ">"],
    [" <silent>", "\n"],
    ["<silent>", " Sorry."],
    ["[silent]"],
    ["<SILENCE>"],
    ["(stay silent)"],
    ["<e:none>", "<silent>"],
    [],
    ["", " ", "\n"],
    ["..."],
    ["I'm not sure", " that was for me."],
    ["It sounds like you're", " talking to someone else."],
    ["Were you talking to me?"],
    ["Sounds like you're on the phone."],
    ["혹시 저한테 하신 말씀이에요?"],
    ["저한테", " 하신 말씀인가요?"],
])
def test_silent_replies_are_swallowed(tokens):
    assert stream(tokens)[0] == "silent"


@pytest.mark.parametrize("tokens", [
    ["Sure", ", it's about two hours."],
    ["Canberra", "."],
    ["네", ", 두 시간 정도 걸려요."],
    ["<r:nod>", "Okay."],
    ["I'm not sure", ", but I think it closes at nine."],
    ["That wasn't", " far, about ten minutes."],
    ["저한테", "맡겨 주세요."],
    ["Three", " point five kilometres."],
    ["(laughs) Sure."],
])
def test_spoken_replies_pass(tokens):
    assert stream(tokens)[0] == "speak"


def test_ordinary_replies_are_not_held_back():
    assert stream(["Sure", ", it's about two hours."])[1] == 4
    assert stream(["네", ", 좋아요."])[1] == 1
    assert stream(["Canberra"])[1] == len("Canberra")


def test_overheard_note():
    items = []
    for i, t in enumerate(["one", "two", "three", "four"]):
        items = car_mode.keep_overheard(items, t, now=100.0 + i)
    assert [t for _, t in items] == ["two", "three", "four"]
    note = car_mode.overheard_note(items, "Hector", now=110.0)
    assert '"two", "three", "four"' in note and "Hector" in note
    assert car_mode.overheard_note(items, "Hector", now=1000.0) == ""
    items = car_mode.keep_overheard(items, "five", now=1000.0)
    assert [t for _, t in items] == ["five"]


def test_rules_never_ask_if_it_was_for_her():
    rules = car_mode.RULES.format(user="Hector")
    assert SILENT_TOKEN in rules and "Never say you weren't sure" in rules
