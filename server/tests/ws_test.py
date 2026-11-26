"""Drive the assistant like a phone: stream a spoken question in real time over /ws (hands-free),
measure end of speech -> first reply audio, and test barge-in on a second turn."""
import asyncio, json, sys, time, urllib.request, wave
import numpy as np, websockets

TTS = 'http://127.0.0.1:8091/synth'
def speech(text, lang):
    req = urllib.request.Request(TTS, json.dumps({'text': text, 'lang': lang}).encode(), {'Content-Type': 'application/json'})
    r = urllib.request.urlopen(req)
    x = np.frombuffer(r.read(), '<i2').astype(np.float32); rate = int(r.headers['X-Sample-Rate'])
    idx = np.arange(0, x.size, rate / 16000)
    return np.interp(idx, np.arange(x.size), x).astype('<i2')

async def run():
    qs = [('en', 'Hey, what should I cook tonight if I only have eggs, rice and some kimchi?'),
          ('ko', '내일 아침에 일찍 일어나려면 오늘 밤에 뭘 하면 좋을까?')]
    silence = np.zeros(1600, '<i2')
    import os, ssl
    ctx = ssl.create_default_context(); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
    async with websockets.connect(os.environ['WS_URL'], max_size=None, ssl=ctx) as ws:
        await ws.send(json.dumps({'type': 'hello', 'version': 1, 'client': 'test', 'lang': 'auto', 'mode': 'handsfree', 'context': {}}))
        for lang, q in qs:
            audio = speech(q, lang)
            for f in [silence] * 5 + [audio[i:i + 1600] for i in range(0, audio.size, 1600)]:
                await ws.send(f.tobytes()); await asyncio.sleep(0.1)
            t_end = time.time()
            got_audio, pcm, events = None, b'', []
            async def pump():
                while True:  # keep streaming silence like a live mic
                    await ws.send(silence.tobytes()); await asyncio.sleep(0.1)
            p = asyncio.create_task(pump())
            while True:
                m = await asyncio.wait_for(ws.recv(), 30)
                if isinstance(m, bytes):
                    if got_audio is None: got_audio = time.time() - t_end
                    pcm += m; continue
                d = json.loads(m); events.append(d['type'])
                if d['type'] == 'user_turn': print(f'[{lang}] heard: {d["text"]!r} ({d["lang"]}) at +{(time.time()-t_end)*1000:.0f} ms')
                if d['type'] == 'assistant_done': print(f'   reply: {d["text"]!r}')
                if d['type'] == 'audio_start': rate = d['sample_rate']
                if d['type'] == 'audio_end': break
                if d['type'] == 'error': print('ERROR', d); break
            p.cancel()
            print(f'   end of speech -> first reply audio: {got_audio*1000:.0f} ms; reply audio {len(pcm)/2/rate:.1f}s; events {sorted(set(events))}')
            with wave.open(f'reply_{lang}.wav', 'wb') as w:
                w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate); w.writeframes(pcm)
        # barge-in: ask a long question, interrupt as soon as audio starts
        audio = speech('Tell me a long story about a dragon who learns to cook.', 'en')
        for f in [audio[i:i + 1600] for i in range(0, audio.size, 1600)] + [silence] * 12:
            await ws.send(f.tobytes()); await asyncio.sleep(0.1)
        while True:
            m = await asyncio.wait_for(ws.recv(), 30)
            if isinstance(m, bytes):
                await ws.send(json.dumps({'type': 'interrupt'})); t = time.time(); break
        msgs, audio_after, last_delta = [], 0, None
        try:
            while True:
                m = await asyncio.wait_for(ws.recv(), 3)
                if isinstance(m, bytes): audio_after += len(m); continue
                d = json.loads(m); msgs.append(d['type'] if d['type'] != 'state' else 'state:' + d['value'])
                if d['type'] == 'assistant_delta': last_delta = time.time() - t
        except asyncio.TimeoutError:
            pass
        from collections import Counter
        print('barge-in: after interrupt', dict(Counter(msgs)), 'audio bytes', audio_after,
              'last delta at +%d ms' % ((last_delta or 0) * 1000), 'final:', msgs[-3:])

asyncio.run(run())
