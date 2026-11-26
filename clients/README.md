# Clients

Every client speaks the same protocol (`docs/protocol.md`): one WebSocket, JSON control messages, Opus or
PCM16 audio, a client-side speech gate so silence costs nothing, barge-in, and the takeover rule (close
code 4000 means another device has the conversation; don't reconnect on your own).

| Client | Path | What it is |
|---|---|---|
| Web (PWA) | `web/` | Build-free test client, served by the gateway. Unchanged here. |
| Android phone/tablet | `android/` | Kotlin + Compose app with a microphone foreground service, so it keeps listening with the screen off. Also relays the watch. |
| Wear OS watch | `wear/` | Push-to-talk (or hands-free) on the watch; audio goes through the phone app over the Wearable Data Layer, or straight to the gateway. |
| Linux speaker | `linux/` | Headless Python client for a Jetson TX2 or any small Linux box: ALSA/PulseAudio, systemd unit, config file. |

## Connecting to a self-signed gateway

The gateway uses a self-signed certificate. No client disables TLS checks; they pin the certificate's SHA-256
instead (the pin replaces CA and hostname checks, since the gateway is reached by IP):

```sh
python3 clients/linux/laura_voice.py fingerprint wss://<gateway>:8443   # prints sha256/<hex>
# or: openssl s_client -connect <gateway>:8443 </dev/null | openssl x509 -noout -fingerprint -sha256
```

The Android app also has "Fetch and trust server certificate", which shows the fingerprint for you to compare
before pinning it. To set up the phone in one step, make a QR code of
`wss://<gateway>:8443/ws?k=<access key>&pin=<hex>` (for example `qrencode -t ansiutf8 '<url>'`) and use
"Scan QR" in settings. The pin and key stay on the device; only `k` is sent, as the gateway requires.

## Android (`android/`, Gradle root for `:core`, `:app` and `:wear`)

`core/` holds everything shared by the phone and the watch: protocol, speech gate, Opus codec, routing,
WebSocket transport with pinning, the relay framing and the `VoiceEngine` itself. `app/` is the phone UI,
the foreground service and the watch relay.

Build (JDK 17+; Android Studio's bundled JBR works):

```sh
cd clients/android
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
JAVA_HOME=~/android-studio-2024.2.1.9-linux/android-studio/jbr ./gradlew :core:testDebugUnitTest :app:assembleDebug :wear:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk, ../wear/build/outputs/apk/debug/wear-debug.apk
```

Both APKs use the application id `dev.hect.lauravoice` and must be signed with the same key (debug builds from
the same machine with the same `~/.android/debug.keystore` are), because the Data Layer only pairs matching
apps. A build signed with a different key can't `install -r` over the old one; uninstalling loses the settings.

Without a local SDK, the same build runs in Docker (JDK 21 + `platforms;android-36` + `build-tools;35.0.0`)
with the project mounted and `ANDROID_USER_HOME` pointing at a persistent `.android` directory so the debug
key stays stable.

Install on the phone over USB (`adb install -r app-debug.apk`) or wireless debugging: Settings → Developer
options → Wireless debugging → Pair device with pairing code, then `adb pair <ip>:<pair-port>` (enter the code),
`adb connect <ip>:<port>`, `adb install -r app-debug.apk`.

The main screen is only the voxel avatar (`docs/avatar/`), full screen and edge to edge, in a WebView:
`app/src/main/assets/avatar` is built from the prototype by `tools/make_avatar_asset.py` with three.js r160
bundled locally (no CDN), served through `WebViewAssetLoader`, cheap glow only (bloom washes the canvas grey in
WebView), paused when the app isn't visible. The engine feeds it state, mic level, reply loudness, car mode and
one-shot events. A small label at the bottom says what the avatar can't (connecting, offline, who is answering:
"Local · Bonsai 9B" or "Claude Sonnet 5.5 · via Hermes") and fades after 3 s; problems that need a tap (certificate,
key, in use elsewhere, muted) stay. Approval requests, a "For Laura" chip for an ignored utterance and running tasks
appear above it only while they exist. If the page isn't ready within 3 s an orb is drawn instead. Dragging rotates
the avatar (horizontal = yaw with fling, vertical = pitch within ±35°; it eases back to the front after 4 s); a tap
mutes/unmutes in hands-free mode, stops a reply while she speaks, and retries or opens settings when something is
wrong; in push-to-talk mode holding still for 150 ms talks.

Opening the app turns Laura on (the first time it asks for the microphone; with no server set it opens Settings on
a welcome card: scan the setup QR code or enter the server by hand).

Settings slide up over the avatar as a sheet. Ways in: the small round button at the bottom right of the avatar
screen, a swipe up from the bottom edge, a long-press on the avatar (hands-free), the launcher icon's "Settings"
shortcut (long-press the icon), tapping a problem in the status label, and saying "Laura, open settings" /
"로라, 설정 열어줘". The voice command is matched on the phone (`core/VoiceCommands`, whole short utterances only, so
"what are the best camera settings" is still answered): the app opens settings, drops the gateway's reply to that
turn with an `interrupt` and nods. From another app this needs "Display over other apps" (Android only lets an app
with it start an activity from the background); without it she just answers the turn. On the first three visits a
hint next to the button explains all of this, until settings has been opened once.

The home page holds what changes day to day and one row per rarer area, each its own page: a status card (connection,
Laura link, host; opens Server: scan QR or paste URL, key, pin; Save & connect), Conversation (hands-free or
push-to-talk, language), Your voice (enrollment), Car (Automatic, Always on, Off; car Bluetooth; Android Auto
animation), Watch (reply volume), Call her from anywhere (invocation setup and floating Laura) and About. Tapping the
version seven times shows Developer on the About page: audio route, codec, live stats, turn off/reconnect, send
settings to the watch and the conversation log. Settings follow the system light/dark theme (the avatar screen stays
dark); the design, stills and emulator screenshots are in `docs/app/settings-design/`. Everything but the connection
fields applies immediately. The notification has Mute, Stop reply and Turn off. Mute stops sending audio and also tells
the gateway (`mute`). The app also handles the gate and Laura additions from the protocol: `interrupted` stops that
reply, `ignored` offers "For Laura" (on screen and as a notification action) for 60 s, which sends `recall`, task
cards can be cancelled, and approval requests get a card whose buttons are the only way an `approval_response` is
ever sent.

Settings migration (`App.migrate`, `prefs_version` 1): existing installs are moved once to hands-free, the wake word
keys are dropped (and the downloaded model in `files/wake` deleted), and the old manual car switch becomes
`car_mode=auto`. `car` stays the effective car state that the engine, overlay and watch read.

### Choices

- **Opus via MediaCodec.** Android 10+ (minSdk 29) ships `c2.android.opus.encoder` and an Opus decoder, so
  there is no native library to build or ship. Packets are raw Opus, 20 ms, 24 kbps up at 16 kHz; the decoder
  gets a synthesized `OpusHead` as `csd-0`. If either codec is missing the hello asks for PCM16 instead, and an
  encoder failure at runtime reconnects with PCM16.
- **Audio route** (setting, default Auto):

  | Route | Mic | Playback | Barge-in | Good for |
  |---|---|---|---|---|
  | Media | `VOICE_RECOGNITION`, normal audio mode | `USAGE_ASSISTANT` (media path, A2DP, ducks music) | By tap only on speakers (half-duplex: mic ignored while Laura talks + 600 ms); by voice with a wired/USB headset | Car Bluetooth, headphones |
  | Call | `VOICE_COMMUNICATION` + `AcousticEchoCanceler`, `MODE_IN_COMMUNICATION` | `USAGE_VOICE_COMMUNICATION` on the loudspeaker | By voice | Phone on a desk |

  Auto uses Media for car mode, any Bluetooth audio output and headsets, and Call on the bare loudspeaker. Call
  mode is the field bug from the PWA: with echo cancellation Android switches to communication mode, replies go
  to the call path, and the car's A2DP plays nothing. It also disturbs other media while it is on. Routes are
  re-evaluated when audio devices come and go.
- **Background:** a `microphone` foreground service with a partial wake lock. Android 14+ only allows starting it
  from the foreground, so the assistant is turned on from the app; it then survives the screen turning off.
- **No wake word:** hands-free streams every voiced moment through the speech gate and the gateway's addressee
  gate decides whether you were talking to her. An invocation (side key, assist gesture) sends the protocol's
  `wake` message with the next utterance, so that turn counts as addressed.
- **Side button status (One UI):** the side key setting lives in Samsung's private settings, so Settings →
  Invocation infers it: green once she's the digital assistant and has been called through the assistant path
  (assistant session or `ACTION_ASSIST`, time kept in the `assist_invoked_at` pref), amber while she's the assistant
  but hasn't been yet, and the setup hint while she isn't (losing the role clears the time).
- **Who's answering:** the reply's `source` ("Local · Bonsai 9B" or "Claude Sonnet 5.5 · via Hermes") shows in the
  label under the avatar when she starts speaking, and on each reply in the developer conversation log.
- **Network:** OkHttp WebSocket, 15 s pings, reconnect with jittered backoff (0.5 s to 10 s), immediate retry when
  the default network comes back. 4000 shows "in use on another device" and waits for a tap; 4401/403 and
  certificate failures stop and point to settings.
- **Floating Laura** (`app/.../overlay/`): while Laura is on and none of the app's screens is visible, just her
  cubes (104 dp, no disc, halo or shadow) float over other apps (`TYPE_APPLICATION_OVERLAY`, owned by the microphone service, so
  it goes away with it). It uses the watch's native GLES renderer (`:avatar`, phone glow 0.67, `transparent` mode:
  translucent surface, premultiplied output with alpha from the cubes' brightness so she reads on light and dark
  apps, 30 fps cap), not a WebView, and renders only while the screen is on and unlocked.
  Tap stops the reply while she speaks and otherwise opens the app (a tap that silently muted her from another
  app would be easy to miss); hold is push-to-talk (only her body is a target); drag snaps to the nearer side, and dropping it on the × at
  the bottom turns her off (service stopped, mic released). New reply text shows as a caption next to it for
  5 s, and an `ignored` utterance gets a "For Laura" chip for its 60 s recall window. Needs "Display over other
  apps", asked for from Settings (Floating Laura) with an explanation; without it nothing changes and nothing
  asks again. Bubbles (`Notification.BubbleMetadata`) were the alternative but don't fit: the collapsed bubble is
  a static icon (no live avatar), long-press and drag are the system's (no hold-to-talk, its × only dismisses the
  bubble), it needs a conversation shortcut and MessagingStyle, and One UI ships bubbles off ("Floating
  notifications" defaults to Smart pop-up view). On Samsung, set Laura's battery use to Unrestricted, and the
  default left edge keeps the bubble clear of the right-hand Edge panel handle.

### Home screen widget (`app/.../widget/`)

- **One widget, two picker entries:** "Laura" (4x2) and "Talk to Laura" (2x1) are the same Glance widget with other
  default sizes; it resizes from 1x1 to 4x3+ and the layout follows the real size (`SizeMode.Exact`): 1x1 her face with
  a state dot, 2x1 face + state, 2x2 state + face + Talk, 4x2 adds who is answering with Mute and Open, 4x3 adds her
  last reply in a card with a source chip (Local / Claude) and its time. Talk (and a tap on her face) starts
  `InvokeActivity`, the same path as the side key, so it turns her on for one turn and the floating overlay shows her;
  Mute is an action callback on the running engine (only shown while she is on); the reply card and Open go to the app.
- **States are stills of the real model:** `res/drawable-nodpi/widget_face_*.webp` (idle, listening, thinking,
  speaking, sleeping for off and muted, offline, error), rendered with the avatar workbench at yaw 15°, pitch 4°,
  `crop(0.3, -0.25, 7.5)`, `bg=transparent` (the overlay theme with its outline), 512 px downscaled to 384. Face styles:
  Live (these), Tile (the Android Auto stills on their dark render background) and Icon (the launcher face, static).
- **Pushed, never polled:** `WidgetSync` (started from `App.onCreate`) reduces `VoiceEngine.ui` to what a widget shows
  (`WidgetModel`: state, second line, last reply; no mic level or stats), and publishes a change at most every 300 ms
  for a state change, 1.5 s while only the reply text grows, and 5 s with the screen off (the last state always
  lands). `updatePeriodMillis` is 0, and with no widget placed nothing is sent. The last reply is kept in prefs so it
  survives the process, and its text only while some widget shows it.
- **Configure** (`WidgetConfigActivity`, optional on placing, "Widget settings" later): theme Auto / Light / Dark, face
  style, face only, last reply on/off, hide reply text (keeps who answered). A widget on a lock screen host
  (`WIDGET_CATEGORY_KEYGUARD`) never shows reply text. The preview is the widget itself, composed by Glance at the
  widget's own size.
- **Design:** `docs/app/widget-design/` (stills rendered from `src/widgets.html` with `src/render.sh`, Raycast-style chrome
  in the icon's plum / wine / apricot, light and dark) and `emu-*.png` from the emulator. Debug builds take
  `adb shell am broadcast -a dev.hect.lauravoice.WIDGET_DEMO --es state speaking --es reply "..." --es source "..."`
  to show a state without a gateway (`--es state live` returns to the engine).

### Car (`app/.../car/`)

- **Car mode is detected, not guessed:** on while Android Auto is connected (`androidx.car.app` `CarConnection`), and
  optionally while the car's Bluetooth device picked in Settings → Car is connected (needs Nearby devices
  permission). No activity recognition. `core/CarState.active` is what the engine, avatar and gateway
  (`context.car`) see; Settings → Car → Always on / Off sets `CarState.manualOverride`, Automatic clears it. Off
  again when Android Auto disconnects.
- **Car call (default, Settings → Car → Car call):** in the car Laura is a call. `car/LauraCall` places a
  self-managed Telecom call named "Laura" through Core-Telecom (`CallsManager`, outgoing, set active by the app
  itself, so nothing rings) while car mode is on and she is on; car mode coming on turns her on. Telecom then owns
  the audio: MODE_IN_COMMUNICATION, the car's hands-free (Bluetooth) as the call device, and the call's audio focus,
  which refuses every other app's focus request while the call is up (so navigation prompts that ask for focus and
  music wait). Her mic is the VOICE_COMMUNICATION source and her replies USAGE_VOICE_COMMUNICATION (`Routing`:
  "car call"); `AndroidAudio` leaves the mode, device and focus alone during the call. Android Auto shows its own
  call screen with the name and her picture (call icon extension, `car_avatar.png` via a FileProvider). The car's
  mute button mutes her, hold (a real phone call) stops and mutes her until it's resumed, hang up (car, Android
  Auto, notification) turns her off; leaving the car ends the call and turns her off again if the call turned her
  on. VoiceService adds the phoneCall foreground type and shows a CallStyle notification during the call. Turning
  her on from the background needs Android's exemptions: battery optimisation off for Laura, and Laura as the
  default digital assistant (the microphone may only be started from the background by the VoiceInteractionService
  app); without them, open Laura on the phone once.
