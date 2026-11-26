package dev.hect.lauravoice.avatar

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Native port of the WebView avatar's live loop (clients/android/app/src/main/assets/avatar/index.html): state
 * targets with exponential damping, reaction layers, blink, gaze, the ponytail chain, the face bitmaps and the
 * per-instance attributes of its one voxel shader. All tables come from laura-model.json ([LauraData]).
 * [step] advances time and fills [instances]: per drawn cube 37 floats (instance matrix, aLit, aShd, aFx, aX, aY, aN).
 * [watch] keeps only the body cubes that ever show at the watch's size (hidden ones never draw).
 */
class AvatarModel(val watch: Boolean = false, private val lodPx: Int = WATCH_MIN_PX) {
    // ---- inputs (any thread) ----
    @Volatile var micInput = 0f
    @Volatile var ttsInput = 0f
    @Volatile var car = false
    /** Peak inner-light level (the owner-approved 0.67 of v1). */
    @Volatile var glow = 0.67f
    /** Halo alpha multiplier (dark theme only). */
    @Volatile var haloScale = 1f
    /** "dark" (on black), "light" or "clear" (transparent overlay: only her cubes, warm outline). Set by the view. */
    @Volatile var theme = "dark"
    /** The view is <= 180 dp: bigger charm, no idle glint (as the WebView at <= 180 CSS px). Set by the view. */
    @Volatile var small = false
    @Volatile var reducedMotion = false
    /** No random blinks, idle wander, curious tilts or glints (render comparisons). */
    var deterministic = false
    private val pending = ConcurrentLinkedQueue<Pair<String, String>>()

    fun setBase(s: String) = pending.add("base" to s)
    fun setState(s: String) = pending.add("state" to s)
    fun triggerReaction(name: String) = pending.add("reaction" to name)
    fun setEmotion(name: String) = pending.add("emotion" to name)
    fun poke() = pending.add("poke" to "")
    /** Gaze target in -1..1 (x right, y up), as the WebView's look(). */
    fun look(x: Float, y: Float) = pending.add("look" to "$x,$y")

    internal val d = LauraData.shared
    private val items = d.items.filter { it.k != LauraData.HAND && it.k != LauraData.WRAP && !it.gone && !(watch && it.k == LauraData.SHELL && it.wpx <= lodPx) }

    /** Per drawn instance: mat4 (16), aLit (3), aShd (3), aFx (4), aX (4), aY (4), aN (3). */
    val instances = FloatArray(items.size * STRIDE)
    var count = 0; private set
    val poolSize get() = items.size

    // ---- per-frame uniforms and scene values (read by the renderer after step) ----
    val uLight = FloatArray(3); var uLightI = 0f; var uHairK = 0f; var uHairOn = 0f; var uPartK = 0f; var uWake = 9f
    var uDesat = 0f; var uAccFloor = 0f; var uSkinK = 1f; var uScan = 0f; var uScanY = 0f; var uGlint = 9f
    val uRimCol = FloatArray(3); val uHairUp = FloatArray(4); val uBodyInv = FloatArray(9); val uHead = FloatArray(16)
    val inner = FloatArray(10) // position, quaternion, scale
    var haloAlpha = 0f; var haloY = 0.3f; val haloColor = FloatArray(3)
    var floorAlpha = 0f; var floorScaleX = 1f; val floorColor = FloatArray(3)
    internal var th: LauraData.Theme = d.themes.getValue("dark"); private set

    // ---- live state ----
    private var clock = 0.0
    private var state = "idle"; private var stateStart = 0.0; private var emotion = "none"; private var baseState = "idle"
    private val nk = d.baseKeys.size
    private val P = d.base.copyOf(); private val T = d.base.copyOf(); private val pose = d.base.copyOf()
    private val fastK = DoubleArray(nk) { d.fast[d.baseKeys[it]] ?: 6.0 }
    private val numIdx = d.numKeys.map { d.baseKeys.indexOf(it) }.toIntArray()
    private val rmDropK = BooleanArray(d.numKeys.size) { d.numKeys[it] in d.rmDrop }
    private val lightCur = d.lights.getValue("idle").copyOf(3); private val lightTarget = d.lights.getValue("idle").copyOf(3)
    private val tint = DoubleArray(3)
    private var pEyes = LauraData.Eyes("open"); private var pMouth = "soft"
    private var pBlink = 1.0; private var pMic = 0.0; private var pAmp = 0.0; private var pAmpSlow = 0.0; private var pWake = -1.0; private var pView = 0.0
    private class Live(val r: LauraData.Reaction, val t0: Double) { var tRel = -1.0; var wRel = 1.0 }
    private val live = ArrayList<Live>()
    private var crownAng = 0.0
    private var micLevel = 0.0; private var ttsVal = 0.0; private var ampSlow = 0.0
    private var pokeAt = -10.0; private var boostUntil = 0.0; private var intrAt = -1.0
    private var blinkNext = 2.0; private var blinkStart = -1.0; private var blinkDbl = false; private var blinkOnset = -10.0
    private var curiousNext = 6.0; private var curiousStart = -10.0; private var curiousDir = 1; private var curiousAmp = 0.17
    private var gx = 0.0; private var gy = 0.0; private var gtx = 0.0; private var gty = 0.0; private var gLast = -10.0; private var gWanderAt = 0.0; private var gwx = 0.0; private var gwy = 0.0
    private var glintNext = 9.0; private var glintT0 = -10.0
    private var spkLvl = 0; private var spkAt = -9.0; private var spkLoud = -9.0; private var spkEnv = 0.0
    private val rnd = java.util.Random()
    private val vis = DoubleArray(items.size); private val vv = DoubleArray(items.size)
    private var wakeOnStart = true

    private fun k(n: String) = d.baseKeys.indexOf(n).also { check(it >= 0) { n } }
    private val kPitch = k("pitch"); private val kNod = k("nod"); private val kYaw = k("yaw"); private val kRoll = k("roll"); private val kY = k("y"); private val kZ = k("z")
    private val kSq = k("sq"); private val kScale = k("scale"); private val kGlow = k("glow"); private val kLight = k("light"); private val kListen = k("listen"); private val kSpeak = k("speak")
    private val kThink = k("think"); private val kDim = k("dim"); private val kDesat = k("desat"); private val kSatR = k("satR"); private val kSatY = k("satY"); private val kSatVis = k("satVis")
    private val kSatSpin = k("satSpin"); private val kDrift = k("drift"); private val kFIdle = k("fIdle"); private val kEar = k("ear"); private val kCrown = k("crown"); private val kVoice = k("voice")
    private val kRest = k("rest"); private val kMotes = k("motes"); private val kRibPerk = k("ribPerk"); private val kRibDroop = k("ribDroop"); private val kRibDrape = k("ribDrape")
    private val kErr = k("err"); private val kFollow = k("follow"); private val kEyeScale = k("eyeScale"); private val kEyeSy = k("eyeSy"); private val kLowLid = k("lowLid")
    private val kEyeX = k("eyeX"); private val kEyeY = k("eyeY"); private val kBlush = k("blush"); private val kBlushBase = k("blushBase"); private val kHandVis = k("handVis")
    private val kBreath = k("breath"); private val kBobAmp = k("bobAmp"); private val kHalo = k("halo"); private val kSleep = k("sleep"); private val kOffline = k("offline")
    private val kAccent = k("accent"); private val kHairK = k("hairK"); private val kSparkle = k("sparkle"); private val kFringeLift = k("fringeLift")
    private val ribKeyIdx = d.ribKeys.map { k(it.second) }.toIntArray()

