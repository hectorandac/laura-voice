"""All three on the GPU: Nemotron streaming ASR (in-process) + Bonsai 2 9B (llama-server) + Supertonic (tts_server).
Round trip per question: end of speech -> final transcript -> LLM first sentence -> TTS audio for it."""
import json, re, subprocess, sys, time, urllib.request
from pathlib import Path
import numpy as np, torch, torchaudio
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from stt import Leveler, load_recognizer

LLM = 'http://127.0.0.1:8090/v1/chat/completions'; TTS = 'http://127.0.0.1:8091/synth'
def smi():
    return subprocess.run(['nvidia-smi', '--query-gpu=memory.used,memory.total', '--format=csv,noheader'], capture_output=True, text=True).stdout.strip()
def post(url, obj, raw=False):
    req = urllib.request.Request(url, json.dumps(obj).encode(), {'Content-Type': 'application/json'})
    r = urllib.request.urlopen(req, timeout=300)
    return r if raw else (r.read(), r.headers)

def synth(text, lang):
    t = time.time(); body, h = post(TTS, {'text': text, 'lang': lang}); return body, int(h['X-Sample-Rate']), time.time() - t

rec = load_recognizer("cuda")
torch.cuda.empty_cache()
post(LLM, {'model': 'bonsai-9b-voice', 'messages': [{'role': 'user', 'content': 'hi'}], 'max_tokens': 1,
           'chat_template_kwargs': {'enable_thinking': False}})
print('GPU with ASR + 9B + TTS loaded:', smi(), flush=True)

SYS = ('You are a voice assistant. Answer in the same language as the user, in one to three short spoken sentences, '
       'no markdown or lists.')
questions = [('en', 'What is a good way to stay focused when working from home? Give me a short answer.'),
             ('ko', '집에서 일할 때 집중력을 유지하는 좋은 방법이 뭐야? 짧게 대답해 줘.')]
for lang, q in questions:
    pcm, rate, _ = synth(q, lang)
    audio = torch.from_numpy(np.frombuffer(pcm, '<i2').astype(np.float32) / 32768)
    audio = torchaudio.functional.resample(audio, rate, 16000).numpy()
    for asr_lang in (lang, 'auto'):
        s = rec.utterance(asr_lang); agc = Leveler()
        for i in range(0, audio.size, 1600):
            s.feed(agc.apply(audio[i:i + 1600]))
        t_end = time.time(); s.feed(agc.drain()); text = s.close(); t_asr = time.time() - t_end
        t = time.time(); first = None; reply = ''
        r = post(LLM, {'model': 'bonsai-9b-voice', 'stream': True, 'max_tokens': 120, 'temperature': 0.6,
                       'messages': [{'role': 'system', 'content': SYS}, {'role': 'user', 'content': text}],
                       'chat_template_kwargs': {'enable_thinking': False}}, raw=True)
        for line in r:
            line = line.decode().strip()
            if not line.startswith('data: ') or line.endswith('[DONE]'):
                continue
            reply += json.loads(line[6:])['choices'][0]['delta'].get('content') or ''
            m = re.search(r'^(.+?[.!?。]|.+?(요|다|까)[.!?]?\s)', reply)
            if first is None and m:
                first = (m.group(0).strip(), time.time() - t)
        t_llm_total = time.time() - t
        sent, t_first = first or (reply, t_llm_total)
        _, _, t_tts = synth(sent, lang)
        print(f'[{lang} asr={asr_lang}] heard: {text!r}\n   reply: {reply.strip()!r}\n   end-of-speech->text {t_asr*1000:.0f} ms | '
              f'LLM first sentence {t_first*1000:.0f} ms (full {t_llm_total*1000:.0f} ms) | TTS first sentence {t_tts*1000:.0f} ms | '
              f'=> first audio ready {(t_asr + t_first + t_tts)*1000:.0f} ms', flush=True)
print('GPU at end:', smi())
