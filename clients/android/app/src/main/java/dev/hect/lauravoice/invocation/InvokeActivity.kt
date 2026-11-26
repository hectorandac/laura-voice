package dev.hect.lauravoice.invocation

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.hect.lauravoice.App
import dev.hect.lauravoice.MainActivity
import dev.hect.lauravoice.avatar.AvatarGlView
import dev.hect.lauravoice.avatar.AvatarModel
import dev.hect.lauravoice.avatarState
import dev.hect.lauravoice.core.View as EngineView
import dev.hect.lauravoice.overlay.LauraOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Transparent entry point for every invocation (assistant session, ASSIST intent, shortcut, side key double
 * press). Being a visible activity is what lets the microphone service start from anywhere.
 *
 * Unlocked with "Display over other apps": starts her and gets out of the way at once; the floating overlay
 * animates in over whatever app is open. Locked (overlays don't show over the keyguard) or without the overlay
 * permission: shows her itself as a small panel at the bottom, over the lock screen too, until she leaves.
 * From the home screen widget (unlocked) neither shows: the widget is her face for that turn.
 */
class InvokeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val density get() = resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).roundToInt()

    private var panel: View? = null
    private var avatar: AvatarGlView? = null
    private var caption: TextView? = null
    private val model = AvatarModel()
    private var feed: Job? = null
    private var leaving = false
    private var unlockReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        if (savedInstanceState != null) return finishQuietly()
        start(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        start(intent)
    }

    private fun start(i: Intent?) {
        val source = i?.getStringExtra(Invocation.EXTRA_SOURCE) ?: i?.action ?: "unknown"
        if (i?.action == Intent.ACTION_ASSIST) Invocation.markAssist(this)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return needsSetup("Laura needs the microphone. Allow it in Laura's settings.")
        }
        if (!App.store.load().configured) return needsSetup("Set up Laura's server first.")
        Invocation.summon(this, source)
        if (needsPanel(source)) {
            if (panel == null) showPanel() else greet()
        } else if (panel == null) {
            // the overlay takes it from here; stay a moment so the service starts while we are in front
            window.decorView.postDelayed({ finishQuietly() }, HANDOFF_MS)
        }
    }

    private fun needsPanel(source: String): Boolean = getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
        !getSystemService(PowerManager::class.java).isInteractive ||
        (!LauraOverlay.permitted(this) && source != Invocation.SOURCE_WIDGET)

    private fun needsSetup(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        val open = {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finishQuietly()
        }
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = open()
                override fun onDismissCancelled() = finishQuietly()
                override fun onDismissError() = finishQuietly()
            })
        } else {
            open()
        }
    }

    override fun onStart() {
        super.onStart()
        Invocation.activities++
        avatar?.resume()
    }

    override fun onResume() {
        super.onResume()
        Invocation.endSession()
    }

    override fun onStop() {
        Invocation.activities--
        avatar?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        unlockReceiver?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        Invocation.dismiss("back")
        leave()
    }

    private fun finishQuietly() {
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    // ---- panel (lock screen, or no overlay permission) ----

    private fun showPanel() {
        val size = dp(PANEL_DP)
        model.car = App.store.load().car
        val gl = AvatarGlView(this, model, fps = 30, transparent = true, zoom = LauraOverlay.ZOOM)
        avatar = gl
        val cap = TextView(this).apply {
            setTextColor(0xFFF1EEF9.toInt())
            textSize = 15f
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(16f).toFloat(); setColor(0xF01A1726.toInt())
            }
            visibility = View.GONE
        }
        caption = cap
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(cap, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(8f); marginStart = dp(24f); marginEnd = dp(24f) })
            addView(gl, LinearLayout.LayoutParams(size, size))
        }
        val root = FrameLayout(this).apply { addView(col, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)) }
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                model.poke()
                if (App.engine.ui.value.view == EngineView.SPEAKING) App.engine.stopReply()
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (maxOf(abs(vx), abs(vy)) < FLING_DP_S * density) return false
                Invocation.dismiss("swipe")
                return true
            }
        })
        gl.setOnTouchListener { _, e -> gestures.onTouchEvent(e) }
        setContentView(root)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.BOTTOM)
        window.attributes = window.attributes.apply { y = dp(56f) }
        // touches beside her go to whatever is behind
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        panel = root

        val lastAtStart = lastReply()
        scope.launch {
            App.engine.ui.collect { ui ->
                model.setBase(avatarState(ui))
                val r = lastReply()
                if (r != null && r != lastAtStart && r.second.isNotBlank()) showCaption(r.second)
            }
        }
        scope.launch {
            App.engine.avatarEvents.collect { e ->
                when (e.kind) {
                    "state" -> model.setState(e.value)
                    "reaction" -> model.triggerReaction(e.value)
                    "emotion" -> model.setEmotion(e.value)
                }
            }
        }
        scope.launch {
            Invocation.presenting.collect { if (!it) leave() }
        }
        feed = scope.launch {
            while (isActive) {
                val ui = App.engine.ui.value
                model.micInput = if (ui.muted) 0f else ui.level
                model.ttsInput = App.engine.ttsLevel()
                delay(33)
            }
        }
        // unlocked while she's up: hand over to the floating overlay
        if (LauraOverlay.permitted(this)) {
            unlockReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    Log.i(TAG, "unlocked, overlay takes over")
                    finishQuietly()
                }
            }.also { registerReceiver(it, IntentFilter(Intent.ACTION_USER_PRESENT)) }
        }
        greet()
    }

    private fun lastReply() = App.engine.ui.value.lines.lastOrNull { it.role == "assistant" }?.let { it.turnId to it.text }

    private fun showCaption(text: String) {
        val c = caption ?: return
        val t = text.trim().replace(Regex("\\s+"), " ")
        c.text = if (t.length <= 200) t else "…" + t.takeLast(200).substringAfter(' ')
        c.visibility = View.VISIBLE
    }

    private fun greet() {
        val v = avatar ?: return
        v.scaleX = 0.4f; v.scaleY = 0.4f; v.alpha = 0f
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(320).setInterpolator(OvershootInterpolator(1.6f)).start()
        if (!model.car) model.triggerReaction("surprised")
    }

    private fun leave() {
        if (leaving) return
        leaving = true
        val v = panel
        if (v == null) return finishQuietly()
        v.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(220).setInterpolator(AccelerateInterpolator())
            .withEndAction { finishQuietly() }.start()
    }

    companion object {
        private const val TAG = "LauraInvoke"
        private const val HANDOFF_MS = 250L
        private const val PANEL_DP = 132f
        private const val FLING_DP_S = 1400f
    }
}