    // ponytail and tassel chains (rig space; the native rig never rotates, so rig = world)
    private inner class Chain(val n: Int, val seg: Double) {
        val p = Array(n) { V3() }; val o = Array(n) { V3() }; var init = false
        fun step(dt: Double, tg: Array<V3>, rate: Double, slack: Double, fixed: Boolean, keep0: Double = 0.88) {
            if (fixed || !init) { for (i in 0 until n) { p[i].copy(tg[i]); o[i].copy(tg[i]) }; init = true; return }
            if (dt <= 0) return
            p[0].copy(tg[0]); o[0].copy(tg[0])
            val keep = keep0.pow(dt * 60)
            for (i in 1 until n) {
                val s = i.toDouble() / (n - 1)
                val vx = (p[i].x - o[i].x) * keep; val vy = (p[i].y - o[i].y) * keep; val vz = (p[i].z - o[i].z) * keep
                o[i].copy(p[i])
                p[i].x += vx; p[i].y += vy - 6 * dt * dt; p[i].z += vz
                p[i].lerp(tg[i], damp(rate * (1 - slack * s), dt))
            }
            repeat(2) { for (i in 1 until n) { tv.sub(p[i], p[i - 1]); val l = tv.len().let { if (it == 0.0) 1e-6 else it }; p[i].copy(p[i - 1]).addScaled(tv, seg / l) } }
        }
    }
    private val tv = V3()
    private val rib = Chain(d.nr, d.chainSeg); private val tassel = Chain(3, 0.24)
    private var swY = 0.0; private var swV = 0.0; private var swInit = false; private var swD = 0.0
    private val ribT = Array(d.nr) { V3() }; private val tasT = Array(3) { V3() }
    private val ribU = Array(d.nr) { V3() }; private val ribV = Array(d.nr) { V3() }; private val ribD = Array(d.nr) { V3() }

    init {
        resetVis()
        applyTheme()
    }

    private fun resetVis() {
        for ((n, it) in items.withIndex()) {
            vv[n] = 0.0
            vis[n] = if (it.k == LauraData.EYE || it.k == LauraData.CATCH || it.k == LauraData.MOUTH || it.k == LauraData.SAT || it.spark) 0.0 else 1.0
        }
    }

    private fun applyTheme() { if (th.name != theme) th = d.themes[theme] ?: d.themes.getValue("dark") }

    // ---------------------------------------------------------------- inputs
    private fun bump() { boostUntil = clock + 2.5 }

    private fun enterState(s: String) {
        val prev = state; state = s; stateStart = clock; bump()
        if (s != prev && s != "sleeping" && s != "offline" && (s == "interrupted" || clock - blinkOnset >= 0.8)) { blinkNext = min(blinkNext, clock); blinkOnset = clock }
        if (s == "interrupted") {
            val shut = d.mouths.getValue("shut")
            for ((n, it) in items.withIndex()) if (it.k == LauraData.MOUTH) { vis[n] = if (shut[it.r][it.c] == '#') 1.0 else 0.0; vv[n] = 0.0 }
        }
        if (prev == "sleeping" && s == "idle") addReaction("wake")
    }

    private fun addReaction(name: String) {
        val r = d.reactions[name] ?: return
        for (l in live) if (l.tRel < 0) { l.wRel = weight(l, clock); l.tRel = clock }
        live += Live(r, clock)
        while (live.size > 4) live.removeAt(0)
    }

    private fun weight(l: Live, t: Double): Double {
        val u = t - l.t0; val dd = l.r.d
        var w = smooth(0.0, 0.22, u) * (1 - smooth(dd - 0.38, dd, u))
        if (l.tRel >= 0) w = min(w, l.wRel * (1 - smooth(l.tRel, l.tRel + 0.3, t)))
        return w
    }

    private fun drain() {
        while (true) {
            val (key, v) = pending.poll() ?: break
            when (key) {
                "base" -> { baseState = v; if (!((state == "interrupted" || state == "ignored") && (v == "idle" || v == "listening"))) enterState(v) }
                "state" -> enterState(v)
                "reaction" -> { bump(); addReaction(v) }
                "emotion" -> emotion = v
                "look" -> { val (x, y) = v.split(','); gtx = x.toDouble(); gty = y.toDouble(); gLast = clock; bump() }
                "poke" -> {
                    pokeAt = clock; bump()
                    when (state) {
                        "sleeping" -> enterState("idle")
                        "offline", "error" -> addReaction("shake")
                        else -> addReaction(if (rnd.nextBoolean()) "happy" else "shy")
                    }
                }
            }
        }
    }

    /** The frame-rate tier (as the WebView's autoFps): 60 while busy or reacting, 30 idle, 20 overlay idle, 10 asleep. */
    fun autoFps(settled: Boolean = false): Int {
        if (!settled && clock < boostUntil) return 60
        if (live.isNotEmpty()) return 60
        if (state == "sleeping" || state == "offline") return 10
        if (state == "error") return if (clock - stateStart < 3) 60 else 20
        if (theme == "clear" && state == "idle") return 20
        if (state == "thinking" || state == "idle" || state == "notowner" || state == "ignored") return 30
        return 60
    }

