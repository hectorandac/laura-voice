#!/usr/bin/env python3
"""Shared cases for the golden instance dumps, the WebView reference stills and the native snapshots.
Each case is a fresh start (wake off, no blink, no idle wander) stepped at exactly 1/60 s for `frames` frames.
  cases.py <golden|refs|native> <dark|watch|clear|all>"""
import json
import sys

DARK = [
    dict(name='idle', state='idle', frames=120),
    dict(name='listening', state='listening', mic=0.55, frames=90),
    dict(name='thinking', state='thinking', frames=150),
    dict(name='speaking', state='speaking', tts=0.7, frames=90),
    dict(name='speaking_low', state='speaking', tts=0.3, frames=80),
    dict(name='sleeping', state='sleeping', frames=150),
    dict(name='offline', state='offline', frames=120),
    dict(name='error', state='error', frames=120),
    dict(name='notowner', state='notowner', frames=120),
    dict(name='ignored', state='ignored', frames=48),
    dict(name='happy', state='idle', emotion='happy', frames=120),
    dict(name='celebrate', state='idle', reaction='celebrate', reactAt=1, frames=60),
    dict(name='shy', state='idle', reaction='shy', reactAt=1, frames=60),
    dict(name='surprised', state='idle', reaction='surprised', reactAt=1, frames=24),
]
for c in DARK:
    c.update(theme='dark', px=512)
WATCH = [dict(c, name='watch_' + c['name'], theme='dark', clearHex='#000000', px=480, watch=1)
         for c in DARK if c['name'] in ('idle', 'listening', 'thinking', 'speaking', 'sleeping', 'celebrate')]
WATCH.append(dict(name='watch_idle_48', state='idle', frames=120, theme='dark', clearHex='#000000', px=48, small=1, watch=1))
CLEAR = [dict(c, name='bubble_' + c['name'], theme='clear', px=208, small=1, pxRatio=2)
         for c in DARK if c['name'] in ('idle', 'listening', 'speaking', 'thinking', 'celebrate', 'sleeping')]

# native only: the watch LOD at other thresholds (compared against the same WebView still)
LOD = [dict(c, name=c['name'] + '@' + str(t), lod=t) for t in (0, 100, 200) for c in WATCH if c['name'] in ('watch_idle', 'watch_speaking')]
# native only: the bubble at the overlay's current ZOOM 1.25 (1 = the WebView's framing)
LOD += [dict(c, name=c['name'] + '@125', zoom=1.25) for c in CLEAR]

if __name__ == '__main__':
    kind, which = sys.argv[1], sys.argv[2]
    sets = dict(dark=DARK, watch=WATCH, clear=CLEAR, all=DARK + WATCH + CLEAR + (LOD if kind == 'native' else []))[which]
    if kind == 'golden':  # the JVM parity test needs no image sizes beyond the camera; skip the 48 px one
        sets = [c for c in sets if c['px'] != 48 and not c.get('watch')]
    print(json.dumps(sets))
