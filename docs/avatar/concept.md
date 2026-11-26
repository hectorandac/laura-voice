# Laura avatar: voxel blob

Replaces the plain orb. Prototype: `prototype.html` (open it on the phone; mic needs https or localhost).
Screenshots: `shots/`.

## Design language
- **Body**: a gumdrop-shaped blob (slightly wider at the bottom) made of about 240 hollow-shell cubes on a voxel
  grid, with a small dim core of about 20 cubes inside. The cubes are semi-transparent with a bright edge (the
  shader brightens each face toward its border) and a gradient from aqua at the top to lavender at the bottom. On
  dark backgrounds the cubes blend additively and get a tight bloom; on light backgrounds they use normal alpha
  blending, sorted back to front, so the colours stay saturated. A soft halo behind the body is the cheap glow.
  It costs almost nothing and is the only glow on low-end devices.
- **Face**: pixel-art eyes, each a 4×4 grid of small cubes. Each eye has a white catchlight, plus an optional
  4×3 mouth and pink blush cubes. The face sits on its own layer that is always drawn on top, with dark ink
  eyes. That keeps it readable over the glowing body in both themes. Expressions are bitmaps (open, happy ^,
  sleepy ‿, flat –, narrow, sad, x, star, calm). Each face pixel grows or shrinks in about 50 ms, so expression
  changes look like a pixel dissolve rather than a cut.
- **Satellites**: 8 small cubes orbit the body. They carry most of the "mind" signal: they gather into a crown
  while thinking, face you while listening, scatter on surprise, and fall when offline. Two floating "hand"
  cubes handle gestures (ear, chin, shrug, wave).
- **Personality**: curious and warm, a little shy. It is always slightly alive: breathing (squash and stretch),
  a slow bob, random blinks and double-blinks, an occasional curious head tilt, and a gaze that follows your
  finger or wanders. Tapping it pokes it (giggle or shy). The design is original geometry with no borrowed
  character, mascot or brand.

**Palette** (dark / light): body top `#8FF3FF` / `#1FB2D6`, body bottom `#A68CFF` / `#6F55EE`, eyes
`#0B0F2E` / `#141638`, blush `#FF7FB0` / `#FF4F8B`. State tints (dark/light): listening mint `#5CF2D6`/`#10B89A`,
thinking violet `#B794FF`/`#7C4DFF`, error `#FF5F5F`/`#E5484D`, warm `#FFC46B`/`#F2A23A`, car teal
`#3FC8B4`/`#1E8C80`, offline grey `#7A8194`/`#8A90A2`.

## States (persistent, from the client state machine)
| State | Animation |
|---|---|
| idle | Breathes and bobs, blinks, follows gaze, tilts its head out of curiosity now and then. |
| sleeping | Sleepy ‿ eyes, slow deep breaths, sinks and tilts, dims. One cube floats up like a "z". |
| listening | Leans toward you with wide eyes and a mint tint. The orbit ring turns to face you. Front cubes ripple and the ring expands with **mic level**. A hand goes up to its ear. |
| thinking | Eyes look up and right. The orbiters spin into a crown, a glow band climbs the body, the core swirls, and a hand goes to its chin. |
| speaking | Mouth pixels open in 3 steps and the surface pulses radially with **TTS amplitude**. Orbiters push out and hands gesture. |
| interrupted | Transient, about 1.2 s: flinches back (squash), amber flash, "o" mouth, cubes scatter, then goes to listening. |
| ignored | Transient, about 2.4 s ("not talking to me"): shrug reaction with flat eyes and a glance away, then back to idle. |
| not owner | Polite but reserved: leans back, shrinks a little, desaturates, narrow eyes, one small head shake. Orbiters stay close and gaze-follow is weak. |
| error | Red tint, x eyes, cubes jitter and drop out in glitch steps. |
| offline | Dims and greys out, slumps, closed eyes. Orbiters fall and lie below it. |
| car (modifier) | Applies to any state: no orbiters, hands or gaze, minimal motion, calm small eyes, teal tint, reactions at 40 %. Speech shows only as core glow. |

**Emotion hint** (persistent mood, optional): `happy` (^ eyes, blush, bouncier), `confused` (tilt, one eye
squinted), `apologetic` (droops, sad eyes, hands together). Applies only in idle, listening, thinking and speaking.

## Reactions (one-shot, chosen by the LLM)
Each reaction is a procedural layer: `f(t)` returns additive offsets (pose, hand targets, orbit, tint, glow) plus
an optional eye/mouth bitmap. The runtime multiplies it by a smooth weight (0.22 s attack, 0.38 s release) and
adds it on top of whatever the state animation is doing. Because of that, reactions work over speaking and
listening. A new reaction fades the previous one out over 0.3 s while its motion keeps running, so rapid or
mid-reply triggers always blend with no pose jumps. Bitmap overrides take effect only in a reaction's middle
section. Up to 4 reactions can overlap. Suggested system-prompt list:

- `nod`: two soft nods; yes, got it, agreed.
- `shake`: head shake; no, that's not right.
- `happy`: little hops, ^ ^ eyes, blush; pleased, warm.
- `laugh`: rapid giggle bounce and wobble; something was funny.
- `confused`: head tilt, one eye squinted, eyes dart; unsure what was meant.
- `thinking_hard`: narrowed eyes up, spinning crown, hand to chin; working on something tricky.
- `surprised`: quick stretch, wide eyes, "o" mouth; oh!
- `sad`: sinks, sad eyes, hands together; sorry, apologetic, bad news.
- `shy`: turns aside, looks down, blushes; flattered or embarrassed.
- `excited`: fast bouncing, sparkly eyes, orbiters whirl; great news!
- `sleepy`: slow yawn, droopy eyes, dims; tired, it's late.
- `wave`: raises a hand and waves; hello or goodbye.
- `celebrate`: jumps with both hands up, cubes burst out; success, done!
- `shrug`: hands up, tilt, glance away; no idea, whatever.
- `focus`: leans in, eyes narrow, orbiters tighten; paying close attention.