    // ---------------------------------------------------------------- states
    private fun stateTargets(t: Double, mic: Double, amp: Double, aSlow: Double): Pair<LauraData.Eyes, String> {
        d.base.copyInto(T)
        var eyes = LauraData.Eyes("open"); var mouth = "soft"; var light = "idle"
        val u = t - stateStart
        T[kLight] = d.lights.getValue("idle")[3]; T[kFIdle] = 0.0; T[kHairK] = d.hairK[state] ?: 0.7
        when (state) {
            "sleeping" -> { eyes = LauraData.Eyes("sleep"); mouth = "none"; light = "sleeping"; T[kLight] = 0.2 * (0.7 + 0.3 * cos(TAU * t / (2 * d.loopL))); T[kBreath] = 0.045; T[kY] = -0.4; T[kRoll] = 0.175
                T[kFollow] = 0.0; T[kSleep] = 1.0; T[kBobAmp] = 0.08; T[kRest] = 1.0; T[kRibDroop] = 1.0; T[kHalo] = 0.5 }
            "listening" -> { light = "listening"; mouth = "none"; T[kLight] = 0.55 + 0.45 * mic; T[kListen] = 1.0; T[kPitch] = 0.14; T[kZ] = 0.6; T[kEyeScale] = 1.10; T[kScale] = 1 + 0.03 + mic * 0.03
                T[kEar] = 1.0; T[kRibPerk] = 1.0; T[kRoll] = 0.05 }
            "thinking" -> { light = "thinking"; mouth = "small"; T[kLight] = 0.7; T[kThink] = 1.0; T[kFollow] = 0.0; T[kEyeX] = 0.6; T[kEyeY] = 0.8; T[kEyeScale] = 0.9; T[kRoll] = -0.1
                T[kCrown] = 1.0; T[kBobAmp] = 0.12
                if (u > 4 && ((u - 4) % 4) < 0.6) { val b = sin(Math.PI * ((u - 4) % 4) / 0.6); T[kEyeX] -= 1.1 * b; T[kLight] = 0.8 } }
            "speaking" -> { light = "speaking"; T[kLight] = 0.6 + 0.4 * aSlow; T[kSpeak] = 1.0; mouth = "speak"; T[kEyeScale] = 0.95; T[kEyeY] = 0.18
                T[kLowLid] = smooth(0.62, 0.85, amp); T[kBlush] = 0.15
                T[kSq] = amp * 0.06; T[kNod] = clamp(0.06 * (2 * amp - 0.6), -0.06, 0.06); T[kVoice] = 1.0; T[kDrift] = 0.2; T[kFollow] = 0.6 }
            "interrupted" -> { light = "listening"; mouth = "shut"; val pop = if (u < 0.12) sin(Math.PI * u / 0.12) else 0.0; val inn = smooth(0.2, 0.42, u)
                T[kLight] = 0.75; T[kListen] = inn; T[kScale] = 1 - 0.04 * pop + 0.03 * inn; T[kPitch] = -0.087 * pop + 0.14 * inn + 0.07 * sin(Math.PI * clamp((u - 0.3) / 0.4, 0.0, 1.0))
                T[kZ] = 0.6 * inn; T[kEar] = inn; T[kRibPerk] = inn; T[kEyeScale] = 0.92 - 0.02 * inn }
            "ignored" -> { light = "ignored"; mouth = "soft"; val g = 1 - smooth(0.8, 1.1, u)
                T[kLight] = d.lights.getValue("idle")[3] * 0.8; T[kFollow] = 0.2; T[kEyeY] = -0.85 * g * smooth(0.0, 0.12, u); T[kEyeX] = -0.2 * g; T[kRoll] = 0.052; T[kBlushBase] = 0.0 }
            "notowner" -> { light = "notowner"; mouth = "flat"; T[kLight] = 0.35; T[kPitch] = -0.14; T[kYaw] = 0.26; T[kZ] = -0.5; T[kScale] = 0.95; T[kEyeSy] = 0.75; T[kFollow] = 0.0
                T[kDrift] = 0.3; T[kEyeX] = -0.7; T[kBlushBase] = 0.0 }
            "error" -> { light = "error"; T[kLight] = if (u < 3) 0.55 + 0.15 * sin(TAU * 1.2 * u) else 0.4; eyes = LauraData.Eyes(if (u < 3) "chev" else "sad"); mouth = if (u < 3) "flat" else "frown"
                T[kRoll] = -0.21; T[kY] = -0.3; T[kErr] = 1.0; T[kDrift] = 0.0; T[kFollow] = 0.1; T[kDim] = 0.85; T[kBlushBase] = 0.15 }
            "offline" -> { light = "offline"; mouth = "none"; T[kLight] = 0.05; T[kDesat] = 0.4; eyes = LauraData.Eyes("off"); T[kY] = -0.45; T[kSq] = -0.06; T[kRoll] = 0.06; T[kPitch] = 0.21
                T[kFollow] = 0.0; T[kBreath] = 0.006; T[kBobAmp] = 0.02; T[kOffline] = 1.0; T[kHalo] = 0.2; T[kRibDrape] = 1.0; T[kAccent] = 0.0; T[kBlushBase] = 0.0 }
        }
        if (state == "listening" && mouth == "none") mouth = "soft"
        if (state == "listening") T[kEyeScale] = 0.9
        if (state == "offline") mouth = "off"
        if (state == "thinking" && mouth == "small") mouth = "think"
        if (state == "notowner") T[kEyeX] = -2.2
        if (state == "ignored") { T[kEyeY] = -2.3 * smooth(0.2, 0.3, u) * (1 - smooth(1.3, 1.4, u)); T[kHairK] = 0.5 * d.hairK.getValue("idle") }
        val emoOk = state == "idle" || state == "listening" || state == "speaking" || state == "thinking"
        val quiet = mouth == "none" || mouth == "soft" || mouth == "small"
        if (emoOk && emotion == "happy") { eyes = LauraData.Eyes("happy"); T[kBlush] = 1.0; T[kSparkle] = 1.0; T[kBobAmp] += 0.1; T[kDrift] *= 1.4; if (quiet) mouth = "smile" }
        if (emoOk && emotion == "confused") { eyes = LauraData.Eyes("open", "narrow"); T[kRoll] += 0.2; T[kEyeX] += 0.3 * sin(t * 1.3); if (quiet) mouth = "wavy" }
        if (emoOk && emotion == "apologetic") { eyes = LauraData.Eyes("sad"); T[kY] -= 0.4; T[kScale] *= 0.95; T[kPitch] -= 0.1; T[kDrift] *= 0.6; T[k("ribDroop")] = 0.6; if (quiet) mouth = "frown" }
        if (car) {
            T[kSatVis] = 0.0; T[kFollow] = 0.0; T[kListen] *= 0.3; T[kSpeak] *= 0.35; T[kSq] *= 0.3; T[kNod] *= 0.3; T[kBobAmp] *= 0.35; T[kHandVis] = 0.0; T[kHalo] *= 0.6; T[kScale] *= 0.94; T[kDrift] = 0.0
            if (state == "idle" || state == "listening" || state == "speaking") { eyes = LauraData.Eyes("calm"); light = "car"; T[kLight] = 0.45 + 0.2 * aSlow; T[kLowLid] = 0.0; T[kEyeY] = 0.0 }
            if (mouth == "speak") mouth = "soft"
            T[kPitch] *= 0.4; T[kZ] *= 0.3; T[kRibPerk] *= 0.3
        }
        T[kHandVis] = 0.0
        if (theme == "clear") T[kBobAmp] *= 0.5
        if (reducedMotion) { T[kBobAmp] = 0.0; T[kRoll] = 0.0; T[kSq] = 0.0; T[kNod] = 0.0; T[kBreath] = 0.0; T[kDrift] = 0.0 }
        d.lights.getValue(light).copyInto(lightTarget, 0, 0, 3)
        return eyes to mouth
    }

    private fun blinkValue(t: Double): Double {
        if (deterministic) return 1.0
        if (t > blinkNext) { blinkStart = t; blinkDbl = rnd.nextDouble() < 0.2; blinkNext = t + 2.2 + rnd.nextDouble() * 4 * (if (car) 1.8 else 1.0) }
        val u = t - blinkStart
        if (u >= 0 && u < 0.15) return 1 - sin(Math.PI * u / 0.15)
        if (blinkDbl && u >= 0.22 && u < 0.37) return 1 - sin(Math.PI * (u - 0.22) / 0.15)
        return 1.0
    }

    private fun follow(cur: Double, v: Double, up: Double, down: Double, dt: Double) = cur + (v - cur) * damp(if (v > cur) up else down, dt)

