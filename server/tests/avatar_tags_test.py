"""Leading <r:...>/<e:...> tags are peeled off a streamed reply whatever the token split. python tests/avatar_tags_test.py"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from assistant_server import ANY_TAG, Session  # noqa: E402


def run(tokens, car=False):
    s = Session.__new__(Session)
    s.context, s.avatar_cue = {"car": car}, None
    lead, tags, out, hold, deltas = "", {}, "", "", []
    for d in tokens:
        if lead is not None:
            lead, d = s._lead_tags(lead + d, tags, final=False)
            if lead is not None or not d:
                continue
        d, hold = s._hold_partial_tag(hold + d, final=False)
        out += d
        deltas.append(d)
    rest = hold
    if lead:
        rest = s._lead_tags(lead, tags, final=True)[1]
    out += s._hold_partial_tag(rest, final=True)[0]
    assert not any(re.search(r"</?[A-Za-z_]", d) for d in deltas), deltas
    return s.avatar_cue, out


cases = [
    (["<r:nod>", " Sure", ", I can."], {"reaction": "nod"}, "Sure, I can."),
    (["<", "r", ":", "la", "ugh", ">", "Ha", " that's funny."], {"reaction": "laugh"}, "Ha that's funny."),
    (["<r:happy><e:happy>", "네", ", 좋아요."], {"reaction": "happy", "emotion": "happy"}, "네, 좋아요."),
    (["<r:dance>", "Okay."], None, "Okay."),
    (["<r:nod><r:wave>", "Hi."], {"reaction": "nod"}, "Hi."),
    (["Sure", " thing."], None, "Sure thing."),
    ([" ", "<e:apolo", "getic>", " Sorry."], {"emotion": "apologetic"}, "Sorry."),
    (["<r:nod>", "Okay."], None, "Okay."),                      # car mode: no reactions
    (["<r:no", "d"], None, ""),                                 # stream ends inside a tag
    (["Use <r:wave> it", " now."], None, "Use  it now."),
    (["<thinking_hard>", "\n", "Hmm."], {"reaction": "thinking_hard"}, "Hmm."),
    (["<b>bold"], None, "bold"),
    (["<focus>", " About an hour. </focus>"], {"reaction": "focus"}, "About an hour. "),
    (["<happy>", " Wow. </thinking_ha"], {"reaction": "happy"}, "Wow. "),
    (["Sure", ". About an hour. <", "/fo", "cus", ">"], None, "Sure. About an hour. "),
    (["Use <", "r:wave> it."], None, "Use  it."),
    (["3 < 5 is true, everyone knows that."], None, "3 < 5 is true, everyone knows that."),
]
ok = True
for i, (tokens, cue, text) in enumerate(cases):
    got = run(tokens, car=(i == 7))
    good = got == (cue, text)
    ok &= good
    print("PASS" if good else "FAIL", tokens, "->", got)
sys.exit(0 if ok else 1)
