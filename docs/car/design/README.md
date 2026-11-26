# Laura in Android Auto: agent screen

Style: the Linear dark system from awesome-design-md (near-black canvas, surface ladder, hairlines, Inter with
tight tracking), with Laura's own colours as the only accents: lavender (her hair) = listening, apricot (her icon
plate) = speaking, grey = thinking/idle. No motion anywhere on the car screen.

What the driver needs at a glance: who is on (Laura, her face), what state she is in (one word or one glyph),
and at most one line of what he said and two of what she is saying. Nothing else.

Stills (`src/render.mjs`, playwright v1.65.1, 1280x720 and 800x480):

1. `a-call-*.png` — **recommended, primary.** Android Auto's own call UI (approximated: Android Auto draws it, we
   supply the name "Laura" and her picture `call-icon-512.png` = `res/drawable-nodpi/car_avatar.png`). Calm, zero
   text to read, standard call controls (mute, hang up), sits next to the map.
2. `b-pane-*.png` — **recommended, secondary.** Laura's own car screen (Car App Library PaneTemplate, calling
   category): "You" / "Laura" rows with live captions, state glyph, her portrait (`portrait-round-480.png` =
   `car_portrait.png`), Call Laura / Hang up + Mute. Android Auto draws the chrome, fonts and buttons; we only
   choose content, glyphs and tints. Shown when no call is up (and when he opens Laura from the launcher).
3. `c-phone-1080x2400.png` — the same agent screen at full fidelity on the phone. Not for the driver; not built
   (the phone keeps its 3D avatar screen).
4. `d-media-*.png` — fallback when Car call is off: the media template with one static picture.