    private val sample = DoubleArray(64)
    private fun applyReactions(t: Double, gain: Double) {
        var best = 0.0; var bestEyes: LauraData.Eyes? = null; var bestMouth: String? = null
        pWake = -1.0
        for (i in live.indices.reversed()) {
            val l = live[i]; val w = weight(l, t) * gain; val r = l.r
            if (t - l.t0 > r.d || (l.tRel >= 0 && t > l.tRel + 0.3)) { live.removeAt(i); continue }
            if (r.name == "wake") pWake = t - l.t0
            if (w <= 0) continue
            val u = t - l.t0; val s = clamp(u * r.rate, 0.0, (r.n - 1).toDouble()); val i0 = floor(s + 1e-6).toInt().coerceAtMost(r.n - 1); val i1 = min(i0 + 1, r.n - 1); val fr = clamp(s - i0, 0.0, 1.0)
            for ((ki, key) in d.numKeys.withIndex()) {
                val v = r.keys[key] ?: continue
                if (reducedMotion && rmDropK[ki]) continue
                pose[numIdx[ki]] += w * (v[i0] + (v[i1] - v[i0]) * fr)
            }
            val tn = r.tint[i0]
            if (tn != null) { val c = d.tints.getValue(tn); val a = w * clamp(r.tintAmt[i0], 0.0, 0.5); for (j in 0..2) tint[j] += (c[j] - tint[j]) * a }
            if (w > best && l.tRel < 0 && u > 0.08 && u < r.d - 0.45) { best = w; bestEyes = r.eyes[i0]; bestMouth = r.mouth[i0] }
        }
        if (bestEyes != null) pEyes = bestEyes
        if (bestMouth != null && pMouth != "speak") pMouth = bestMouth
    }

    // ---------------------------------------------------------------- live loop
    /** Advances [dtIn] seconds (clamped as the WebView: <= 0.12 s) and fills [instances] and the uniforms. */
    fun step(dtIn: Float) = stepD(min(0.12, max(0.0, dtIn.toDouble())))

    internal fun stepD(dt: Double) {
        applyTheme()
        if (wakeOnStart) { wakeOnStart = false; enterState("idle"); addReaction("wake") }
        clock += dt
        drain()
        val t = clock
        val rm = reducedMotion
        if (state == "interrupted" && t - stateStart > 0.7) enterState(if (pMic > 0.15 || baseState == "interrupted") "listening" else baseState)
        if (state == "ignored" && t - stateStart > 2.4) enterState(if (baseState == "ignored") "idle" else baseState)
        micLevel = follow(micLevel, micInput.toDouble(), 42.0, 7.7, dt)
        ttsVal = follow(ttsVal, if (state == "speaking") ttsInput.toDouble() else 0.0, 55.0, 17.0, dt)
        val m = micLevel; val a = ttsVal
        ampSlow = follow(ampSlow, if (state == "speaking") a else 0.0, 1 / 0.06, 1 / 0.4, dt)
        val (fe, fmo) = stateTargets(t, m, a, ampSlow)
        val clear = theme == "clear"
        if (state == "idle" && !car && !rm && !deterministic) {
            if (t > curiousNext) { curiousStart = t; curiousDir = if (rnd.nextBoolean()) -1 else 1; curiousAmp = if (clear) 0.07 else 0.17; curiousNext = t + if (clear) 20 + rnd.nextDouble() * 20 else 6 + rnd.nextDouble() * 6 }
            val cu = t - curiousStart; T[kRoll] += curiousDir * curiousAmp * smooth(0.0, 0.4, cu) * (1 - smooth(1.4, 1.9, cu))
        }
        val pu = t - pokeAt; if (pu < 0.9 && !rm) T[kSq] += -0.16 * exp(-pu * 5) * cos(pu * 18)
        for (i in 0 until nk) P[i] += (T[i] - P[i]) * damp(if (rm) 15.0 else fastK[i], dt)
        if (state == "interrupted") {
            P[kEar] = max(P[kEar], smooth(0.0, 0.12, t - stateStart))
            if (intrAt != stateStart) { intrAt = stateStart; P[kVoice] = 0.0; for ((n, it) in items.withIndex()) if (it.k == LauraData.SAT) vis[n] = 0.0 }
        }
        val burst = state == "thinking" && t - stateStart > 4 && ((t - stateStart - 4) % 4) < 0.6
        if (!rm) crownAng += dt * TAU * 0.5 * (if (burst) 3 else 1)
        val lk = damp(12.0, dt); for (j in 0..2) lightCur[j] += (lightTarget[j] - lightCur[j]) * lk
        var gxT = gtx; var gyT = gty
        val touched = max(pokeAt, if (clear) -1e9 else gLast)
        if (!deterministic && t - gLast > 2.5 && !(clear && t - touched > 10) && !rm) {
            if (t > gWanderAt) { gwx = (rnd.nextDouble() - 0.5) * 1.2; gwy = (rnd.nextDouble() - 0.5) * 0.7; gWanderAt = t + 1.5 + rnd.nextDouble() * 2.5 }
            gxT = gwx; gyT = gwy
        }
        if (!deterministic && clear && t - gLast > 2.5 && t - touched > 10) { gxT = 0.0; gyT = 0.0 }
        gx += (gxT * P[kFollow] - gx) * damp(5.0, dt); gy += (gyT * P[kFollow] - gy) * damp(5.0, dt)
        P.copyInto(pose)
        for (j in 0..2) tint[j] = lightCur[j]
        pose[kYaw] += gx * 0.36; pose[kPitch] -= gy * 0.16; pose[kRoll] -= gx * 0.04
        pose[kEyeX] += gx * 0.9; pose[kEyeY] += gy * 0.9
        pView = pose[kYaw]
        pEyes = fe; pMouth = fmo; pMic = m; pAmp = if (car) 0.0 else a; pAmpSlow = if (car) 0.0 else ampSlow
        pBlink = if (state == "sleeping" || state == "offline") 1.0 else blinkValue(t)
        applyReactions(t, if (car) 0.4 else 1.0)
        writeInstances(t, t, dt)
    }

    // ---------------------------------------------------------------- pose -> instances
    private var xfSxz = 1.0; private var xfSy = 1.0; private var xfS = 1.0; private var xfBodyY = 0.0; private var xfZ = 0.0
    private val bq = DoubleArray(4); private val q2 = DoubleArray(4); private val q3 = DoubleArray(4); private val gemQ = DoubleArray(4); private val diaQ = DoubleArray(4)
    private val vP = V3(); private val vT = V3(); private val tR = V3(); private val tZ = V3(); private val e3 = V3()
    private val wLit = DoubleArray(3); private val wShd = DoubleArray(3)
    private val ribC1 = V3(); private val ribC2 = V3()

    private fun bodyXf(x: Double, y: Double, z: Double, o: V3): V3 {
        o.set(x * xfSxz * xfS, ((y + d.piv) * xfSy - d.piv) * xfS, z * xfSxz * xfS).rot(bq)
        o.y += xfBodyY; o.z += xfZ; return o
    }

    private fun faceZ(x: Double, y: Double): Double {
        val kx = Math.round(x / d.vs).toInt(); val ky = floor(y / d.vs).toInt()
        return (d.faceZ[LauraData.key(kx, ky)] ?: 3.0) * d.vs + 0.5 * d.vs + 0.025
    }

    private fun onFace(fz: Double, lift: Double) = fz - 0.425 + (0.4 + lift) / (xfSxz * xfS)

    private fun speakPat(amp: Double, t: Double): List<String> {
        val up = doubleArrayOf(0.12, 0.35, 0.62); val dn = doubleArrayOf(0.08, 0.3, 0.56)
        var tgt = 0; for (i in 0 until 3) if (amp >= (if (i < spkLvl) dn[i] else up[i])) tgt = i + 1
        if (t < spkAt) { spkAt = -9.0; spkLoud = -9.0 }
        if (tgt != spkLvl && t - spkAt >= 0.05) { spkLvl = tgt; spkAt = t }
        if (spkLvl > 0) { spkLoud = t; return d.speak[spkLvl] }
        return if (t - spkLoud < 0.2 || spkEnv > 0.06) d.mouths.getValue("gap") else d.speak[0]
    }

