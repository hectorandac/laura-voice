"""Is Hector talking to the assistant? Layered, cheapest first:

1. conversation window: a follow-up shortly after a reply needs no trigger;
2. the assistant's name anywhere in the transcript (Korean/English spellings and common ASR misspellings);
3. otherwise a one-token yes/no from the local LLM, with a threshold that depends on context
   (others around -> name required). When unsure, stay silent.
In the car (context.car) the gate always lets the turn through and the reply model decides whether to talk
(car_mode.py). Plus spoken mute / resume commands, which work everywhere.
"""
import re
import time

import httpx

EN_NAMES = {"laura", "lorra", "loura", "lorah", "laurah", "laora", "lowra", "lauro", "larua", "loira"}
EN_AMBIGUOUS = {"lora", "lara", "aura", "laure", "lauren", "laurie", "lola", "nora"}   # only as a vocative
VOCATIVE_BEFORE = {"hey", "hi", "hello", "ok", "okay", "yo", "thanks", "thank", "you", "so", "and", "um", "uh"}
KO_NAME_STARTS = ("로라", "라우라", "로우라", "라우러", "로러", "롤라", "러라", "로나야", "노라야", "로라아")

MUTE = re.compile(r"\b(stop listening|mute|go to sleep|don'?t listen|stop eavesdropping)\b|"
                  r"그만\s*들어|듣지\s*마|음소거|잠깐\s*꺼|조용히\s*있어", re.I)
RESUME = re.compile(r"\b(start listening|resume|wake up|listen again|unmute|you can listen)\b|"
                    r"다시\s*들어|다시\s*시작해|음소거\s*(해제|풀어)", re.I)
GREETING = re.compile(r"^(hey|hi|hello|good (morning|afternoon|evening)|안녕|안녕하세요|좋은 아침)\b[\s,.!?]*"
                      r"([\w' ]{0,25}[.!?]*)?$", re.I)   # a bare greeting with nobody else around
# ML jargon: "LoRA" said mid-sentence, or 로라 followed by what you do with an adapter, is not her name.
KO_JARGON = re.compile(r"로라\s*(어댑터|랭크|가중치|학습|튜닝|파인|모델|레이어|버전|병합|머지)")
EN_JARGON = re.compile(r"\b(q?loras?)\b", re.I)
COMMAND_MAX_WORDS = 7         # commands are short; longer sentences only mention the words


def _edit1(a, b):
    if abs(len(a) - len(b)) > 1:
        return False
    if len(a) == len(b):
        return sum(x != y for x, y in zip(a, b)) <= 1
    if len(a) > len(b):
        a, b = b, a
    i = 0
    while i < len(a) and a[i] == b[i]:
        i += 1
    return a[i:] == b[i + 1:]


def mentions_name(text: str) -> bool:
    low = text.lower()
    words = [(m.group(0).strip("'"), m.start(), m.end()) for m in re.finditer(r"[a-z']+", low)]
    for i, (w, a, b) in enumerate(words):
        if w.endswith("'s"):
            w = w[:-2]
        if w in EN_NAMES or (len(w) in (4, 5, 6) and _edit1(w, "laura") and w not in EN_AMBIGUOUS | {"maura", "lauda"}):
            return True
        if w in EN_AMBIGUOUS:
            before = words[i - 1][0] if i else ""
            after = low[b:b + 1]
            if i == 0 or i == len(words) - 1 or before in VOCATIVE_BEFORE or after in (",", "!", "?"):
                return True
    for m in re.finditer(r"[가-힣]+", text):
        if m.group(0).startswith(KO_NAME_STARTS) and not KO_JARGON.match(text, m.start()):
            return True
    return False


def lora_jargon(text: str) -> bool:
    """The utterance talks about LoRA (the fine-tuning method) rather than calling her."""
    return bool(KO_JARGON.search(text)) or (bool(EN_JARGON.search(text)) and not mentions_name(text))


_PET = r"(baby|babe|honey|sweetie|sweetheart|darling|mom|mommy|mum|dad|daddy|bro|dude|buddy|kiddo|guys)"
OTHER_PERSON = re.compile(
    rf"^(?:(?:oh|hi|hey|hello|bye|thanks|okay|ok|yes|no)[\s,!]+)*{_PET}\b|"          # leading vocative
    rf"\b(?:hi|hey|hello|bye|thanks|oh)[\s,]+{_PET}\b|(?<!my )(?<!your )(?<!the ){_PET}\s*,|,\s*{_PET}[.!?]*$|"
    r"\b(welcome (home|back)|you'?re (home|back)|(did|do) you just (arrive|get (home|here|back))|you made it|"
    r"how was (your|ur) (day|work|trip|flight))\b|"
    r"(?<![가-힣])(자기야|여보|애기야|얘들아)(?![가-힣])|"
    r"^(엄마|아빠|오빠|언니|누나|형|자기)\s*([,!?]|나\b|나는|이거|우리|왔)|"
    r"다녀왔어|잘 다녀왔|오늘 (하루 )?어땠어", re.I)
