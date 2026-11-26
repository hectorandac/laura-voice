"""Car mode (context.car): she stays in the conversation and the LLM decides when to talk.

Every utterance that passes the owner check goes to the LLM. When it isn't for her, the model answers with
SILENT_TOKEN (or nothing) and the gateway swallows the reply: no user_turn, no ignored event, no state change.
Replies that open by telling Hector it wasn't meant for her are swallowed the same way.
"""
import re
import time

SILENT_TOKEN = "<silent>"
OVERHEARD_KEEP, OVERHEARD_MAX_AGE_S = 3, 90.0

RULES = (
    "In the car you hear everything, not only what is meant for you: passengers talking to each other, the radio, "
    "phone calls, {user} thinking aloud or singing along. Answer when he talks to you (your name, a question or "
    "request an assistant could help with, a follow-up to what you just said) or when a short answer is clearly "
    "useful to him. Otherwise reply with exactly " + SILENT_TOKEN + " and nothing else: it is never spoken and you "
    "simply keep listening. Never say you weren't sure it was for you, never ask whether he was talking to you, "
    "never comment on staying quiet. When you answer, one short sentence is best.")

_SILENT = re.compile(r"[<\[(]\s*(?:silent|silence|quiet|no[ _-]?reply|stay[ _-]?silent)\s*[>\])]", re.I)
_LEAD_TAG = re.compile(r"\s*<[re]:\s*[A-Za-z_]+\s*>")
_OPENERS = ("<", "[", "(")
_CLOSERS = (">", "]", ")")
_BRACKET_WAIT = 20
# Replies that announce she isn't being addressed. Openers are matched as prefixes so ordinary replies are
# released after a few characters; only replies that start like one of these wait for their first sentence.
NOT_FOR_ME_LEADS = (
    "i'm not sure", "i am not sure", "i wasn't sure", "i was not sure", "were you talking", "were you asking",
    "are you talking", "are you asking", "was that for me", "is that for me", "was that meant", "it sounds like you",
    "sounds like you", "it seems like you", "seems like you", "that wasn't", "that was not", "that didn't sound",
    "저한테", "저에게", "혹시 저", "제게",
)
NOT_FOR_ME = re.compile(
    r"\b(?:that|this|it)\s+(?:was|is|wasn'?t|isn'?t)\s+(?:really\s+)?(?:meant\s+|intended\s+)?for me\b|"
    r"\b(?:was|is) (?:that|this|it) (?:meant |intended )?for me\b|\b(?:meant|intended) for me\b|\b(?:talking|speaking) to (?:me|someone|somebody|your|a |the )|"
    r"\basking me\b|\bsomeone else\b|\bsomebody else\b|\bon the phone\b|\bthinking (?:out loud|aloud)\b|"
    r"(?:저한테|저에게|제게)\s*(?:하신|한|물어보신|말씀)|다른\s*분(?:과|이랑|하고|께|한테)", re.I)
_SENTENCE_END = re.compile(r"[.!?。！？]|[요다까죠]\s")
_LEAD_HOLD_CHARS = 120


def silence(buf: str, final: bool):
    """Decide from the start of a streamed reply. -> "silent", "speak", or None (wait for more text)."""
    body = buf
    while (m := _LEAD_TAG.match(body)):        # an avatar tag before the decision does not count
        body = body[m.end():]
    body = body.strip()
    if not body or not re.search(r"\w", body):
        return "silent" if final or (body and len(body) > 8) else None
    if _SILENT.match(body):
        return "silent"
    if body.startswith(_OPENERS) and not any(c in body for c in _CLOSERS):
        if not final and len(body) < _BRACKET_WAIT:
            return None
    low = body.lower()
    for lead in NOT_FOR_ME_LEADS:
        if not final and len(low) < len(lead) and lead.startswith(low):
            return None
        if low.startswith(lead):
            m = _SENTENCE_END.search(body)
            if not m and not final and len(body) < _LEAD_HOLD_CHARS:
                return None
            first = body[:m.end()] if m else body
            return "silent" if NOT_FOR_ME.search(first) else "speak"
    return "speak"


def keep_overheard(items, text, now=None):
    """Remember an utterance she stayed silent for (Hector may follow up with "answer that")."""
    now = now or time.time()
    return [x for x in items if now - x[0] <= OVERHEARD_MAX_AGE_S][-(OVERHEARD_KEEP - 1):] + [(now, text)]


def overheard_note(items, user, now=None):
    """System-prompt line listing what she recently stayed silent for, or "" when there is nothing recent."""
    now = now or time.time()
    recent = [t for at, t in items if now - at <= OVERHEARD_MAX_AGE_S]
    if not recent:
        return ""
    quoted = ", ".join('"' + t[:200].replace('"', "'") + '"' for t in recent)
    return (f"Overheard in the car just before, and you stayed silent (oldest first): {quoted}. If {user} now asks "
            "you to answer or react to one of these, do.")