    private fun mouthPat(spec: String, amp: Double, t: Double) = if (spec == "speak") speakPat(amp, t) else d.mouths[spec] ?: d.mouths.getValue("none")

    private fun visSpring(n: Int, target: Double, dt: Double) {
        if (target <= 0) { vis[n] = 0.0; vv[n] = 0.0; return }
        if (dt <= 0 || reducedMotion) { vis[n] += (target - vis[n]) * (1 - exp(-(if (reducedMotion) 15.0 else 30.0) * max(dt, 1e-4))); vv[n] = 0.0; return }
        val kk = 900.0; val c = 2 * 0.45 * 30
        val steps = ceil(dt / (1.0 / 240)).toInt(); val h = dt / steps
        repeat(steps) { vv[n] += ((target - vis[n]) * kk - vv[n] * c) * h; vis[n] += vv[n] * h }
    }

    private var moteSz = 1.0
    private fun moteForm(i: Int, t: Double, o: V3): Double {
        val p = pose; val base = i < 4; val side = if (i < 2) -1 else 1; val j = i % 2; val dr = if (reducedMotion) 0.0 else p[kDrift]; val ph = TAU * t / d.loopL
        val ear = if (base) p[kEar] else 0.0; val voice = if (base) p[kVoice] else 0.0; val rest = if (i >= 3) p[kRest] else 0.0; val mo = if (base) p[kMotes] else 0.0
        val wIdle = max(0.0, mo - ear - p[kCrown] - voice) + 0.001
        val w = wIdle + ear + p[kCrown] + voice + rest
        var x = 0.0; var y = 0.0; var z = 0.0; moteSz = 1.0
        run { val kq = wIdle / w; x += kq * (if (base) side * (5.0 + 0.3 * j) + dr * 0.2 * sin(ph + i * 1.3) else 0.0); y += kq * (if (base) 0.2 + 1.5 * j + dr * 0.3 * sin(ph + i * 1.7) else 6.0); z += kq * (if (base) 1.2 - 1.0 * j else 0.0) }
        if (ear > 1e-3) { val kq = ear / w; val an = Math.PI * (0.13 + 0.247 * i); val r = min(7.3, 5.6 + pMic * (0.3 + 0.35 * hash(i + 3.0)) * max(p[kListen], 0.4))
            val sh = if (reducedMotion) 0.0 else (1 - pMic) * 0.12 * sin(TAU * t + i * 1.6)
            x += kq * cos(an) * r; y += kq * (0.3 + sin(an) * r * 0.9 + sh); z += kq * 2.6 }
        if (p[kCrown] > 1e-3) { val kq = p[kCrown] / w; val an = crownAng + TAU * i / 6; val rx = cos(an) * 2.5; val rz = sin(an) * 1.6
            x += kq * rx; y += kq * (6.1 - rz * SIN15); z += kq * rz * COS15; moteSz += (d.crownSz[i] - 1) * clamp(p[kCrown], 0.0, 1.0) }
        if (voice > 1e-3) { val kq = voice / w; x += kq * side * (5.1 + 0.15 * pAmp) * d.vs; y += kq * (d.mouthY + 0.12); z += kq * 2.5 }
        if (rest > 1e-3) { val kq = rest / w; val g = i - 3; val uu = (((t - g * 0.6) / 4) % 1 + 1) % 1; val life = uu * 4 / 2.4
            x += kq * (3.4 + 0.5 * g + 0.9 * min(life, 1.0)); y += kq * (3.6 + 0.9 * g + 1.6 * min(life, 1.0)); z += kq * 1.2; moteSz = d.sleepSz[g] }
        var v = clamp(ear + p[kCrown] + (if (i == 0 || i == 3) voice else 0.0) + mo, 0.0, 1.0)
        if (rest > 1e-3) { val g = i - 3; val uu = (((t - g * 0.6) / 4) % 1 + 1) % 1; val life = uu * 4 / 2.4
            v = max(v, if (life <= 1) rest * p[kSleep] * smooth(0.0, 0.12, life) * (1 - smooth(0.75, 1.0, life)) else 0.0) }
        if (voice > 0.5) moteSz *= 0.7 * (0.8 + 0.4 * pAmp) * (0.6 + 0.4 * smooth(0.05, 0.3, pAmpSlow))
        val f = 1 + clamp(p[kSatR], -2.0, 2.0) / 14; val c = cos(p[kSatSpin]); val s = sin(p[kSatSpin])
        val xr = x * c - z * s; val zr = x * s + z * c
        o.set(xr * f, y + p[kSatY], zr * f)
        return v
    }

