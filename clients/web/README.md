# Voice assistant PWA client

Static, build-free client for the always-on voice assistant: `index.html`, `app.js`,
`capture-worklet.js` (AudioWorklet: device rate -> 16 kHz PCM16, 100 ms frames),
`styles.css`, `manifest.webmanifest`, `sw.js`, `icon.svg` / `icon-512.png`.

The backend serves these files and the WebSocket at `/ws` on the same origin.

## Secure context required

The microphone (`getUserMedia`), service worker and wake lock only work in a secure
context: `https://` (or `http://localhost`). On the LAN the backend must serve the
client over HTTPS with a certificate the phone trusts (or that you click through once).
The socket follows the page: `wss://<host>/ws` when the page is https.

## Mock server

```sh
pip install aiohttp
python3 mock_server.py                 # http://localhost:8765/
python3 mock_server.py --https         # self-signed cert, for phone testing on the LAN
python3 mock_server.py --audio-seconds 12   # long fake TTS reply, to try barge-in
```

It prints the LAN URL. With `--https` accept the certificate warning on the phone, then
"Add to Home Screen" / "Install app". `--cert/--key` use your own certificate.

The mock answers `hello` with `session` + `state`, turns incoming audio energy into fake
partials, finalizes on `end_turn` (push-to-talk) or ~1 s of low energy (hands-free),
streams a canned reply (alternating Korean / English, or forced by the language setting)
followed by a PCM16 beep "TTS" stream, and cancels the reply on `interrupt`.

## Notes

- Mic constraints: echo cancellation on, noise suppression and AGC off (the backend runs
  its own level control).
- Barge-in: while reply audio is playing in hands-free mode, 3 of the last 5 mic frames
  above -34 dBFS stop playback locally and send `{"type":"interrupt"}`. Tune
  `BARGE_RMS` in `app.js` on real devices.
- `buildContext()` in `app.js` is the single place that builds the `context` object;
  `window.voiceAssistant.sendContext(data)` sends a `context` message.
- iOS: the page sets `navigator.audioSession.type = "play-and-record"` where available.
  Browsers suspend mic capture when the page is in the background or the screen locks;
  the wake lock keeps the screen on while the assistant is active.
