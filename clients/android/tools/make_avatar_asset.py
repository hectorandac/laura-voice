#!/usr/bin/env python3
"""Builds app/src/main/assets/avatar/ from docs/avatar/prototype.html: avatar only, local three.js r160,
theme-matched background, cheap glow first, and a small API for the app to drive it.

  python3 make_avatar_asset.py <three-0.160.0 npm package dir> [prototype.html]
"""
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SRC = ROOT / 'docs/avatar/prototype.html'
OUT = Path(__file__).resolve().parents[1] / 'app/src/main/assets/avatar'
ADDONS = ['postprocessing/EffectComposer.js', 'postprocessing/RenderPass.js', 'postprocessing/UnrealBloomPass.js',
          'postprocessing/OutputPass.js', 'postprocessing/Pass.js', 'postprocessing/ShaderPass.js',
          'postprocessing/MaskPass.js', 'shaders/CopyShader.js', 'shaders/LuminosityHighPassShader.js',
          'shaders/OutputShader.js']

CSS = """
html,body{background:var(--app-bg,#0F0D17)!important;height:100%;overflow:hidden}
.wrap{max-width:none;padding:0;height:100%}
.top{position:static;padding:0;background:transparent;height:100%}
.stage{position:fixed;inset:0;height:auto;min-height:0;border:0;border-radius:0;background:transparent}
canvas#c{touch-action:none;cursor:default}
.hud,.meters,#desc,h1,section{display:none!important}
"""

# Replacements: (old, new). Each old string must occur exactly once in the prototype.
EDITS = [
    ('https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.module.js', './three/three.module.js'),
    ('https://cdn.jsdelivr.net/npm/three@0.160.0/examples/jsm/', './three/addons/'),
    ("import * as THREE from 'three';",
     "import * as THREE from 'three';\n"
     "const APP_BG = '#' + (new URLSearchParams(location.search).get('bg') || '0F0D17');\n"
     "document.documentElement.dataset.theme = 'dark';\n"
     "document.documentElement.style.setProperty('--app-bg', APP_BG);\n"
     "const ext = { mic: null, tts: null, base: 'idle', paused: false, bloomTried: false, good: 0 };\n"
     "const bridge = window.AvatarBridge || { ready() {}, failed() {} };"),
    ("catch (e) { $('#desc').textContent = 'WebGL is not available in this browser.'; throw e; }",
     "catch (e) { bridge.failed(String(e && e.message || e)); throw e; }"),
    ("function micLevel(t) {",
     "function micLevel(t) {\n  if (ext.mic !== null) { mic.level += (ext.mic - mic.level) * (ext.mic > mic.level ? 0.5 : 0.12); return mic.level; }"),
    ("function ttsAmp(t, active) {",
     "function ttsAmp(t, active) {\n  if (ext.tts !== null) { const v = active ? ext.tts : 0; tts.val += (v - tts.val) * (v > tts.val ? 0.6 : 0.25); return tts.val; }"),
    ("if (state === 'interrupted' && t - stateStart > 1.2) setState('listening');",
     "if (state === 'interrupted' && t - stateStart > 1.2) setState(ext.base === 'interrupted' ? 'listening' : ext.base);"),
    ("if (state === 'ignored' && t - stateStart > 2.4) setState('idle');",
     "if (state === 'ignored' && t - stateStart > 2.4) setState(ext.base === 'ignored' ? 'idle' : ext.base);"),
    ("let glowMode = 'bloom';", "let glowMode = 'cheap';"),
    # Drag-to-rotate: the cubes live in a rig the app turns (yaw with fling, pitch clamped); the halo and the
    # floor shadow stay put. After 4 s without touch it eases back to facing front.
    ("const bodyL = makeLayer(bodyIdx.length, mat, 0), faceL = makeLayer(faceIdx.length, faceMat, 10);",
     "const bodyL = makeLayer(bodyIdx.length, mat, 0), faceL = makeLayer(faceIdx.length, faceMat, 10);\n"
     "const rig = new THREE.Group(); scene.add(rig); rig.add(bodyL.mesh); rig.add(faceL.mesh);\n"
     "const rot = { yaw: 0, pitch: 0, vy: 0, touch: -1e9, held: false };\n"
     "const PITCH_MAX = 35 * Math.PI / 180;\n"
     "function stepRotation(dt) {\n"
     "  if (!rot.held) { rot.yaw += rot.vy * dt; rot.vy *= Math.exp(-2.2 * dt); if (Math.abs(rot.vy) < 0.02) rot.vy = 0; }\n"
     "  if (!rot.held && clock - rot.touch > 4 && rot.vy === 0) {\n"
     "    const front = Math.round(rot.yaw / TAU) * TAU; const k = 1 - Math.exp(-1.2 * dt);\n"
     "    rot.yaw += (front - rot.yaw) * k; rot.pitch += (0 - rot.pitch) * k;\n"
     "  }\n"
     "  rig.rotation.set(rot.pitch, rot.yaw, 0, 'YXZ');\n"
     "  faceL.mesh.visible = Math.cos(rot.yaw) * Math.cos(rot.pitch) > -0.05;\n"
     "}"),
    ("function liveStep(dt) {\n  const t = clock;", "function liveStep(dt) {\n  const t = clock;\n  stepRotation(dt);"),
    # One knob for overall brightness: scales the emissive and edge terms and the halo (not the base colours).
    ("const L = 4;", "const L = 4;\nconst GLOW = 0.67;"),
    ("vec3 col = base * diff * (0.5 + 0.6 * vFx.x) + base * edge * uEdge * (0.55 + vFx.x) + base * fres * 0.15;",
     "float G = ${GLOW.toFixed(3)};\n"
     "      vec3 col = base * diff * (0.5 + 0.6 * vFx.x * G) + base * edge * uEdge * G * (0.55 + vFx.x) + base * fres * 0.15;"),
    ("halo.material.uniforms.uAlpha.value = TH.haloA * p.halo",
     "halo.material.uniforms.uAlpha.value = GLOW * TH.haloA * p.halo"),
    ("renderer.setClearColor(new THREE.Color(clearHex || cssVar('--stage') || '#000000'), 1);",
     "renderer.setClearColor(new THREE.Color(clearHex || APP_BG), 1);"),
    ("  requestAnimationFrame(loop);\n  if (exporting) return;",
     "  if (ext.paused) return;\n  requestAnimationFrame(loop);\n  if (exporting) return;"),
    ("    if (bloom.enabled && fps < 45) slowSecs += fpsT; else slowSecs = 0;",
     # Bloom stays off: in Android WebView the composer output washes the whole canvas grey.
     "    if (bloom.enabled && fps < 45) slowSecs += fpsT; else slowSecs = 0;"),
    ("window.__avatar = { setState,",
     "window.__avatar = {\n"
     "  setBase(s) { ext.base = s; if (!((state === 'interrupted' || state === 'ignored') && (s === 'idle' || s === 'listening'))) setState(s); },\n"
     "  feed(m, a) { ext.mic = m; ext.tts = a; },\n"
     "  setCar(b) { if (car !== !!b) { car = !!b; setState(state); } },\n"
     "  look(x, y) { gaze.tx = x; gaze.ty = y; gaze.last = clock; },\n"
     "  dragStart() { rot.held = true; rot.vy = 0; rot.touch = clock; },\n"
     "  drag(dx, dy) { rot.yaw += dx * Math.PI; rot.pitch = clamp(rot.pitch + dy * Math.PI * 0.5, -PITCH_MAX, PITCH_MAX); rot.touch = clock; },\n"
     "  dragEnd(vx) { rot.held = false; rot.vy = clamp(vx * Math.PI, -12, 12); rot.touch = clock; },\n"
     "  poke() { poke(); },\n"
     "  pause(p) { const was = ext.paused; ext.paused = !!p; if (was && !p) { last = performance.now(); requestAnimationFrame(loop); } },\n"
     "  setState,"),
]