    private fun writeInstances(t: Double, tb: Double, dt: Double) {
        val p = pose; val rm = reducedMotion; val S = p[kScale]; val th = th
        val bob = p[kBobAmp] * cos(TAU * tb / d.loopL); val breath = p[kBreath] * cos(TAU * 2 * tb / d.loopL)
        val sqv = p[kSq] + breath; xfSy = 1 + sqv; xfSxz = 1 - sqv * 0.5; xfS = S; xfBodyY = p[kY] + bob; xfZ = p[kZ]
        Q.eulerYXZ(p[kPitch] + p[kNod], p[kYaw], p[kRoll], bq)
        Q.inv(bq, q2); Q.mat3(q2, uBodyInv)
        vT.set(0.0, d.hbandFlat, 0.0).rot(bq); uHairUp[0] = vT.x.toFloat(); uHairUp[1] = vT.y.toFloat(); uHairUp[2] = vT.z.toFloat(); uHairUp[3] = (d.hbandFlatZ * d.hbandFlat).toFloat()
        headMatrix()
        val eox = p[kEyeX] * 0.3; val eoy = p[kEyeY] * 0.22; val es = p[kEyeScale]; val esy = p[kEyeSy]; val bl = pBlink
        if (!rm && dt > 0) { Q.axisAngle(0.0, 1.0, 0.0, TAU * tb / d.loopL, q2); Q.mul(q2, d.gemBase, gemQ) } else d.gemBase.copyInto(gemQ)
        Q.mul(bq, d.rot45, diaQ)
        val accK = if (small) 1.4 else 1.0
        val wu = pWake; val waking = wu >= 0 && wu < 0.9
        val wakeLight = if (waking) (if (rm) smooth(0.0, 0.5, wu) else 0.15 + 0.85 * smooth(0.05, 0.45, wu)) else 1.0
        val wakeEyes = if (waking && wu < (if (rm) 0.1 else 0.3)) 0 else 1
        // ponytail targets (body space), then simulate
        var wsum = 0.0; for (ki in ribKeyIdx) wsum += max(0.0, p[ki])
        val wr = max(0.0, 1 - wsum); val wn = max(1.0, wsum)
        ribC1.set(0.0, 0.0, 0.0); ribC2.set(0.0, 0.0, 0.0)
        for ((n, pr) in d.ribKeys.withIndex()) { val w = max(0.0, p[ribKeyIdx[n]]) / wn; if (w > 0) { val c = d.ribPoses.getValue(pr.first); ribC1.add(c[0][0] * w, c[0][1] * w, c[0][2] * w); ribC2.add(c[1][0] * w, c[1][1] * w, c[1][2] * w) } }
        val sway = if (rm) 0.0 else 0.12 * sin(TAU * tb / d.loopL + 0.6) * (1 - p[kOffline])
        run {
            val Y = p[kYaw]
            if (!swInit || rm || dt <= 0) { swY = Y; swV = 0.0; swInit = true }
            else { val h = min(dt, 1.0 / 30); swV += (d.swW * d.swW * (Y - swY) - 2 * d.swZ * d.swW * swV) * h; swY += swV * h }
            swD = clamp(swY - Y, -d.swMax, d.swMax)
        }
        val swC = cos(swD); val swS = sin(swD); val nr = d.nr; val root = d.tailRoot
        for (i in 0 until nr) {
            val s = i.toDouble() / (nr - 1); val b = 2 * (1 - s) * s; val c = s * s; val r = d.restS[i]; val rx = r[0] * swC + r[2] * swS; val rz = -r[0] * swS + r[2] * swC
            bodyXf(root[0] + b * ribC1.x + c * ribC2.x + wr * rx, root[1] + b * ribC1.y + c * ribC2.y + wr * r[1], root[2] + b * ribC1.z + c * ribC2.z + wr * rz + sway * s * s, ribT[i])
        }
        rib.step(dt, ribT, d.tailRate, 0.55, rm, d.tailKeep)
        val ribL = rib.p
        for (i in 0 until nr) {
            ribD[i].sub(ribL[min(i + 1, nr - 1)], ribL[max(i - 1, 0)]).normalize()
            e3.set(0.0, 0.0, 1.0).rot(bq); ribU[i].cross(ribD[i], e3); if (ribU[i].lenSq() < 0.05) { e3.set(1.0, 0.0, 0.0); ribU[i].cross(ribD[i], e3) }
            ribU[i].normalize(); ribV[i].cross(ribD[i], ribU[i]).normalize()
        }
        for (i in 0 until 3) bodyXf(d.charmAt[0], d.charmAt[1] - (0.36 + 0.24 * i) * accK, d.charmAt[2] + 0.05, tasT[i])
        tassel.step(dt, tasT, 9.0, 0.5, rm)
        // which eye and mouth patterns are on
        val igU = if (state == "ignored") clock - stateStart else -1.0
        val halfBlink = if (igU < 0) 0.0 else smooth(0.2, 0.26, igU) * (1 - smooth(1.34, 1.4, igU))
        val lid = min(bl, 1 - 0.5 * halfBlink)
        val eyeNm = Array(2) { si -> val nm = pEyes.of(if (si == 0) -1 else 1); if (wakeEyes < 1) "sleep" else if (lid < 0.3) "shut" else if (d.eyes.containsKey(nm)) nm else "open" }
        spkEnv = pAmpSlow
        val mp = mouthPat(pMouth, if (rm) pAmp * 0.5 else pAmp, t); val lit = pMouth in d.litMouth
        val wrapW = DoubleArray(2) { si -> val side = if (si == 0) -1 else 1; smooth(1.222, 1.484, -side * pView) * (if (eyeNm[si] in d.openLike) 1.0 else 0.0) }
        val offEye = p[kOffline] > 0.5
        val eyeZ = DoubleArray(2) { si -> val side = if (si == 0) -1 else 1; val cx = side * d.eyeCx + eox; val cy = d.eyeCy + eoy; val hw = 2.9 * d.fs * es; val hh = 3.6 * d.fs * es * esy
            var z = -9.0; var a = -1.0; while (a <= 1.0) { var b = -1.0; while (b <= 1.0) { z = max(z, faceZ(cx + a * hw, cy + b * hh)); b += 0.5 }; a += 0.5 }; z }
        val eyeLit = th.c("eyeLit"); val eyeShd = th.c("eyeShd"); val irisLit = th.c("irisLit"); val irisShd = th.c("irisShd")

        var kk = 0
        val out = instances
        for ((n, it) in items.withIndex()) {
            var x: Double; var y: Double; var z: Double; var s = it.size; var sx2 = 1.0; var sy2 = 1.0; var sz2 = 1.0; var target = 1.0; var q = bq; var dyn = false
            var fx1 = th.fx[it.i * 4 + 1].toDouble(); var x3 = th.x[it.i * 4 + 3].toDouble()
            when (it.k) {
                LauraData.SAT -> {
                    target = moteForm(it.idx, t, vP) * p[kSatVis] * (1 - 0.6 * p[kErr]); x = vP.x * S; y = vP.y + xfBodyY * 0.6; z = vP.z * S
                    q = gemQ; s *= moteSz
                    if (it.idx == 4 && waking && !rm && wu < 0.2) {
                        bodyXf(d.spark[0], d.spark[1], d.spark[2], vP); x = vP.x; y = vP.y; z = vP.z; target = 1.0; s = 0.2 * (0.4 + 0.6 * sin(Math.PI * wu / 0.2))
                    }
                    x3 = 0.0
                }
                LauraData.RIB -> {
                    val i2 = it.idx; val a = ribL[i2]; val u = ribU[i2]; val dd = ribD[i2]; val w = d.tailW[i2]; val v2 = ribV[i2]
                    val o = if (it.cn == 2) (if (it.qi != 0) 0.25 else -0.25) * w else 0.0; val ov = (if (i2 and 1 == 1) 1 else -1) * d.tailAlt * 0.5 * w
                    val ddd = if (i2 == nr - 1) 0.25 * d.tailSeg else 0.0
                    x = a.x + u.x * o + dd.x * ddd + v2.x * ov; y = a.y + u.y * o + dd.y * ddd + v2.y * ov; z = a.z + u.z * o + dd.z * ddd + v2.z * ov
                    tZ.copy(v2); tZ.x = -tZ.x; tZ.y = -tZ.y; tZ.z = -tZ.z
                    q = Q.basis(u, dd, tZ, q3)
                    sx2 = w * (if (it.cn == 2) 0.54 else 0.75) / s; sy2 = d.tailSeg * d.tailOv / s; sz2 = w * 0.82 / s
                }
                LauraData.TIE -> {
                    val a = ribL[1]; val b2 = ribL[2]; val u = ribU[1]; val v = ribV[1]; val dd = ribD[1]; val r = d.tailW[1] * 0.5 * 0.95; val ring = if (it.qi >= 6) 1 else 0
                    val an = (it.qi % 6) * TAU / 6; val f2 = 0.05 + 0.3 * ring
                    tR.set(u.x * cos(an) + v.x * sin(an), u.y * cos(an) + v.y * sin(an), u.z * cos(an) + v.z * sin(an)); tZ.cross(dd, tR)
                    x = a.x + (b2.x - a.x) * f2 + tR.x * r; y = a.y + (b2.y - a.y) * f2 + tR.y * r; z = a.z + (b2.z - a.z) * f2 + tR.z * r
                    q = Q.basis(tZ, dd, tR, q3)
                    sx2 = 2 * Math.PI * r / 6 * 1.2 / s; sy2 = 0.28 * accK / s; sz2 = 0.16 / s; x3 = 0.8 * p[kAccent]
                }
                LauraData.CHARM -> {
                    if (it.tassel >= 0) { val tp = tassel.p[it.tassel + 1]; x = tp.x; y = tp.y; z = tp.z }
                    else { val o = it.o!!; bodyXf(d.charmAt[0] + o[0] * accK, d.charmAt[1] + o[1] * accK, d.charmAt[2] + o[2], vP); x = vP.x; y = vP.y; z = vP.z; q = diaQ }
                    s *= accK; x3 = if (it.core) (if (small) 1.0 else 0.8) * p[kAccent] else if (small) 0.5 * p[kAccent] else 0.0
                }
                else -> {
                    x = 0.0; y = 0.0; z = 0.0
                    when (it.k) {
                        LauraData.SHELL -> {
                            val pp = it.p!!; x = pp[0]; y = pp[1]; z = pp[2]
                            if (it.fringe) y += p[kFringeLift] * 0.3 * d.vs
                            if (it.drop > 0 && p[kErr] > 0.01) { y -= p[kErr] * (1.2 + 0.4 * it.drop); target = 1 - smooth(0.2, 0.9, p[kErr]) }
                            Q.mul(bq, it.q!!, q3); q = q3
                            sx2 = it.s!![0]; sy2 = it.s[1]; sz2 = it.s[2]
                        }
                        LauraData.EYE -> {
                            val si = if (it.side < 0) 0 else 1; val nm = eyeNm[si]; val pat = d.eyes.getValue(nm); val rows = pat.size; val cols = pat[0].length
                            val cx = it.side * d.eyeCx; val sy = es * esy
                            var pc = it.c.toDouble(); var pr = it.r.toDouble()
                            if (it.lash) {
                                val lw = d.lashes[nm]; target = if (lw != null && !offEye && wrapW[si] <= 0) 1.0 else 0.0
                                if (lw != null) { pc = lw[0]; pr = if (nm == "shut" || lid >= 1) lw[1] else max(lw[1], ceil((1 - lid) * rows - 0.5) - 0.55); lashPc[si] = pc; lashPr[si] = pr }
                                else { pc = lashPc[si]; pr = lashPr[si] }
                            } else {
                                target = if (it.r < rows && it.c < cols) { val cc = if (it.side > 0) cols - 1 - it.c else it.c; if (pat[it.r][cc] == '#') 1.0 else 0.0 } else 0.0
                                if (lid < 1 && nm != "shut" && (it.r + 0.5) / rows < 1 - lid) target = 0.0
                                if (p[kLowLid] > 0.5 && rows == 6 && it.r == rows - 1) target = 0.0
                                if (wrapW[si] > 0 && ((if (it.side < 0) it.c else cols - 1 - it.c) + 0.5) / cols < 2 * wrapW[si]) target = 0.0
                            }
                            val dc = if (it.lash) pc else it.c.toDouble()
                            val colPos = if (it.side > 0 && it.lash) cols - 1 - dc else dc
                            x = cx + eox + (colPos - (cols - 1) / 2.0) * d.fs * es; y = d.eyeCy + eoy + ((rows - 1) / 2.0 - pr + (d.eyeDy[nm] ?: 0.0)) * d.fs * sy
                            z = onFace(eyeZ[si], 0.05) - d.fs * es * it.flat * 0.5; s *= es; sx2 = 1.18; sy2 = esy * 1.18; sz2 = it.flat
                            if (it.lash) { sx2 = 0.85; sy2 = esy * 0.7 }
                            val ir = d.iris[nm]; val bot = ir != null && it.r == ir[1] && !it.lash; val mid = ir != null && it.r == ir[0] && ir[0] != ir[1] && !it.lash
                            z -= if (bot) 0.004 else if (mid) 0.002 else 0.0
                            dyn = true
                            if (offEye) { val c = th.c("eyeOff"); for (j in 0..2) { wLit[j] = c[j]; wShd[j] = c[j] * 0.85 } }
                            else { val lc = if (bot) irisLit else eyeLit; val sc2 = if (bot) irisShd else eyeShd; val kq = if (mid) 0.5 else 0.0
                                for (j in 0..2) { wLit[j] = lc[j] + (irisLit[j] - lc[j]) * kq; wShd[j] = sc2[j] + (irisShd[j] - sc2[j]) * kq } }
                        }
                        LauraData.CATCH -> {
                            val si = if (it.side < 0) 0 else 1; val nm = eyeNm[si]; val lay = d.catches[nm]; val pat = d.eyes.getValue(nm); val rows = pat.size; val cols = pat[0].length
                            if (lay != null && it.qi < lay.size && lid > 0.8 && wrapW[si] < 0.05 && !offEye) {
                                val (c, r, sc3) = lay[it.qi].let { Triple(it[0], it[1], it[2]) }
                                x = it.side * d.eyeCx + eox + (c - (cols - 1) / 2.0) * d.fs * es; y = d.eyeCy + eoy + ((rows - 1) / 2.0 - r + (d.eyeDy[nm] ?: 0.0)) * d.fs * es * esy; s *= es * sc3
                                sx2 = if (sc3 < 1) 1.0 else 1.18; sy2 = esy
                            } else { target = 0.0; x = it.side * d.eyeCx; y = d.eyeCy }
                            z = onFace(eyeZ[si], 0.068) - s * it.flat * 0.5; sz2 = it.flat
                            dyn = true; val c = th.catchLit; for (j in 0..2) { wLit[j] = c[j]; wShd[j] = c[j] }
                        }
                        LauraData.MOUTH -> {
                            val ch = mp[it.r][it.c]
                            target = if (ch != '.') 1.0 else 0.0; x = (it.c - 3) * d.ms + eox * 0.3; y = d.mouthY - (it.r + 0.5) * d.ms; sz2 = 0.12
                            if (ch == 'G' || ch == 'D') y -= 0.5 * d.ms
                            if (ch == 'U') y += 0.5 * d.ms
                            z = onFace(max(d.mouthZ0, faceZ(x, y)), 0.04) - d.ms * 0.06 - (if (ch == 'l') 0.004 else if (ch == 'r' || ch == 'g' || ch == 'G' || ch == 'W') 0.002 else 0.0)
                            sx2 = if (ch == 'W') 3.18 else 1.18; sy2 = if (ch == 'G' || ch == 'U' || ch == 'D') 2.18 else 1.18; dyn = true
                            val c = when (ch) { 'l' -> th.c("mouthGlow"); 'r' -> th.c("mouthRose"); 'g', 'G', 'W' -> th.c("mouthRing"); 'f' -> th.c("mouthOff"); 'n' -> th.mouthFlat
                                else -> if (lit) th.c("mouthLit") else th.c("mouthSoft") }
                            for (j in 0..2) { wLit[j] = c[j]; wShd[j] = c[j] }
                            if (ch == 'l') { fx1 = 1.0; x3 = -1.0 } else { fx1 = 0.0; x3 = 0.0 }
                        }
                        LauraData.BLUSH -> {
                            x = it.x; y = it.y
                            val b0 = clamp(p[kBlushBase] + 0.65 * p[kBlush], 0.0, 1.0); val b = b0 * it.bk
                            dyn = true
                            if (it.spark) { val c = th.c("sparkle"); for (j in 0..2) { wLit[j] = c[j]; wShd[j] = c[j] } }
                            else { val sl = th.c("skinLit"); val ss = th.c("skinShd"); val bc = th.c("blush"); for (j in 0..2) { wLit[j] = sl[j] + (bc[j] - sl[j]) * b; wShd[j] = ss[j] + (bc[j] - ss[j]) * b * 0.85 } }
                            z = onFace(it.fz, 0.012) - it.size * it.flat * 0.5; sx2 = it.sx; sy2 = it.sy; sz2 = it.flat; target = (1 - p[kOffline]) * (if (b0 > 0.02) 1.0 else 0.0)
                            if (it.spark) { target *= if (p[kSparkle] > 0.5) 1.0 else 0.0; z = onFace(it.fz, 0.02) - it.size * it.flat * 0.5 }
                        }
                    }
                    bodyXf(x, y, z, vP); x = vP.x; y = vP.y; z = vP.z
                }
            }
            when (it.k) {
                LauraData.MOUTH -> vis[n] = target
                LauraData.EYE, LauraData.CATCH -> visSpring(n, target, dt)
                else -> vis[n] += (target - vis[n]) * damp(18.0, dt)
            }
            var sc = s * clamp(vis[n], 0.0, 1.3)
            if (it.k == LauraData.EYE || it.k == LauraData.CATCH) sc = if (vis[n] < 0.5) 0.0 else s * clamp(vis[n], 1.0, 1.15)
            else if (it.k == LauraData.MOUTH) sc = if (vis[n] < 0.5) 0.0 else s
            if (sc < 0.004) continue
            val o = kk * STRIDE
            Q.compose(x, y, z, q, sc * sx2, sc * sy2, sc * sz2, out, o)
            val i3 = it.i * 3; val i4 = it.i * 4
            for (j in 0..2) {
                out[o + 16 + j] = if (dyn) wLit[j].toFloat() else th.lit[i3 + j]
                out[o + 19 + j] = if (dyn) wShd[j].toFloat() else th.shd[i3 + j]
                out[o + 34 + j] = d.n[i3 + j]
            }
            for (j in 0..3) { out[o + 22 + j] = th.fx[i4 + j]; out[o + 26 + j] = th.x[i4 + j]; out[o + 30 + j] = th.y[i4 + j] }
            out[o + 23] = fx1.toFloat(); out[o + 29] = x3.toFloat()
            kk++
        }
        count = kk
        // inner light body, uniforms, halo and floor
        bodyXf(0.0, d.innerY, 0.0, vP)
        inner[0] = vP.x.toFloat(); inner[1] = vP.y.toFloat(); inner[2] = vP.z.toFloat(); for (j in 0..3) inner[3 + j] = bq[j].toFloat()
        inner[7] = (d.innerR[0] * xfSxz * S).toFloat(); inner[8] = (d.innerR[1] * xfSy * S).toFloat(); inner[9] = (d.innerR[2] * xfSxz * S).toFloat()
        val li = clamp(p[kLight] + p[kGlow], 0.0, 1.2) * glow * p[kDim] * wakeLight
        for (j in 0..2) uLight[j] = tint[j].toFloat(); uLightI = li.toFloat()
        val hk = clamp(p[kHairK] + 0.4 * p[kGlow], 0.0, 1.0) * p[kDim] * wakeLight
        uHairK = hk.toFloat(); uHairOn = clamp(hk * 4, 0.0, 1.0).toFloat()
        uPartK = (0.62 + 1.25 * li).toFloat()
        uWake = if (waking && !rm) (-1.3 + 2.6 * smooth(0.08, 0.42, wu)).toFloat() else 9f
        uDesat = p[kDesat].toFloat(); uAccFloor = (0.45 * p[kAccent]).toFloat(); uSkinK = (1 - 0.52 * clamp(p[kOffline], 0.0, 1.0)).toFloat()
        uScan = (p[kThink] * glow).toFloat(); uScanY = (-4.5 + ((t * 0.5) % 1) * 10).toFloat()
        val rc = th.c("rimCol")
        if (th.name == "clear") { val kq = if (small) 0.45 else 0.3; for (j in 0..2) uRimCol[j] = (rc[j] + (tint[j] - rc[j]) * kq).toFloat() }
        else for (j in 0..2) uRimCol[j] = rc[j].toFloat()
        if (rm || small || state != "idle" || deterministic) { if (t > glintNext - 2) glintNext = t + 2 }
        else if (t > glintNext) { glintT0 = t; glintNext = t + 9 + 5 * rnd.nextDouble() }
        val gu = (t - glintT0) / 0.8; uGlint = if (gu >= 0 && gu < 1) (-1.5 + 3.0 * gu).toFloat() else 9f
        for (j in 0..2) haloColor[j] = tint[j].toFloat()
        haloAlpha = (th.n("haloA") * p[kHalo] * clamp(li * 1.6, 0.0, 1.0) * (1 - p[kOffline] * 0.8) * haloScale).toFloat()
        haloY = (0.3 + xfBodyY * 0.8).toFloat()
        val floorAdd = th.n("floorAdd") > 0
        val fc = if (floorAdd) tint else d.uni.getValue("floorLight").let { f -> DoubleArray(3) { f[it].toDouble() } }
        for (j in 0..2) floorColor[j] = fc[j].toFloat()
        floorScaleX = (1 - 0.08 * (xfBodyY + 1)).toFloat()
        floorAlpha = (th.n("floorA") * clamp(1 - xfBodyY * 0.15, 0.4, 1.4) * (if (floorAdd) clamp(li * 2, 0.2, 1.0) else 1.0)).toFloat()
    }
    private val lashPc = doubleArrayOf(-0.4, -0.4); private val lashPr = doubleArrayOf(0.45, 0.45)