**Protocol (additive, v1-compatible).** The LLM writes an optional tag at the start of its reply, e.g.
`<r:nod>`. The server strips it before TTS and sends:
`{"type":"avatar","turn_id","reaction":"nod"}` (server → client, ideally when `audio_start` goes out so the
reaction lands with the voice) and optionally `{"type":"avatar","emotion":"happy"|"confused"|"apologetic"|"none"}`.
Alternatively the server can add an optional `reaction` field to the first `assistant_delta`. Clients ignore
unknown names. The planned speaker/addressee work should emit `{"type":"ignored","reason":"not_addressed"|"not_owner"}`,
which maps to the ignored and not-owner states.

## Native clients and budgets
- **Android phone (Compose)**: Filament gives proper PBR, bloom and instancing but is heavy for a single blob.
  The recommended path is a small **GLES 3.0 `GLSurfaceView`/`TextureView` wrapped in `AndroidView`**: one
  instanced draw for the body, one for the face, two halo quads, and an optional half-resolution bloom (3 mips).
  The animation code is about 600 lines of pure math, so it ports directly to Kotlin. Fallback: the exported
  sprite sheets (below). Budget: ≤ 350 cubes, ≤ 6 draw calls without bloom (≤ 20 with), DPR ≤ 2, 60 fps while a
  session is active and 30 fps in idle. Stop rendering when the screen is off or the app is backgrounded.
  Target < 3 % extra battery per 10 minutes of conversation. Bloom turns itself off below 45 fps (the
  prototype does this too).
- **Jetson TX2 speaker**: with a small screen it can run the same GLES path in full (or desktop GL). With only
  an LED ring it uses a 1-D projection: hue = state tint, brightness = mic or TTS level, a rotating highlight for
  thinking, a red stutter for error. Reactions become short ring patterns.
- **Galaxy Watch7**: see below.

## Watch assessment (Wear OS, 450 px round)
A **live version is technically feasible but not recommended as the default.** The Watch7's Exynos W1000 has a
Mali-G68 MP2 with GLES 3.2. It could draw about 80 opaque-ish cubes with a faked halo at 30 fps, but:
continuous GL keeps the GPU and SoC out of their low-power states (on the order of 5–10 % battery per 10 minutes
of on-screen animation); sustained use warms the wrist; alpha sorting and bloom are too costly; and a
GLSurfaceView has to be handled carefully around ambient mode and tilt-to-wake. Assistant sessions are short, so a
reduced live mode (≤ 80 cubes, no transparency sorting, no bloom, 30 fps, only while the session is foreground)
is a reasonable later experiment. **v1 should use pre-rendered clips.**

**Clip spec.** All clips are rendered by the prototype's *Export watch clips* panel: deterministic, on true
black, no bloom (OLED), no gaze, blink or randomness.
- **Canonical pose** = idle-loop frame 0. Every base oscillator is a cosine harmonic of a 4 s loop, so velocity is
  zero there. Reaction clips run canonical idle → reaction → canonical idle with the idle base frozen at phase 0.
  Their first and last frames are pixel-identical to idle frame 0 (the exporter verifies this; all 15 clips
  measured 0/255 difference). Chaining: play the idle loop and, at its wrap (or at frame 0 of any clip), switch
  to any reaction. Drop the reaction's last frame and continue with idle frame 0. There is no seam in either
  direction.
- **Format**: one sprite-sheet atlas per clip (8 columns), plus `manifest.json` (name, size, fps, frames, cols,
  rows, duration). Convert the PNG export to **lossy WebP q80** for the APK. Lottie and AVD are poor fits
  because the look is rasterized 3D.
- **Resolution / rate**: 256×256 frames at 15 fps, drawn centred and scaled to about 300 px on the 450 px
  face. 192 px is acceptable to save space. Idle loop: 60 frames. Reactions: 19–37 frames (1.2–2.4 s). State
  loops (listening, thinking, speaking at 3 amplitude levels, error) can be exported the same way later.
- **Size (measured)**: all 16 clips are about 3.5 MB as WebP q80 at 256 px, or about 2.2 MB at 192 px (about
  7 KB per frame). Decode one clip at a time (256 KB per frame as ARGB, so keep only idle plus the current
  clip). Alternatively, split into `Bitmap` regions and draw with `Canvas.drawBitmap` from a Compose `Canvas`,
  which needs no GL.
- **Ambient mode**: show a single static frame (canonical pose, eyes `calm`, desaturated outline, no fill)
  refreshed once a minute, with no animation.

## What the clients must feed the avatar
```
state:     idle | sleeping | listening | thinking | speaking | interrupted | ignored | not_owner | error | offline
car:       bool                      (from context.car)
micLevel:  0..1, ~30–60 Hz           (RMS dBFS mapped −58..−13 dB → 0..1, fast attack / slow release)
ttsAmp:    0..1, ~30–60 Hz           (RMS envelope of the playback buffer actually being played, not decoded ahead)
emotion:   none | happy | confused | apologetic          (persistent until changed)
reaction:  one-shot name from the list above              (fire-and-forget; avatar handles blending)
gaze:      optional −1..1 x/y (touch position; or face direction on the speaker)
```
`interrupted` and `ignored` are transient: the avatar returns to listening or idle by itself. `interrupted` comes
from the client's own barge-in. `ignored` and `not_owner` will come from the planned server events.
