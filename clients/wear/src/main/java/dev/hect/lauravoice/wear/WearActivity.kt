package dev.hect.lauravoice.wear

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.hect.lauravoice.avatar.AvatarGlView
import dev.hect.lauravoice.avatar.AvatarModel
import dev.hect.lauravoice.core.Conn
import dev.hect.lauravoice.core.UiState
import dev.hect.lauravoice.core.View
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The watch is only the avatar: a native GLES render of the phone's WebView avatar (same model data and shader), on black.
 * Opening it takes the conversation over; hold anywhere to talk in push-to-talk mode, tap to stop a reply.
 */
class WearActivity : ComponentActivity() {
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.RECORD_AUDIO] == true && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) WearApp.engine.activate()
    }
    private lateinit var avatar: AvatarGlView
    // the reduced watch model (only cubes that ever show at 480 px), the phone's look and approved glow level
    private val model = AvatarModel(watch = true)

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Side buttons/bezel control the stream the replies use.
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        avatar = AvatarGlView(this, model, fps = 30)
        setContentView(avatar)
        val engine = WearApp.engine
        var holding = false
        var downAt = 0L
        avatar.setOnTouchListener { _, e ->
            val ui = engine.ui.value
            val ptt = WearApp.store.load().mode == "ptt"
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = e.eventTime
                    if (ptt && ui.active && ui.conn == Conn.ONLINE) { holding = true; engine.pttDown() }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (holding) { holding = false; engine.pttUp() }
                    if (e.actionMasked == MotionEvent.ACTION_UP && e.eventTime - downAt < 300) {
                        if (ui.conn == Conn.ELSEWHERE || ui.conn == Conn.OFFLINE) engine.reconnect() else engine.stopReply()
                        model.poke()
                    }
                }
            }
            true
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch { engine.ui.map { avatarState(it) }.distinctUntilChanged().collect { model.setBase(it) } }
                launch {
                    engine.avatarEvents.collect { e ->
                        when (e.kind) {
                            "state" -> model.setState(e.value)
                            "reaction" -> model.triggerReaction(e.value)
                            "emotion" -> model.setEmotion(e.value)
                        }
                    }
                }
                while (true) {
                    val ui = engine.ui.value
                    model.micInput = if (ui.muted) 0f else ui.level
                    model.ttsInput = engine.ttsLevel()
                    model.car = WearApp.store.load().car
                    delay(33)
                }
            }
        }
    }

    // The watch only listens while the app is on screen and in the foreground: leaving it, screen off, ambient
    // or wrist-down all pause the activity, which releases the mic and hands the conversation back to the phone.
    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        avatar.resume()
        WearApp.syncConfig()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            WearApp.engine.activate()
        else permission.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
    }

    override fun onPause() {
        WearApp.engine.deactivate("watch app paused")
        avatar.pause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }
}

private fun avatarState(ui: UiState): String = when {
    ui.conn == Conn.ELSEWHERE -> "sleeping"
    ui.conn == Conn.BAD_KEY || ui.conn == Conn.CERT || ui.conn == Conn.NOT_CONFIGURED -> "error"
    !ui.active || ui.conn != Conn.ONLINE -> "offline"
    ui.view == View.SPEAKING -> "speaking"
    ui.view == View.THINKING -> "thinking"
    ui.muted -> "sleeping"
    ui.holding || ui.partial.isNotEmpty() -> "listening"
    else -> "idle"
}