    /** Rig space -> head grid (bodyXf inverse, in voxels), for the shader's see-through rule. */
    private fun headMatrix() {
        val kq = 1 / (xfS * d.vs)
        // H = T(0, (PIV/sy - PIV)/VS, 0) * S(k/sxz, k/sy, k/sxz) * R(bq^-1) * T(0, -bodyY, -z)
        Q.inv(bq, q2)
        val r = FloatArray(16); Q.compose(0.0, 0.0, 0.0, q2, 1.0, 1.0, 1.0, r, 0)
        val sx = kq / xfSxz; val sy = kq / xfSy; val sz = kq / xfSxz
        for (c in 0..2) { uHead[c * 4] = (r[c * 4] * sx).toFloat(); uHead[c * 4 + 1] = (r[c * 4 + 1] * sy).toFloat(); uHead[c * 4 + 2] = (r[c * 4 + 2] * sz).toFloat(); uHead[c * 4 + 3] = 0f }
        // translation column: S * R * (0, -bodyY, -z) + (0, ty, 0)
        val tx = r[4] * -xfBodyY + r[8] * -xfZ; val ty = r[5] * -xfBodyY + r[9] * -xfZ; val tz = r[6] * -xfBodyY + r[10] * -xfZ
        uHead[12] = (tx * sx).toFloat(); uHead[13] = (ty * sy + (d.piv / xfSy - d.piv) / d.vs).toFloat(); uHead[14] = (tz * sz).toFloat(); uHead[15] = 1f
    }

    // ---- render-comparison harness: a fresh start stepped at exactly 1/60 s, as export.sh's cases ----
    internal fun startCase(st: String, emo: String = "none", carMode: Boolean = false) {
        deterministic = true; wakeOnStart = false; car = carMode; emotion = emo
        enterState(st); baseState = st
    }

    /** One fixed 1/60 s step with a reaction fired just before it (render comparisons). */
    internal fun stepCase(reaction: String? = null) { if (reaction != null) addReaction(reaction); stepD(1.0 / 60) }

    companion object {
        const val STRIDE = 37
        /** Watch LOD: body cubes that never cover more than this many pixels at 480 px (in any frame of any state or reaction) are left out. */
        const val WATCH_MIN_PX = 40
        private val SIN15 = sin(0.26); private val COS15 = cos(0.26)
        private fun hash(n: Double): Double { val s = sin(n * 12.9898 + 78.233) * 43758.5453; return s - floor(s) }
        val REACTIONS: Set<String> get() = LauraData.shared.reactions.keys
    }
}