VOCATIVE_NAME = re.compile(r"^(?:(?i:hey|hi|hello|yo|oh)[\s,]+)?([A-Z][a-z]{2,})[,!?]")
NOT_NAMES = {"Hey", "Hi", "Hello", "Okay", "Ok", "Yes", "No", "Yeah", "Well", "So", "Oh", "Wait", "Sorry", "Thanks",
             "Please", "What", "Why", "How", "When", "Where", "Who", "And", "But", "Right", "Sure", "Hmm", "Um", "Uh",
             "Ugh", "Wow", "Damn", "Great", "Cool", "Nice", "Fine", "Alright", "Actually", "Also", "Then", "Now",
             "Look", "Listen", "Excuse", "Thank", "Good", "Perfect", "Exactly", "Really", "Seriously", "Anyway"}


def talks_to_someone_else(text: str) -> bool:
    """Pet names, family terms, someone arriving, or a vocative with a name that isn't the assistant's."""
    if OTHER_PERSON.search(text):
        return True
    m = VOCATIVE_NAME.match(text.strip())
    return bool(m and m.group(1) not in NOT_NAMES and not mentions_name(m.group(1)))


def command(text: str):
    if len(text.split()) > COMMAND_MAX_WORDS:
        return None
    if RESUME.search(text):
        return "resume"
    if MUTE.search(text):
        return "mute"
    return None


# "That was for you": answer the utterance the gate just ignored. Unambiguous phrases count as addressed on their
# own; the others need the name or the conversation window.
RECALL_SURE = re.compile(
    r"\b(that was for you|that one was for you|i was talking to you|i was asking you|i was speaking to you)\b|"
    r"(너|로라|라우라)한테\s*(한|했던|물어본)\s*(말|거|건|얘기)|"
    r"방금\s*(거|건|말|것)\s*(은|는)?\s*(너|로라)한테(\s*(한|했던)\s*(거|말|건))?", re.I)
RECALL_MAYBE = re.compile(
    r"\b(answer (that|it|this|me|the question)|respond to (that|it)|reply to (that|it)|you missed (that|it)|"
    r"(did|didn'?t) you hear (that|me)|go ahead and answer|that was a question)"
    r"(?=\s*(?:[,.!?]|$|please\b|laura\b|lora\b|now\b))|"
    r"(그거|그것|그 질문|방금 거|방금 질문)\s*(에)?\s*(대답|답)|대답\s*(해|좀|안 해|해줘|해 줘)|왜\s*대답\s*안|못\s*들었어", re.I)


def recall_intent(text: str):
    """-> None | "sure" | "maybe", and the leftover words (extra content to append)."""
    m = RECALL_SURE.search(text) or RECALL_MAYBE.search(text)
    if not m:
        return None, ""
    kind = "sure" if RECALL_SURE.search(text) else "maybe"
    rest = (text[:m.start()] + " " + text[m.end():])
    rest = re.sub(r"(?i)\b(hey|ok(ay)?|so|please|laura|lora|yeah|yes|no)\b|로라(야|씨|아)?|라우라|제발|좀", " ", rest)
    rest = re.sub(r"^[\s,.!?~]+|[\s,.!?~]+$", "", re.sub(r"\s+", " ", rest))
    rest = re.sub(r"^(이야|야|예요|에요|거야|말이야)$", "", rest)
    return kind, rest if len(rest.split()) >= 2 else ""


RECALL_SYSTEM = (
    "{name} is {user}'s voice assistant; she stays silent when she thinks he is talking to someone else. "
    "Is this utterance him telling {name} that what he said just before was meant for her, or asking her to answer "
    "it now (for example \"that was for you\", \"answer that\", \"방금 거 너한테 한 말이야\")? "
    "Answer with one word: yes or no.")


CLASSIFY_SYSTEM = (
    "You label transcripts from an always-on microphone in {user}'s home and car. {name} is his voice assistant "
    "(a smart speaker he talks to in Korean or English). Answer yes if the utterance is meant for {name}: any "
    "question or request an assistant could answer (facts, math, translations, recipes, timers, reminders, advice), "
    "or a reaction to what {name} just said. Answer no if he is clearly talking to another person (calls them "
    "honey, mom, babe, by name, or refers to shared plans), on the phone, muttering to himself, or if it sounds "
    "like TV, radio, a video or an announcer. Utterances may be Korean or English; judge both the same way.\n"
    "Examples: \"How far is Busan from here?\" yes. \"내일 비 와?\" yes. \"이거 영어로 뭐야?\" yes. "
    "\"Make it shorter.\" yes. \"안녕하세요\" yes. \"이거 뭐야?\" yes. \"Mom, I'm leaving now.\" no. \"아 진짜 왜 이래\" no. "
    "\"Coming up next, the evening news.\" no. \"네, 내일 연락드릴게요\" no.\n"
    "Answer with one word: yes or no.")


