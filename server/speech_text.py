"""Make LLM text speakable before TTS: numbers, decimals, percents, times, units and symbols become words.

English uses num2words. Korean uses Sino-Korean readings (삼 점 오, 이십오 퍼센트), and native numbers
(한, 두, 세 ...) before native counters such as 시, 개, 명, 살.
"""
import re

from num2words import num2words

KO_DIGITS = "영일이삼사오육칠팔구"
KO_UNITS = [(10 ** 12, "조"), (10 ** 8, "억"), (10 ** 4, "만")]
KO_NATIVE = {1: "한", 2: "두", 3: "세", 4: "네", 5: "다섯", 6: "여섯", 7: "일곱", 8: "여덟", 9: "아홉", 10: "열",
             20: "스물", 30: "서른", 40: "마흔", 50: "쉰", 60: "예순", 70: "일흔", 80: "여든", 90: "아흔"}
KO_NATIVE_COUNTERS = ("시간", "시", "개", "명", "살", "번째", "번", "마리", "잔", "권", "대", "가지", "달", "곳", "병", "장")


def ko_sino_small(n: int) -> str:
    out = ""
    for value, name in ((1000, "천"), (100, "백"), (10, "십")):
        d, n = divmod(n, value)
        if d:
            out += ("" if d == 1 else KO_DIGITS[d]) + name
    if n:
        out += KO_DIGITS[n]
    return out


def ko_sino(n: int) -> str:
    if n == 0:
        return "영"
    out = ""
    for value, name in KO_UNITS:
        d, n = divmod(n, value)
        if d:
            out += ("" if d == 1 and name == "만" else ko_sino_small(d)) + name + " "
    out += ko_sino_small(n)
    return out.strip()


def ko_native(n: int) -> str:
    if n == 20:
        return "스무"
    tens, ones = divmod(n, 10)
    return (KO_NATIVE.get(tens * 10, "") if tens else "") + (KO_NATIVE.get(ones, "") if ones else "")


def ko_number(s: str) -> str:
    s = s.replace(",", "")
    if "." in s:
        whole, frac = s.split(".", 1)
        return ko_sino(int(whole or 0)) + " 점 " + " ".join(KO_DIGITS[int(c)] for c in frac)
    return ko_sino(int(s))


def en_number(s: str) -> str:
    s = s.replace(",", "")
    try:
        return num2words(float(s) if "." in s else int(s))
    except (ValueError, OverflowError):
        return s


UNITS_EN = {"km": "kilometers", "kg": "kilograms", "cm": "centimeters", "mm": "millimeters", "m": "meters",
            "g": "grams", "GB": "gigabytes", "MB": "megabytes", "TB": "terabytes", "km/h": "kilometers per hour",
            "°C": "degrees Celsius", "℃": "degrees Celsius", "°F": "degrees Fahrenheit", "h": "hours", "min": "minutes",
            "ms": "milliseconds", "W": "watts"}
UNITS_KO = {"km": "킬로미터", "kg": "킬로그램", "cm": "센티미터", "mm": "밀리미터", "m": "미터", "g": "그램",
            "GB": "기가바이트", "MB": "메가바이트", "TB": "테라바이트", "km/h": "킬로미터", "°C": "도", "℃": "도",
            "°F": "화씨", "h": "시간", "min": "분", "ms": "밀리초", "W": "와트"}
NUM = r"\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?"
UNIT_RE = "|".join(sorted((re.escape(u) for u in UNITS_EN), key=len, reverse=True))

EMOJI = re.compile("[\U0001F300-\U0001FAFF☀-➿️]")


def speakable(text: str, lang: str) -> str:
    t = EMOJI.sub("", text)
    t = re.sub(r"[*_#`>|~]+", "", t)                    # markdown
    t = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", t)      # links
    t = t.replace("&", " and " if lang == "en" else " 그리고 ")
    if lang == "ko":
        t = re.sub(r"(\d{1,2}):(\d{2})", lambda m: f"{ko_native(int(m[1]))} 시 {ko_sino(int(m[2]))} 분"
                   if 0 < int(m[1]) < 25 else m[0], t)
        t = re.sub(rf"({NUM})\s*%", lambda m: ko_number(m[1]) + " 퍼센트", t)
        t = re.sub(rf"({NUM})\s*({UNIT_RE})(?![A-Za-z])", lambda m: ko_number(m[1]) + " " + UNITS_KO[m[2]], t)
        t = re.sub(rf"(\d{{1,2}})\s*(?=({'|'.join(KO_NATIVE_COUNTERS)}))",
                   lambda m: ko_native(int(m[1])) + " " if 0 < int(m[1]) < 100 else m[0], t)
        t = re.sub(r"\b(\d)\s*[-~]\s*(\d)(?=\s*[가-힣])", lambda m: ko_sino(int(m[1])) + ko_sino(int(m[2])) + " ", t)
        t = re.sub(rf"({NUM})\s*[-~]\s*({NUM})", lambda m: ko_number(m[1]) + "에서 " + ko_number(m[2]), t)
        t = re.sub(NUM, lambda m: ko_number(m[0]), t)
    else:
        t = re.sub(r"\b(\d{1,2}):(\d{2})\b", lambda m: num2words(int(m[1])) + " " +
                   ("o'clock" if m[2] == "00" else ("oh " + num2words(int(m[2])) if m[2][0] == "0" else num2words(int(m[2])))), t)
        t = re.sub(rf"\$({NUM})", lambda m: en_number(m[1]) + " dollars", t)
        t = re.sub(rf"({NUM})\s*%", lambda m: en_number(m[1]) + " percent", t)
        t = re.sub(rf"({NUM})\s*({UNIT_RE})(?![A-Za-z])", lambda m: en_number(m[1]) + " " + UNITS_EN[m[2]], t)
        t = re.sub(rf"\b(\d+)(st|nd|rd|th)\b", lambda m: num2words(int(m[1]), to="ordinal"), t)
        t = re.sub(rf"({NUM})\s*[-–]\s*({NUM})", lambda m: en_number(m[1]) + " to " + en_number(m[2]), t)
        t = re.sub(r"\b(1[1-9]\d\d|20\d\d)\b(?![.,]\d)", lambda m: num2words(int(m[1]), to="year"), t)
        t = re.sub(NUM, lambda m: en_number(m[0]), t)
    return re.sub(r"\s+", " ", t).strip()


if __name__ == "__main__":
    for s, l in [("Python 3.5 came out in 2015, about 25% faster.", "en"), ("It's 3:30, 18°C, and 2.4 km away.", "en"),
                 ("파이썬 3.5는 2015년에 나왔고 25% 빨라요.", "ko"), ("지금 3시 30분이고, 기온은 18℃, 2.4 km 남았어요.", "ko"),
                 ("사과 3개, 사람 5명, 2-3분 걸려요.", "ko"), ("**Tip:** 1st try 😀", "en")]:
        print(f"{s!r}\n   -> {speakable(s, l)!r}")