def main():
    if len(sys.argv) not in (2, 3):
        raise SystemExit(__doc__)
    pkg = Path(sys.argv[1])
    html = Path(sys.argv[2] if len(sys.argv) == 3 else SRC).read_text()
    for old, new in EDITS:
        if html.count(old) != 1:
            raise SystemExit('prototype changed, cannot apply: ' + old[:70])
        html = html.replace(old, new)
    html = html.replace('</style>', CSS + '</style>', 1)
    # Ready only once the canvas has a real on-screen size: a 0-height viewport renders but paints nothing.
    html = html.replace('</script>\n</body>',
                        "(function check(n) { const r = canvas.getBoundingClientRect();\n"
                        "  if (r.width > 0 && r.height > 0 && innerHeight > 0) bridge.ready();\n"
                        "  else if (n > 0) setTimeout(() => check(n - 1), 100); })(25);\n"
                        "</script>\n</body>", 1)
    if OUT.exists():
        shutil.rmtree(OUT)
    (OUT / 'three/addons/postprocessing').mkdir(parents=True)
    (OUT / 'three/addons/shaders').mkdir(parents=True)
    (OUT / 'index.html').write_text(html)
    shutil.copy(pkg / 'build/three.module.min.js', OUT / 'three/three.module.js')
    for a in ADDONS:
        shutil.copy(pkg / 'examples/jsm' / a, OUT / 'three/addons' / a)
    shutil.copy(pkg / 'LICENSE', OUT / 'three/LICENSE')
    print('wrote', OUT)


if __name__ == '__main__':
    main()