class AddresseeClassifier:
    def __init__(self, http, url, model, user="Hector", name="Laura"):
        self.http, self.url, self.model = http, url, model
        self.name = name
        self.system = CLASSIFY_SYSTEM.format(user=user, name=name)
        self.recall_system = RECALL_SYSTEM.format(user=user, name=name)

    async def p_addressed(self, text, last_reply=None, ago_s=None):
        """Probability that `text` is addressed to the assistant, from the first-token yes/no logprobs."""
        ctx = ""
        if last_reply:
            ctx = f"{self.name} last said ({ago_s:.0f} s ago): \"{last_reply[:200]}\"\n"
        return await self._yes_no(self.system, f"{ctx}Utterance: \"{text}\"\nSaid to the assistant?")

    async def p_recall(self, text, ignored):
        return await self._yes_no(self.recall_system, f"Before, he said: \"{ignored[:200]}\" (she stayed silent)\n"
                                                      f"Now he says: \"{text}\"\nAsking her to answer the earlier one?")

    async def _yes_no(self, system, user):
        body = {"model": self.model, "max_tokens": 1, "temperature": 0, "logprobs": True, "top_logprobs": 10,
                "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
                "chat_template_kwargs": {"enable_thinking": False}}
        t = time.time()
        for attempt in (0, 1):
            try:
                r = await self.http.post(self.url, json=body, timeout=5)
                break
            except httpx.RemoteProtocolError:     # llama-server dropped an idle keep-alive connection
                if attempt:
                    raise
        r.raise_for_status()
        d = r.json()["choices"][0]
        yes = no = 0.0
        try:
            for c in d["logprobs"]["content"][0]["top_logprobs"]:
                tok = c["token"].strip().lower()
                p = 2.718281828 ** c["logprob"]
                if tok in ("yes", "y", "예", "네"):
                    yes += p
                elif tok in ("no", "n", "아니요"):
                    no += p
        except (KeyError, IndexError, TypeError):
            word = (d["message"].get("content") or "").strip().lower()
            yes, no = (1.0, 0.0) if word.startswith("yes") else (0.0, 1.0)
        p = yes / (yes + no) if yes + no > 0 else 0.0
        return p, round((time.time() - t) * 1000)


class Gate:
    """Per-session addressee state and the decision."""

    WINDOW_S = 20.0
    THRESH = {"default": 0.6, "window": 0.25}

    def __init__(self, classifier: AddresseeClassifier | None):
        self.classifier = classifier
        self.reply_end = 0.0           # wall time when the last reply finishes playing on the client
        self.last_reply = ""
        self.muted = False

    def note_reply(self, text, ends_at):
        self.last_reply, self.reply_end = text, max(self.reply_end, ends_at)

    def in_window(self, now=None):
        return (now or time.time()) <= self.reply_end + self.WINDOW_S

    async def decide(self, text, context=None, others_present=False, started=None):
        """-> (addressed, info). info["why"]: name | car | greeting | window | classifier | resume | muted |
        not_addressed | other_person | others_present | classifier_error.
        In the car every turn is let through (why "car"; only mute holds her back) and the reply model decides
        whether to talk, so there is no window to expire and nothing to recall. The name always counts. Talking
        to someone else (pet names, family terms, another name, someone arriving) never does. Inside the follow-up window the classifier still runs but only a clear "no" (p < 0.25)
        rejects; with others around, outside the window only the name counts."""
        context = context or {}
        now = time.time()
        info = {"name": mentions_name(text), "window": self.in_window(started or now)}
        cmd = command(text)
        if cmd and (cmd == "resume") == self.muted:
            info["command"] = cmd
        else:
            cmd = None
        if self.muted:
            if cmd == "resume":
                return True, {**info, "why": "resume"}
            return False, {**info, "why": "muted"}
        if info["name"]:
            return True, {**info, "why": "name"}
        if context.get("car"):
            return True, {**info, "why": "car"}
        if talks_to_someone_else(text):
            return False, {**info, "why": "other_person"}
        others = others_present or bool(context.get("others_present"))
        if not others and GREETING.match(text.strip()):
            return True, {**info, "why": "greeting"}
        if others and not info["window"]:
            return False, {**info, "why": "others_present"}
        if not self.classifier:
            return info["window"], {**info, "why": "window" if info["window"] else "not_addressed"}
        th = self.THRESH["window" if info["window"] and not others else "default"]
        ago = now - self.reply_end
        try:
            p, ms = await self.classifier.p_addressed(text, self.last_reply if ago < 60 else None, max(ago, 0))
        except Exception as e:  # noqa: BLE001
            ok = info["window"] and not others
            return ok, {**info, "why": "window" if ok else "classifier_error", "error": str(e)[:100]}
        info.update(p=round(p, 3), classifier_ms=ms, threshold=th)
        if p >= th:
            return True, {**info, "why": "window" if info["window"] else "classifier"}
        return False, {**info, "why": "not_addressed"}
