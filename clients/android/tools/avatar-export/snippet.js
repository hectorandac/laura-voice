// Appended to the avatar module by export.mjs (never shipped). It reads the module's own tables, so the native
// renderer is fed from the same code that draws the WebView. Modes (?xmode=): model, golden, refs, lod.
{
const XQ = new URLSearchParams(location.search), XMODE = XQ.get('xmode') || 'model';
const r5 = v => Math.round(v * 1e5) / 1e5;
const arr = a => Array.from(a, r5);
const lin = hex => new THREE.Color(hex).toArray().map(r5);
const post = (name, body) => fetch('/out/' + name, { method: 'POST', body: typeof body === 'string' ? body : JSON.stringify(body) });
ext.paused = true; exporting = true;
const ID = () => { for (let i = 0; i < N; i++) B.x[i * 4 + 1] = i; }; // item index in the (unused) mask slot
const theme = (nm, hex) => { applyTheme(nm, hex); ID(); };

// a fresh start: the same state as a page load with ?wake=0, no blink, no idle wander, no curious tilt, no glint
function reset() {
  clock = 0; state = 'idle'; stateStart = 0; emotion = 'none'; car = false; ext.base = 'idle'; ext.mic = 0; ext.tts = 0;
  Object.assign(P, BASE); Object.assign(T, BASE); lightCur.set(LIGHTS.idle[0]); lightTarget.set(LIGHTS.idle[0]);
  hLcur.copy(HAND_L); hRcur.copy(HAND_R);
  liveReactions.length = 0; blink.off = true; blink.next = 2; blink.onset = -10; curious.next = 1e9; glint.next = 1e9; glint.t0 = -10;
  gaze.x = gaze.y = gaze.tx = gaze.ty = 0; gaze.last = 0; gaze.wanderAt = 1e9; gaze.wx = 0; gaze.wy = 0;
  spk.lvl = 0; spk.at = -9; spk.loud = -9; spk.env = 0; tts.val = 0; mic.level = 0; ampSlow = 0; crownAng = 0; pokeAt = -10; boostUntil = 0; intrAt = -1;
  rib.init = false; tassel.init = false; TAIL_SW.init = false; rot.yaw = rot.pitch = rot.vy = 0; rot.held = false; rot.touch = -1e9;
  for (const it of items) { it.vv = 0; it.vis = (it.k === K.EYE || it.k === K.CATCH || it.k === K.MOUTH || it.k === K.HAND || it.k === K.SAT || it.k === K.WRAP || it.spark) ? 0 : 1; }
}
// a case: { state, emotion, mic, tts, frames, reaction, reactAt (frame), car }
function runCase(c) {
  reset();
  if (c.car) car = true;
  if (c.emotion) emotion = c.emotion;
  setState(c.state || 'idle'); ext.base = c.state || 'idle';
  ext.mic = c.mic || 0; ext.tts = c.tts || 0;
  const dt = 1 / 60;
  for (let f = 1; f <= c.frames; f++) {
    if (c.reaction && f === c.reactAt) triggerReaction(liveReactions, c.reaction, clock);
    clock += dt; gaze.last = clock; liveStep(dt);
  }
}
function setSize(px, isSmall, pxRatio) {
  renderer.setPixelRatio(1); renderer.setSize(px, px, false); frame(px, px); small = isSmall;
  outlineMat.uniforms.uRes.value.set(px, px); outlineMat.uniforms.uPx.value = 1.5 * pxRatio;
}
function dumpFrame() {
  const n = vox.count, S = 37, out = new Array(n * S);
  for (let k = 0; k < n; k++) {
    const o = k * S;
    for (let j = 0; j < 16; j++) out[o + j] = IM[k * 16 + j];
    for (let j = 0; j < 3; j++) { out[o + 16 + j] = A.lit.array[k * 3 + j]; out[o + 19 + j] = A.shd.array[k * 3 + j]; out[o + 34 + j] = A.n.array[k * 3 + j]; }
    for (let j = 0; j < 4; j++) { out[o + 22 + j] = A.fx.array[k * 4 + j]; out[o + 26 + j] = A.x.array[k * 4 + j]; out[o + 30 + j] = A.y.array[k * 4 + j]; }
  }
  const u = {};
  for (const k of ['uLightI', 'uHairK', 'uHairOn', 'uPartK', 'uWake', 'uDesat', 'uAccFloor', 'uSkinK', 'uScan', 'uScanY', 'uGlint', 'uRimStr', 'uRimPow', 'uRimAdd', 'uKey', 'uHairRimK', 'uTrans', 'uTrCap'])
    u[k] = r5(uniforms[k].value);
  for (const k of ['uLight', 'uRimCol']) u[k] = uniforms[k].value.toArray().map(r5);
  u.uHairUp = uniforms.uHairUp.value.toArray().map(r5); u.uBodyInv = arr(uniforms.uBodyInv.value.elements);
  u.uHead = arr(uniforms.uHead.value.elements); u.uCamH = uniforms.uCamH.value.toArray().map(r5);
  const extra = { inner: [...inner.position.toArray(), ...inner.quaternion.toArray(), ...inner.scale.toArray()].map(r5),
    halo: [r5(halo.material.uniforms.uAlpha.value), r5(halo.position.y), r5(halo.scale.x)], haloColor: halo.material.uniforms.uColor.value.toArray().map(r5),
    floor: [r5(floor.material.uniforms.uAlpha.value), r5(floor.scale.x)], floorColor: floor.material.uniforms.uColor.value.toArray().map(r5),
    view: arr(camera.matrixWorldInverse.elements), proj: arr(camera.projectionMatrix.elements), fps: autoFps(true) };
  return { n, inst: arr(out), u, extra };
}

if (XMODE === 'model') {
  const th = {};
  for (const nm of ['dark', 'light', 'clear']) {
    theme(nm);
    const T0 = THEMES[nm], cols = {};
    for (const k of Object.keys(T0)) if (typeof T0[k] === 'string' && T0[k][0] === '#') cols[k] = lin(T0[k]);
    const num = {}; for (const k of Object.keys(T0)) if (typeof T0[k] !== 'string') num[k] = typeof T0[k] === 'boolean' ? (T0[k] ? 1 : 0) : T0[k];
    faceCols();
    th[nm] = { name: nm, col: cols, num, mouthFlat: FC.mouthFlat.toArray().map(r5), catchLit: FC.catchLit.toArray().map(r5),
      uSkinFD: uniforms.uSkinFD.value.toArray().map(r5), uCheek: uniforms.uCheek.value.toArray().map(r5), uSkinRim: uniforms.uSkinRim.value.toArray().map(r5),
      uSkinFill: uniforms.uSkinFill.value.toArray().map(r5), uRampZ: r5(uniforms.uRamp.value.z),
      lit: arr(B.lit), shd: arr(B.shd), fx: arr(B.fx), x: arr(B.x), y: arr(B.y) };
  }
  // items in pool order; the body (shell) items carry their rest transform in body space (before bodyXf)
  const id4 = [0, 0, 0, 1];
  const its = items.map((it, i) => {
    const o = { k: it.k };
    if (it.k === K.SHELL) {
      let z = it.hz, sx2 = 1, sy2 = 1, sz2 = 1, q = id4;
      if (it.sxy) { sx2 = it.jsx || it.sxy; sy2 = it.jsy || it.sxy; z += it.hzOff; if (it.sz) sz2 = it.sz; }
      if (it.tq) { q = it.tq.toArray(); sx2 = it.hsx; sy2 = it.hsy; if (it.hsz) sz2 = it.hsz; }
      Object.assign(o, { p: [it.hx, it.hy, z].map(r5), q: q.map(r5), s: [sx2, sy2, sz2].map(r5), size: it.size,
        fringe: it.fringe ? 1 : 0, drop: it.drop || 0, gone: it.gone ? 1 : 0, m: it.m });
    } else if (it.k === K.EYE) Object.assign(o, { side: it.side, r: it.r, c: it.c, lash: it.lash ? 1 : 0, size: it.size, flat: it.flat });
    else if (it.k === K.CATCH) Object.assign(o, { side: it.side, q: it.q, size: it.size, flat: it.flat });
    else if (it.k === K.MOUTH) Object.assign(o, { r: it.r, c: it.c, size: it.size });
    else if (it.k === K.BLUSH) { const y = it.hy, bx = it.bcx;
      Object.assign(o, { side: it.side, j: it.j, spark: it.spark ? 1 : 0, x: r5(it.hx), y: r5(y), size: it.size, sx: it.sx, sy: r5(it.sy), flat: it.flat, bk: it.bk,
        fz: r5(Math.max(faceZ(bx - 0.5, y), faceZ(bx, y), faceZ(bx + 0.5, y))) }); }
    else if (it.k === K.SAT) Object.assign(o, { idx: it.idx, size: it.size });
    else if (it.k === K.RIB) Object.assign(o, { idx: it.idx, q: it.q, cn: it.cn, size: it.size });
    else if (it.k === K.TIE) Object.assign(o, { q: it.q, size: it.size });
    else if (it.k === K.CHARM) Object.assign(o, { q: it.q, o: it.o || null, core: it.core ? 1 : 0, tassel: it.tassel === undefined ? -1 : it.tassel, size: r5(it.size) });
    else if (it.k === K.WRAP) Object.assign(o, { side: it.side, r: it.r, c: it.c, p: [it.hx, it.hy, it.hz].map(r5), size: it.size, thin: it.thin });
    else if (it.k === K.HAND) Object.assign(o, { side: it.side, q: it.q, o: it.o, size: it.size });
    return o;
  });
  const fz = {}; for (let x = -7; x <= 7; x++) for (let j = -9; j <= 9; j++) { const k = x + ',' + j; const v = skinZ.get(k) ?? frontZ.get(k); if (v !== undefined) fz[k] = r5(v); }
  // reactions: f(u) sampled at 60 Hz (they depend on u only), numeric keys plus the face/tint picks
  const reactions = {};
  for (const nm of REACTION_NAMES) {
    const d = REACTIONS[nm].d, n = Math.ceil(d * 60) + 1, keys = {}, eyes = [], mouth = [], tint = [], tintAmt = [];
    for (let s = 0; s < n; s++) {
      for (const k in ro) delete ro[k];
      REACTIONS[nm].f(s / 60, d, ro);
      for (const k of NUM_KEYS) if (ro[k] !== undefined) { (keys[k] = keys[k] || new Array(n).fill(0))[s] = r5(ro[k]); }
      eyes.push(ro.eyes === undefined ? null : ro.eyes); mouth.push(ro.mouth === undefined ? null : ro.mouth);
      tint.push(ro.tint && TINTS[ro.tint] ? ro.tint : null); tintAmt.push(ro.tintAmt === undefined ? 0.4 : r5(ro.tintAmt));
    }
    reactions[nm] = { d, rate: 60, keys, eyes, mouth, tint, tintAmt };
  }
  const tints = {}; for (const k in TINTS) tints[k] = lin(TINTS[k]);
  const lights = {}; for (const k in LIGHTS) lights[k] = [...lin(LIGHTS[k][0]), LIGHTS[k][1]];
  const model = {
    version: 1, source: 'clients/android/app/src/main/assets/avatar/index.html',
    VS, PIV, EYE_CX: r5(EYE_CX), EYE_CY: r5(EYE_CY), MOUTH_Y, FS, MS, L, GLOW, FB, fov: camera.fov,
    TAIL: { root: TAIL.root, n: NR, seg: TAIL.seg, alt: TAIL.alt, ov: TAIL.ov, keep: TAIL.keep, rate: TAIL.rate, chainSeg: rib.seg }, TAIL_W: arr(TAIL_W),
    REST_S: REST_S.map(v => v.toArray().map(r5)), RIB_POSES, RIB_KEYS, TAIL_SW: { w: TAIL_SW.w, z: TAIL_SW.z, max: TAIL_SW.max },
    CHARM_AT: CHARM_AT.toArray(), CROWN_SZ, SLEEP_SZ, gemBase: gemBase.toArray().map(r5), ROT45: ROT45.toArray().map(r5),
    spark: [0, r5((bangY(0) - 0.5 - (TIPS['0'] || 0)) * VS), r5(faceZ(0, -0.1) + 0.02)],
    mouthZ0: r5(faceZ(0, MOUTH_Y - 0.3)),
    INNER_R, INNER_Y, HBAND: { flat: HBAND.flat, flatZ: HBAND.flatZ },
    uni: { uRamp: uniforms.uRamp.value.toArray().map(r5), uMouthL: r5(uniforms.uMouthL.value), uApple: uniforms.uApple.value.toArray().map(r5),
      uBand: uniforms.uBand.value.toArray().map(r5), uBandAx: HBAND.ax.toArray().map(r5), uBandEx: HBAND.ex.toArray().map(r5), uBandEz: HBAND.ez.toArray().map(r5),
      uSheen: uniforms.uSheen.value.toArray().map(r5), uHairRim: uniforms.uHairRim.value.toArray().map(r5), uUnder: uniforms.uUnder.value.toArray().map(r5),
      uGlintC: uniforms.uGlintC.value.toArray().map(r5), innerFill: innerMat.uniforms.uFill.value.toArray().map(r5), innerBase: innerMat.uniforms.uBase.value.toArray().map(r5),
      outline: outlineMat.uniforms.uColor.value.toArray().map(r5), floorLight: lin('#120A1E') },
    themes: th, lights, tints,
    EYES, EYE_DY, IRIS, CATCHES, LASH, MOUTHS, SPEAK, LIT_MOUTH, OPEN_LIKE,
    BASE, FAST, HAIRK, NUM_KEYS, RM_DROP, reactions,
    faceZ: fz, items: its, n: arr(B.n), NSHELL, NBODY
  };
  post('laura-model.json', model).then(() => post('done', 'ok'));
}

if (XMODE === 'golden' || XMODE === 'refs' || XMODE === 'lod') {
  const cases = JSON.parse(XQ.get('cases') || '[]');
  (async () => {
    for (const c of cases) {
      theme(c.theme || themeName(), c.clearHex);
      setSize(c.px || 512, !!c.small, c.pxRatio || 1);
      if (XMODE === 'lod') { await lod(c); continue; }
      runCase(c);
      if (XMODE === 'golden') await post(c.name + '.json', { case: c, ...dumpFrame() });
      else { render(); await post(c.name + '.png', renderer.domElement.toDataURL('image/png')); }
    }
    await post('done', 'ok');
  })().catch(e => post('error', String(e && e.stack || e)));
}

// watch LOD: per item, the most pixels it covers in any frame of any state/reaction at the watch size (ID render, no AA)
async function lod(c) {
  const px = c.px, rt = new THREE.WebGLRenderTarget(px, px), buf = new Uint8Array(px * px * 4), best = new Float32Array(N);
  const idMat = new THREE.ShaderMaterial({ glslVersion: THREE.GLSL3,
    vertexShader: 'in vec4 aX; flat out int vId; void main(){ vId = int(aX.y + 0.5); gl_Position = projectionMatrix * modelViewMatrix * instanceMatrix * vec4(position, 1.0); }',
    fragmentShader: 'flat in int vId; out vec4 o; void main(){ int i = vId + 1; o = vec4(float(i % 256) / 255.0, float(i / 256) / 255.0, 0.0, 1.0); }' });
  const keep = { m: vox.material, inner: inner.visible, halo: halo.visible, floor: floor.visible, outline: outline.visible };
  const shoot = () => {
    vox.material = idMat; inner.visible = halo.visible = floor.visible = outline.visible = false;
    renderer.setRenderTarget(rt); renderer.setClearColor(0, 0); renderer.clear(); renderer.render(scene, camera);
    renderer.readRenderTargetPixels(rt, 0, 0, px, px, buf); renderer.setRenderTarget(null);
    vox.material = keep.m; inner.visible = keep.inner; halo.visible = keep.halo; floor.visible = keep.floor; outline.visible = keep.outline;
    const cnt = new Float32Array(N);
    for (let p = 0; p < px * px; p++) { const i = buf[p * 4] + 256 * buf[p * 4 + 1] - 1; if (i >= 0) cnt[i]++; }
    for (let i = 0; i < N; i++) best[i] = Math.max(best[i], cnt[i]);
  };
  const runs = [];
  for (const s of Object.keys(STATES)) runs.push({ state: s, frames: 240, mic: 0.7, tts: 0.7 });
  for (const s of ['idle', 'speaking']) for (const e of ['happy', 'confused', 'apologetic']) runs.push({ state: s, emotion: e, frames: 120, tts: 0.7 });
  for (const r of REACTION_NAMES) runs.push({ state: 'idle', frames: Math.ceil(REACTIONS[r].d * 60) + 10, reaction: r, reactAt: 1 });
  for (const lx of [-1, 1]) for (const ly of [-1, 1]) runs.push({ state: 'idle', frames: 90, look: [lx, ly] }); // the idle wander's extremes
  for (const rc of runs) {
    reset(); if (rc.emotion) emotion = rc.emotion; setState(rc.state); ext.base = rc.state; ext.mic = rc.mic || 0; ext.tts = rc.tts || 0;
    if (rc.look) { gaze.tx = rc.look[0]; gaze.ty = rc.look[1]; }
    for (let f = 1; f <= rc.frames; f++) {
      if (rc.reaction && f === rc.reactAt) triggerReaction(liveReactions, rc.reaction, clock);
      clock += 1 / 60; gaze.last = clock; liveStep(1 / 60);
      if (f % 6 === 0) shoot();
    }
    await new Promise(r => setTimeout(r, 0));
  }
  await post(c.name + '.json', { px, pix: Array.from(best) });
}
}