- **Car screen** (`car/LauraCarAppService`, calling category): a PaneTemplate with two fixed rows, "You" (what he
  said, live) and "Laura" (her reply, or her state), a state glyph (lavender mic listening, apricot wave speaking,
  dots thinking), her round portrait, and Call Laura / Hang up + Mute. Row titles never change, so updates are
  template refreshes (no step quota); at most one a second. Android Auto puts its call screen over it while the
  call is up. Design and stills: `docs/car/design/`.
- **Media fallback** (car call off): Laura as a media source, one static picture of her as artwork, what she's
  doing as the subtitle, her last reply as the description. Play = talk (turns her on if Android allows it from
  the car, otherwise asks you to open the app once; unmutes), pause = stop the reply, plus Mute and Turn off. The
  session only reports playing while she speaks, and steps aside during a car call. The browse root is a "Laura"
  tab with "Talk to Laura" inside (Android Auto only shows browsable items at the root, `core/CarBrowse`); it
  answers Android Auto, Assistant, system apps and media controllers whether or not car mode is on, and the
  session is only active in car mode, so it never takes headset play/pause outside the car.
- **Guidance and focus (media fallback):** replies take transient-may-duck focus (music ducks, podcasts pause, both get it back
  when she finishes). Navigation prompts make her quieter (-9 dB) instead of pausing her; an exclusive transient
  request (another assistant, the car's own prompt) -16 dB; a phone call stops the reply. Under Android Auto her
  replies use the media usage, which Android Auto projects to the car's media channel.
- **Debug builds in the car:** Android Auto hides apps not installed from Play until you allow it yourself, once:
  Settings → Connected devices → Android Auto → scroll to Version → tap "Version and permission info" 10 times →
  OK; then ⋮ → Developer settings → Unknown sources on. Android Auto can keep its earlier verdict until it restarts: App
  info for Android Auto → Force stop, then open Android Auto settings → Customize launcher → make sure Laura is
  ticked. Reconnect the car. `adb logcat | grep -E 'CAR.VALIDATOR|LauraMedia|LauraCall|Telecom'` shows whether Android Auto allowed
  the package and whether it got a browse root.

## Wear OS (`wear/`)

Assumed watch: Galaxy Watch7 (Wear OS 5, API 34), paired with a Galaxy S21 (Android 15). minSdk 30.

- The UI is only the avatar on black, full screen. All settings (mode, language, car, gateway) live on the
  phone, which mirrors them to the watch through a Data Layer item (`/laura/config`).
- Opening the app takes the conversation over from the phone. Hands-free just listens; in push-to-talk mode
  you hold anywhere on the screen. A tap stops the current reply.
- The watch only listens while the app is in the foreground: leaving it, screen off, ambient and wrist-down
  all pause the activity, which releases the microphone and closes the session. No foreground service or
  wake lock. The phone can take the conversation back with "Use phone" (chip and notification action),
  which releases the watch mic too (close code 4000).
- Via the phone (default): the watch opens a `ChannelClient` channel (`/laura/ws`) to the node advertising
  `laura_voice_relay` and speaks the normal protocol over it, framed as `[type u8][len u32][payload]`. The
  phone's `WearRelayService` holds the WebSocket and relays both ways. It ends the relay when the channel
  closes or the watch goes quiet for 10 s (the watch pings every 2 s), then the phone's own session returns.
- Replies play as USAGE_MEDIA on the watch (the music stream is the loud one, and the side buttons/bezel
  control it while the app is open), with extra gain from a LoudnessEnhancer, which also limits peaks. The
  phone's Settings has a "Watch volume" slider (synced over the Data Layer): 0.5 is unity, the default 0.75 is
  +6 dB, 1.0 is +12 dB.
- Avatar: a native GLES 3 render of the same parameter model as the phone (`android/avatar`, shared module):
  base pose, per-state targets with exponential damping, reaction layers with attack/release weights,
  face bitmaps, car mode, ported from `docs/avatar/prototype.html`. Coarser voxels (about 110 body cubes, 50
  face pixels, 4 orbiters, 2 hands), one instanced draw for the body (additive on black) and one for the face,
  no bloom, 30 fps, rendering only while the activity is resumed. `AvatarModel.glow` (0.67 on the phone page,
  0.4 on the watch, plus `haloScale`/`opacityScale`) is the brightness knob.

Install: on the watch, Settings → Developer options → ADB debugging and Wireless debugging → Pair new device;
then `adb pair <watch-ip>:<pair-port>`, `adb connect <watch-ip>:<port>`,
`adb -s <watch-ip>:<port> install -r wear-debug.apk`. The phone app must be installed too (same signing key).

## Linux speaker (`linux/`)

`laura_voice.py` is a single file, standard library only, Python 3.6+ (Ubuntu 18.04 / JetPack 4.x). Audio
goes through `arecord`/`aplay` (or `parec`/`pacat`) subprocesses, Opus through `libopus.so.0` via ctypes (PCM16
if it is missing), and the WebSocket is a small RFC 6455 client, so nothing needs compiling on the TX2.

```sh
sudo apt install alsa-utils libopus0
sudo useradd -r -G audio laura
sudo install -D -m 755 clients/linux/laura_voice.py /opt/laura-voice/laura_voice.py
sudo install -m 640 -g laura clients/linux/laura-voice.conf.example /etc/laura-voice.conf   # edit url/key/pin
sudo install -m 644 clients/linux/laura-voice.service /etc/systemd/system/
sudo systemctl enable --now laura-voice
journalctl -u laura-voice -f
```

`systemctl reload laura-voice` (SIGUSR1) takes the conversation back after another device took it over;
`systemctl kill -s USR2 laura-voice` toggles mute. Test without a microphone:
`laura_voice.py -c conf --wav question_16k_mono.wav --once --out reply.wav`.

### What the TX2 adds, and what it needs

The TX2 is a capable but power-hungry host for this job: everything heavy (VAD, ASR, speaker verification,
addressee detection, LLM, TTS) runs on the gateway, and the client only gates, encodes and plays, which a
Raspberry Pi Zero 2 could do. It's worth using because Hector has it, and it leaves room for on-device extras
later (a wake word or local VAD on the GPU). The real gaps are hardware:

- **Microphone:** a far-field array with on-board processing, such as the ReSpeaker USB Mic Array v2.0 (XMOS,
  1-channel firmware gives beamformed, noise-suppressed 16 kHz audio over USB). A laptop-style single mic across a
  room will hurt ASR badly.
- **Speaker and echo:** any powered speaker on the 3.5 mm jack or USB. Without echo cancellation the client must be
  half-duplex (the default): no voice barge-in while Laura talks. The ReSpeaker does AEC on its own output, so
  play through its jack and set `half_duplex = no` for voice barge-in.
- **Power:** the TX2 module idles around 5 to 7 W plus the dev carrier board; fine for mains, not for battery.
- **OS:** JetPack 4.x is Ubuntu 18.04 with Python 3.6, which the client targets; nothing else is needed.

## Verified vs not

| What | Status |
|---|---|
| Linux client, real turn against the live gateway (Opus both ways, cert pin, Python 3.6 in Docker) | Verified: transcript, reply text, 5 s of decoded reply audio, first audio 0.82 s after the turn |
| Linux client against `web/mock_server.py` (PCM) and an Opus stand-in | Verified |
| Linux client: bad key → exits 2, wrong pin → exits 3, no pin with self-signed cert → retries | Verified |
| Linux unit tests (gate, Opus round trip, URL) on Python 3.6 and 3.12 | Pass |
| Android `:core` unit tests (config/QR parsing, protocol incl. gate/task/approval messages, gate, routing, relay framing, Opus header, voice commands) | Pass |
| MediaCodec Opus encode → decode round trip on an Android 15 emulator (same `c2.android.opus` codecs as the S21) | Pass |
| Full `VoiceEngine` on the emulator (WAV as mic) against an Opus gateway stand-in over pinned TLS: speech gate, Opus up, reply decoded (1.5 s of 1.5 s) | Pass (`:core:connectedDebugAndroidTest`, `LiveGatewayTest` with `-e url/key/pin`) |
| Phone app on the Galaxy S21 (Android 15): connects with the pinned cert, gateway logs `audio up=opus down=opus` | Verified |
| Phone: real speech turns, screen-off listening, car Bluetooth routing, voice barge-in | Not yet confirmed on hardware |
| Watch on the Galaxy Watch7: relay session, avatar clips, mic released on screen off (`dumpsys audio`), phone resumes, "Use phone" takes it back | Verified |
| Watch CPU while open, listening and animating (debug build) | 65–85 % of one core (of 5); GL thread ~20–40 %, rest audio/Opus/relay |
| Watch battery with the app open (screen on, listening, avatar) | 2.27 mAh in 120 s, about 11 mAh per 10 min (about 3 % of a Watch7 battery); not a full 10-minute run, the watch dozes on its own |
| Linux client on the TX2 with a real mic/speaker | Not tested (no access); ALSA via `arecord`/`aplay` only |
