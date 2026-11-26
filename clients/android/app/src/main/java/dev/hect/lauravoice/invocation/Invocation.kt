package dev.hect.lauravoice.invocation

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.hect.lauravoice.App
import dev.hect.lauravoice.VoiceService
import dev.hect.lauravoice.core.View
import dev.hect.lauravoice.core.VoiceServiceBase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference

/**
 * Laura called up from outside the app: side key (as the digital assistant, or double press → her shortcut), the
 * assist gesture, the lock screen, the "Talk to Laura" shortcut. [InvokeActivity] is the one entry point, because
 * a visible activity is what Android 14+ requires before a microphone service may start.
 *
 * While [presenting], the floating overlay (or the activity's own panel on the lock screen) shows her. She goes
 * away once the turn is over and things are quiet, or when flung away. If the invocation is what turned her on,
 * the microphone service stops with her, so nothing keeps listening afterwards.
 *
 * Talk on the home screen widget is the exception: the widget already shows her state and reply, so while a turn
 * started there is presenting ([byWidget]), no floating head is shown. Android gives no permission-free way to know
 * whether the widget is still on screen (which app is in front needs usage access, which Laura doesn't hold), so the
 * source of the turn decides: widget turns never show the overlay, side key / assist / lock screen turns always do.
 * Opening Laura's own app still ends the presentation as usual.
 */
object Invocation {
    const val ACTION = "dev.hect.lauravoice.INVOKE"
    const val EXTRA_SOURCE = "source"
    const val SOURCE_WIDGET = "widget"
    private const val TAG = "LauraInvoke"
    /** Nothing said or heard for this long after an invocation: she leaves. */
    private const val LISTEN_MS = 12_000L
    /** After a turn, quiet this long: she leaves. */
    private const val IDLE_MS = 5_000L

    private val _presenting = MutableStateFlow(false)
    val presenting: StateFlow<Boolean> = _presenting
    private val _leaving = MutableStateFlow(false)
    /** She is going away and the service with her: the floating bubble shouldn't linger meanwhile. */
    val leaving: StateFlow<Boolean> = _leaving
    private val _summons = MutableStateFlow(0L)
    /** Time (elapsedRealtime) of the latest invocation, for the entrance animation. */
    val summons: StateFlow<Long> = _summons
    private val _byWidget = MutableStateFlow(false)
    /** The turn being presented was started from the home screen widget, which shows her itself. */
    val byWidget: StateFlow<Boolean> = _byWidget

    /** The service was started by an invocation: stop it (and release the mic) when she leaves. */
    @Volatile private var ephemeral = false
    /** [InvokeActivity] instances on screen (they count as "the app is visible" for [App.visible]). */
    @Volatile var activities = 0
        internal set
    private var session: WeakReference<LauraSession>? = null
    private val main = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private var turnSeen = false
    private var lastBusy = 0L

    fun intent(ctx: Context, source: String): Intent =
        Intent(ctx, InvokeActivity::class.java).setAction(ACTION).putExtra(EXTRA_SOURCE, source)

    private const val PREF_ASSIST_AT = "assist_invoked_at"

    /** She was called through the assistant path (side key hold, assist gesture, lock screen shortcut). */
    fun markAssist(ctx: Context) = ctx.getSharedPreferences("laura", Context.MODE_PRIVATE).edit()
        .putLong(PREF_ASSIST_AT, System.currentTimeMillis()).apply()

    /**
     * Side key status: true = she's the assistant and the side key hold opens the assistant, false = she's the
     * assistant but that isn't confirmed yet, null = she isn't the assistant (which also forgets the last call).
     * One UI 7 keeps "Press and hold" in Settings.Global function_key_config_longpress_selected_item
     * (long_press_ai_agent_app = Digital assistant app); when that can't be read, a past assist call stands in.
     */
    fun sideKeyWorking(ctx: Context, assistant: Boolean): Boolean? {
        val p = ctx.getSharedPreferences("laura", Context.MODE_PRIVATE)
        if (!assistant) {
            p.edit().remove(PREF_ASSIST_AT).apply()
            return null
        }
        val longPress = runCatching {
            android.provider.Settings.Global.getString(ctx.contentResolver, "function_key_config_longpress_selected_item")
        }.getOrNull()
        if (longPress != null) return longPress == "long_press_ai_agent_app"
        return p.getLong(PREF_ASSIST_AT, 0L) > 0L
    }

    /** "Try it" in settings and anything else inside the app. */
    fun launch(ctx: Context, source: String) =
        ctx.startActivity(intent(ctx, source).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    /** Called by [InvokeActivity] while it is in the foreground. */
    internal fun summon(ctx: Context, source: String) {
        appCtx = ctx.applicationContext
        val engine = App.engine
        Log.i(TAG, "invoked ($source), on=${engine.active}")
        _leaving.value = false
        if (!engine.active) {
            ephemeral = true
            VoiceServiceBase.start(ctx, VoiceService::class.java)
        }
        engine.summon()
        val now = SystemClock.elapsedRealtime()
        _summons.value = now
        _byWidget.value = source == SOURCE_WIDGET
        _presenting.value = true
        turnSeen = false
        lastBusy = now
        main.removeCallbacks(tick)
        main.postDelayed(tick, 500)
    }

    fun dismiss(reason: String) {
        main.removeCallbacks(tick)
        endSession()
        if (!_presenting.value) return
        Log.i(TAG, "leaving: $reason (ephemeral=$ephemeral)")
        if (ephemeral) _leaving.value = true
        _presenting.value = false
        _byWidget.value = false
        if (ephemeral) main.postDelayed({
            val ctx = appCtx
            if (!_presenting.value && ephemeral && ctx != null) {
                ephemeral = false
                VoiceServiceBase.stop(ctx, VoiceService::class.java, "invocation done")
            }
            clearLeaving()
        }, 300)
    }

    /** Once she's really off, the floating bubble setting applies again. */
    private fun clearLeaving() {
        if (App.engine.active && !_presenting.value) main.postDelayed(::clearLeaving, 100) else _leaving.value = false
    }

    /** Laura was turned off some other way (bubble ×, notification): nothing left to stop. */
    fun forget() {
        ephemeral = false
        main.removeCallbacks(tick)
        _presenting.value = false
        _byWidget.value = false
        endSession()
    }

    private val tick = object : Runnable {
        override fun run() {
            val ui = App.engine.ui.value
            val now = SystemClock.elapsedRealtime()
            if (!ui.active && now - _summons.value > 2000) return forget()
            if (App.visible.value && activities == 0) {
                // the user opened the app: she stays on as if turned on there
                ephemeral = false
                return dismiss("app opened")
            }
            val busy = ui.view == View.THINKING || ui.view == View.SPEAKING || ui.partial.isNotEmpty()
            if (busy) turnSeen = true
            if (busy || ui.holding) lastBusy = now
            when {
                turnSeen && now - lastBusy > IDLE_MS -> return dismiss("turn over")
                !turnSeen && now - lastBusy > LISTEN_MS -> return dismiss("nothing said")
            }
            main.postDelayed(this, 500)
        }
    }

    internal fun attach(s: LauraSession) {
        session = WeakReference(s)
    }

    internal fun detach(s: LauraSession) {
        if (session?.get() === s) session = null
    }

    /** The assistant session only exists to start [InvokeActivity]; close it once that is done. */
    internal fun endSession() {
        session?.get()?.let { runCatching { it.hide() } }
        session = null
    }
}
