package dev.hect.lauravoice.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.hect.lauravoice.App
import dev.hect.lauravoice.MainActivity
import dev.hect.lauravoice.VoiceService
import dev.hect.lauravoice.avatar.AvatarGlView
import dev.hect.lauravoice.avatar.AvatarModel
import dev.hect.lauravoice.avatarState
import dev.hect.lauravoice.core.Line
import dev.hect.lauravoice.core.RECALL_MS
import dev.hect.lauravoice.core.UiState
import dev.hect.lauravoice.core.View as EngineView
import dev.hect.lauravoice.core.VoiceServiceBase
import dev.hect.lauravoice.invocation.Invocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Laura as a floating bubble over other apps while she is on and the app isn't on screen. Owned by the
 * microphone service: created when it starts, removed when it stops. Never touches the mic itself.
 *
 * Tap: stop the reply while she speaks, otherwise open the app. Hold: push to talk.
 * Drag: moves and snaps to the nearest side; dropping on the bottom × turns her off.
 *
 * Invoked from outside the app (side key, assist gesture, shortcut) she animates in even when the floating bubble
 * is switched off, and goes away again after the turn (see [Invocation]); a fling sends her away early.
 */
class LauraOverlay(private val ctx: Context) {
    private val engine = App.engine
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = OverlayPrefs(ctx)
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).roundToInt()

    private val size = dp(SIZE_DP)
    private val margin = 0
    private val model = AvatarModel()
    private var avatar: AvatarGlView? = null
    private var bubble: FrameLayout? = null
    private var dot: View? = null
    private var info: LinearLayout? = null
    private var caption: TextView? = null
    private var chip: TextView? = null
    private var target: FrameLayout? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var infoLp: WindowManager.LayoutParams? = null

    private fun unlocked() = ctx.getSystemService(PowerManager::class.java).isInteractive &&
        !ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    private val screenOn = MutableStateFlow(unlocked())
    private val permTick = MutableStateFlow(0)
    private var shown = false
    private var rendering = false
    private var feedJob: Job? = null
    private var lastReply: Pair<String?, String>? = null
    private var captionUntil = 0L
    private var greeted = 0L
    private var surprisedAt = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            screenOn.value = i.action != Intent.ACTION_SCREEN_OFF && unlocked()
        }
    }
    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(c: Configuration) { main.post { if (shown) snap(animate = false) } }
        @Suppress("OVERRIDE_DEPRECATION") override fun onLowMemory() {}
    }

    fun start() {
        ctx.registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        })
        ctx.registerComponentCallbacks(configCallbacks)
        scope.launch {
            combine(engine.ui.map { it.active }.distinctUntilChanged(), App.visible, permTick, Invocation.presenting, Invocation.leaving) { active, visible, _, invoked, leaving ->
                active && !visible && ((prefs.enabled && !leaving) || invoked) && Settings.canDrawOverlays(ctx)
            }.distinctUntilChanged().collect { setShown(it, animate = true) }
        }
        scope.launch {
            Invocation.summons.collect { if (it > greeted && shown) greet(entering = false) }
        }
        scope.launch {
            combine(App.visible, screenOn) { v, s -> v to s }.collect { permTick.value++; updateRendering() }
        }
        scope.launch {
            engine.ui.collect { onUi(it) }
        }
        scope.launch {
            engine.avatarEvents.collect { e ->
                when (e.kind) {
                    "state" -> model.setState(e.value)
                    "reaction" -> {
                        if (e.value == "surprised") surprisedAt = SystemClock.elapsedRealtime()
                        model.triggerReaction(e.value)
                    }
                    "emotion" -> model.setEmotion(e.value)
                }
            }
        }
    }

    fun stop() {
        setShown(false, animate = false)
        scope.cancel()
        runCatching { ctx.unregisterReceiver(screenReceiver) }
        runCatching { ctx.unregisterComponentCallbacks(configCallbacks) }
    }

    // ---- windows ----

    private fun overlayLp(w: Int, h: Int, touchable: Boolean) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // untrusted overlays that let touches through must stay at or below 0.8 opacity to not block them
        if (!touchable) alpha = 0.8f
        if (Build.VERSION.SDK_INT >= 31) setFitInsetsTypes(0)
    }

    private fun setShown(on: Boolean, animate: Boolean) {
        if (on == shown) return
        shown = on
        Log.i(TAG, "overlay ${if (on) "shown" else "hidden"}")
        if (on) {
            lastReply = lastAssistant(engine.ui.value)?.let { it.turnId to it.text }
            buildViews()
            val lp = overlayLp(size, size, true)
            val (w, h) = screen()
            lp.x = if (prefs.right) w - size - margin else margin
            lp.y = (prefs.yFrac * h).roundToInt()
            bubbleLp = lp
            clampY(lp)
            runCatching { wm.addView(bubble, lp) }.onFailure { Log.w(TAG, "add bubble", it); shown = false; return }
            onUi(engine.ui.value)
            if (Invocation.presenting.value && Invocation.summons.value > greeted) greet(entering = true)
        } else {
            hideTarget()
            removeInfo()
            val gl = avatar
            bubble?.let { b ->
                if (animate && gl != null) {
                    // animate the GL view itself: a SurfaceView doesn't follow its parent's alpha
                    gl.animate().cancel()
                    gl.animate().scaleX(0.4f).scaleY(0.4f).alpha(0f).setDuration(OUT_MS).setInterpolator(AccelerateInterpolator())
                        .withEndAction { runCatching { wm.removeViewImmediate(b) } }.start()
                } else {
                    runCatching { wm.removeViewImmediate(b) }
                }
            }
            bubble = null
            avatar = null
            dot = null
            info = null
        }
        updateRendering()
    }

    /** Invocation: pop in (or a little hop when she was already floating) with the wake reaction. */
    private fun greet(entering: Boolean) {
        val b = avatar ?: return
        greeted = Invocation.summons.value
        b.animate().cancel()
        if (entering) {
            b.scaleX = 0.4f; b.scaleY = 0.4f; b.alpha = 0f
            b.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(IN_MS).setInterpolator(OvershootInterpolator(1.6f)).start()
        } else {
            b.animate().scaleX(1.15f).scaleY(1.15f).setDuration(120).withEndAction {
                b.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(OvershootInterpolator()).start()
            }.start()
        }
        if (SystemClock.elapsedRealtime() - surprisedAt > 1000 && !model.car) model.triggerReaction("surprised")
    }

    private fun updateRendering() {
        val want = shown && screenOn.value
        if (want == rendering) return
        rendering = want
        val a = avatar
        if (want) {
            a?.resume()
            feedJob = scope.launch {
                while (isActive) {
                    val ui = engine.ui.value
                    model.micInput = if (ui.muted) 0f else ui.level
                    model.ttsInput = engine.ttsLevel()
                    delay(33)
                }
            }
        } else {
            a?.pause()
            feedJob?.cancel()
            feedJob = null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildViews() {
        model.car = App.store.load().car
        val gl = AvatarGlView(ctx, model, fps = 30, transparent = true, zoom = ZOOM)
        avatar = gl
        val d = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.GRAY)
            }
            visibility = View.GONE
        }
        dot = d
        val root = object : FrameLayout(ctx) {
            override fun onInterceptTouchEvent(ev: MotionEvent) = true
        }
        root.addView(gl, FrameLayout.LayoutParams(size, size))
        root.addView(d, FrameLayout.LayoutParams(dp(8f), dp(8f), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(14f), dp(14f))
        })
        root.setOnTouchListener(Touch())
        bubble = root
    }

    private fun screen(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            return b.width() to b.height()
        }
        val m = ctx.resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    private fun clampY(lp: WindowManager.LayoutParams) {
        val (_, h) = screen()
        lp.y = lp.y.coerceIn(dp(56f), h - size - dp(120f))
    }

    private fun snap(animate: Boolean) {
        val b = bubble ?: return
        val lp = bubbleLp ?: return
        val (w, h) = screen()
        val right = lp.x + size / 2 > w / 2
        val tx = if (right) w - size - margin else margin
        clampY(lp)
        prefs.right = right
        prefs.yFrac = lp.y.toFloat() / h
        if (!animate) {
            lp.x = tx
            runCatching { wm.updateViewLayout(b, lp) }
            placeInfo()
            return
        }
        ValueAnimator.ofInt(lp.x, tx).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                if (bubble !== b) return@addUpdateListener
                lp.x = it.animatedValue as Int
                runCatching { wm.updateViewLayout(b, lp) }
            }
            start()
        }
        main.postDelayed({ placeInfo() }, 240)
    }

    // ---- drag-to-dismiss target ----

    private fun showTarget() {
        if (target != null) return
        val t = FrameLayout(ctx)
        val circle = TextView(ctx).apply {
            text = "×"
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC2A2438.toInt()); setStroke(dp(2f), 0x88FFFFFF.toInt()) }
        }
        t.addView(circle, FrameLayout.LayoutParams(dp(64f), dp(64f), Gravity.CENTER))
        val lp = overlayLp(dp(96f), dp(96f), false).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(40f)
            alpha = 1f
        }
        runCatching { wm.addView(t, lp); target = t }
    }

    private fun hideTarget() {
        target?.let { runCatching { wm.removeViewImmediate(it) } }
        target = null
    }

    private fun overTarget(): Boolean {
        val lp = bubbleLp ?: return false
        val (w, h) = screen()
        val tx = w / 2f
        val ty = h - dp(40f) - dp(48f)
        return hypot(lp.x + size / 2f - tx, lp.y + size / 2f - ty) < dp(72f)
    }

    // ---- caption and recall chip ----

    private fun lastAssistant(ui: UiState): Line? = ui.lines.lastOrNull { it.role == "assistant" }

    private fun onUi(ui: UiState) {
        val d = dot
        if (d != null) {
            val src = EngineTag.local(ui)
            d.visibility = if (src == null) View.GONE else View.VISIBLE
            if (src != null) (d.background as GradientDrawable).setColor(if (src) 0xFF34D399.toInt() else 0xFFE8875B.toInt())
        }
        if (!shown) return
        model.setBase(avatarState(ui))
        val last = lastAssistant(ui)
        val key = last?.let { it.turnId to it.text }
        if (last != null && key != lastReply && last.text.isNotBlank()) {
            lastReply = key
            showCaption(tail(last.text))
        }
        updateInfo(ui)
    }

    private fun tail(text: String): String {
        val t = text.trim().replace(Regex("\\s+"), " ")
        if (t.length <= 140) return t
        val cut = t.substring(t.length - 140)
        val i = cut.indexOfFirst { it == ' ' }
        return "…" + cut.substring(i + 1)
    }

    private fun showCaption(text: String) {
        ensureInfo()
        caption?.apply {
            this.text = text
            visibility = View.VISIBLE
            animate().cancel()
            alpha = 1f
        }
        captionUntil = SystemClock.elapsedRealtime() + CAPTION_MS
        main.removeCallbacks(fadeCaption)
        main.postDelayed(fadeCaption, CAPTION_MS)
        placeInfo()
    }

    private val fadeCaption = Runnable {
        val c = caption ?: return@Runnable
        c.animate().alpha(0f).setDuration(400).withEndAction {
            c.visibility = View.GONE
            updateInfo(engine.ui.value)
        }.start()
    }

    private val chipExpire = Runnable { updateInfo(engine.ui.value) }

    private fun updateInfo(ui: UiState) {
        val ignored = ui.lastIgnored?.takeIf { it.recallable() }
        if (ignored != null) {
            ensureInfo()
            chip?.apply {
                text = "For Laura: “${ignored.text.take(40)}${if (ignored.text.length > 40) "…" else ""}”"
                visibility = View.VISIBLE
            }
            main.removeCallbacks(chipExpire)
            main.postDelayed(chipExpire, RECALL_MS - (SystemClock.elapsedRealtime() - ignored.time) + 100)
        } else {
            chip?.visibility = View.GONE
        }
        val captionOn = caption?.visibility == View.VISIBLE
        if (ignored == null && !captionOn) removeInfo() else placeInfo()
    }

    private fun ensureInfo() {
        if (info != null || !shown) return
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val cap = TextView(ctx).apply {
            setTextColor(0xFFF1EEF9.toInt())
            textSize = 14f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(250f)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            background = GradientDrawable().apply { cornerRadius = dp(14f).toFloat(); setColor(0xF01A1726.toInt()) }
            visibility = View.GONE
            setOnClickListener { openApp() }
        }
        val ch = TextView(ctx).apply {
            setTextColor(0xFFF1EEF9.toInt())
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(250f)
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            background = GradientDrawable().apply { cornerRadius = dp(16f).toFloat(); setColor(0xFF4C3F8F.toInt()); setStroke(dp(1f), 0xFF8B7CF6.toInt()) }
            visibility = View.GONE
            setOnClickListener {
                engine.recallLast()
                visibility = View.GONE
                updateInfo(engine.ui.value)
            }
        }
        col.addView(cap, LinearLayout.LayoutParams(-2, -2))
        col.addView(ch, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6f) })
        info = col
        caption = cap
        chip = ch
    }

    private fun placeInfo() {
        val v = info ?: return
        val b = bubbleLp ?: return
        val (w, _) = screen()
        val touchable = chip?.visibility == View.VISIBLE
        val right = b.x + size / 2 > w / 2
        val lp = infoLp?.takeIf { (it.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0) == touchable }
            ?: overlayLp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, touchable)
        lp.gravity = Gravity.TOP or (if (right) Gravity.END else Gravity.START)
        lp.x = if (right) w - b.x + dp(8f) else b.x + size + dp(8f)
        lp.y = b.y + dp(8f)
        for (i in 0 until v.childCount) {
            val c = v.getChildAt(i).layoutParams as LinearLayout.LayoutParams
            c.gravity = if (right) Gravity.END else Gravity.START
        }
        if (v.parent == null) {
            runCatching { wm.addView(v, lp) }
        } else if (lp !== infoLp) {
            runCatching { wm.removeViewImmediate(v); wm.addView(v, lp) }
        } else {
            runCatching { wm.updateViewLayout(v, lp) }
        }
        infoLp = lp
    }

    private fun removeInfo() {
        main.removeCallbacks(fadeCaption)
        main.removeCallbacks(chipExpire)
        info?.let { if (it.parent != null) runCatching { wm.removeViewImmediate(it) } }
        caption?.visibility = View.GONE
        infoLp = null
    }

    // ---- actions ----

    private fun openApp() {
        ctx.startActivity(
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
    }

    private fun onTap() {
        model.poke()
        val ui = engine.ui.value
        if (ui.view == EngineView.SPEAKING) engine.stopReply() else openApp()
    }

    private fun turnOff() {
        Log.i(TAG, "turned off from the bubble")
        Invocation.forget()
        setShown(false, animate = false)
        VoiceServiceBase.stop(ctx, VoiceService::class.java, "overlay")
    }

    private inner class Touch : View.OnTouchListener {
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var downX = 0f; private var downY = 0f
        private var startX = 0; private var startY = 0
        private var dragging = false
        private var holding = false
        private var magnet = false
        private var tracking = false
        private val trail = ArrayDeque<FloatArray>()
        private val longPress = Runnable {
            if (dragging) return@Runnable
            holding = true
            bubble?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            bubble?.animate()?.scaleX(1.12f)?.scaleY(1.12f)?.setDuration(120)?.start()
            engine.pttDown()
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val lp = bubbleLp ?: return false
            if (!tracking && e.actionMasked != MotionEvent.ACTION_DOWN) return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // only her body (an upright ellipse) is a target; the transparent corners do nothing
                    val nx = (e.x / size - 0.5f) / 0.36f; val ny = (e.y / size - 0.5f) / 0.46f
                    if (nx * nx + ny * ny > 1f) { tracking = false; return true }
                    tracking = true
                    trail.clear()
                    track(e)
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    dragging = false; holding = false; magnet = false
                    main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    track(e)
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && !holding && hypot(dx, dy) > slop) {
                        dragging = true
                        main.removeCallbacks(longPress)
                        removeInfo()
                        showTarget()
                    }
                    if (dragging) {
                        lp.x = (startX + dx).roundToInt()
                        lp.y = (startY + dy).roundToInt()
                        val over = overTarget()
                        if (over != magnet) {
                            magnet = over
                            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            target?.animate()?.scaleX(if (over) 1.25f else 1f)?.scaleY(if (over) 1.25f else 1f)?.setDuration(120)?.start()
                        }
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    when {
                        holding -> {
                            holding = false
                            v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                            engine.pttUp()
                        }
                        dragging -> {
                            hideTarget()
                            when {
                                magnet && e.actionMasked == MotionEvent.ACTION_UP -> turnOff()
                                e.actionMasked == MotionEvent.ACTION_UP && Invocation.presenting.value && flung(e) -> Invocation.dismiss("swipe")
                                else -> snap(animate = true)
                            }
                        }
                        e.actionMasked == MotionEvent.ACTION_UP -> onTap()
                    }
                }
            }
            return true
        }

        /** Screen positions of the last moments of a drag (the window moves under the finger). */
        private fun track(e: MotionEvent) {
            val t = (e.eventTime - e.downTime).toFloat()
            trail.addLast(floatArrayOf(t, e.rawX, e.rawY))
            while (trail.size > 2 && t - trail.first()[0] > 150) trail.removeFirst()
        }

        private fun flung(e: MotionEvent): Boolean {
            track(e)
            val a = trail.first()
            val dt = ((e.eventTime - e.downTime) - a[0]).coerceAtLeast(16f)
            val v = hypot(e.rawX - a[1], e.rawY - a[2]) / dt * 1000
            return v > FLING_DP_S * density
        }
    }

    companion object {
        private const val TAG = "LauraOverlay"
        const val SIZE_DP = 104f
        const val ZOOM = 1f
        const val CAPTION_MS = 5000L
        const val IN_MS = 320L
        const val OUT_MS = 220L
        const val FLING_DP_S = 1400f

        fun permitted(ctx: Context) = Settings.canDrawOverlays(ctx)
    }
}

class OverlayPrefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("overlay", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = p.getBoolean("enabled", true)
        set(v) = p.edit().putBoolean("enabled", v).apply()
    var right: Boolean
        get() = p.getBoolean("right", false)
        set(v) = p.edit().putBoolean("right", v).apply()
    var yFrac: Float
        get() = p.getFloat("y", 0.3f)
        set(v) = p.edit().putFloat("y", v).apply()
}

/** Who is answering (wake agent's `speakingSource`): true = local model, false = Claude via Hermes, null = unknown. */
internal object EngineTag {
    fun local(ui: UiState): Boolean? = ui.speakingSource?.local
}
