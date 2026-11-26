package dev.hect.lauravoice.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import dev.hect.lauravoice.App
import dev.hect.lauravoice.core.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Pushes Laura's state to the home screen widgets. Nothing polls: the engine's state is reduced to what a widget
 * shows ([WidgetModel], no mic level or stats), and only a change of that is published, at most every
 * [STATE_MS] for a state change and every [REPLY_MS] while only the reply text grows; with the screen off, changes are
 * coalesced to one every [SCREEN_OFF_MS] (the last one always lands). With no widget placed, no widget update is sent.
 */
object WidgetSync {
    private const val TAG = "LauraWidget"
    const val STATE_MS = 300L
    const val REPLY_MS = 1500L
    const val SCREEN_OFF_MS = 5000L

    private val _model = MutableStateFlow(WidgetModel())
    /** What the widgets show right now (Glance sessions collect it). */
    val model: StateFlow<WidgetModel> = _model
    private val _configs = MutableStateFlow(0)
    /** Bumped when a widget's settings change. */
    val configs: StateFlow<Int> = _configs

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var ctx: Context
    private var stored: Reply? = null
    private var turnKey: String? = null
    private var turnAt = 0L
    private var lastAt = 0L
    private var demo: WidgetModel? = null
    private const val DEMO = "dev.hect.lauravoice.WIDGET_DEMO"

    /** The one hook: called from [App.onCreate]. */
    fun start(app: Context) {
        ctx = app.applicationContext
        stored = WidgetPrefs.loadReply(ctx)
        _model.value = compute(App.engine.ui.value)
        scope.launch {
            App.engine.ui.map(::key).distinctUntilChanged().map { compute(App.engine.ui.value) }.distinctUntilChanged().conflate()
                .collect { if (demo == null) publish(it) }
        }
        if (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) ContextCompat.registerReceiver(ctx, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = demo(i)
        }, IntentFilter(DEMO), ContextCompat.RECEIVER_EXPORTED)
        // a widget may show a state from before the process died: correct it once
        if (widgetIds().isNotEmpty()) scope.launch { updateWidgets() }
    }

    /** Only the parts of [UiState] that can change what a widget shows. */
    private fun key(ui: UiState) = listOf(ui.active, ui.conn, ui.view, ui.muted, ui.holding, ui.awake, ui.partial.isNotEmpty(),
        ui.onWatch, ui.speakingSource, ui.lines.lastOrNull { it.role == "assistant" }?.let { it.turnId to it.text })

    private fun compute(ui: UiState): WidgetModel {
        val cfg = App.store.load()
        val reply = WidgetModels.lastReply(ui) { k ->
            if (k != turnKey) { turnKey = k; turnAt = System.currentTimeMillis() }
            turnAt
        } ?: stored
        return WidgetModels.from(ui, cfg.configured, cfg.mode == "ptt", reply)
    }

    private suspend fun publish(m: WidgetModel) {
        val prev = _model.value
        val gap = when {
            // nobody sees it, but the last state must still land (a cached app may not hear SCREEN_ON in time)
            !ctx.getSystemService(PowerManager::class.java).isInteractive -> SCREEN_OFF_MS
            m.state != prev.state || m.meta != prev.meta -> STATE_MS
            else -> REPLY_MS
        }
        val wait = lastAt + gap - SystemClock.elapsedRealtime()
        if (wait > 0) delay(wait)
        lastAt = SystemClock.elapsedRealtime()
        _model.value = m
        val ids = widgetIds()
        if (m.reply != null && m.reply != stored && m.state != WState.SPEAKING && m.state != WState.THINKING) {
            stored = m.reply
            WidgetPrefs.saveReply(ctx, m.reply, WidgetPrefs.anyShowsText(ctx, ids))
        }
        if (ids.isNotEmpty()) updateWidgets()
    }

    /**
     * Debug builds only, for screenshots without a gateway:
     * `adb shell am broadcast -a dev.hect.lauravoice.WIDGET_DEMO --es state speaking --es meta "..." --es reply "..." --es source "..." --ez local false`;
     * `--es state live` goes back to the engine.
     */
    private fun demo(i: Intent) {
        val st = i.getStringExtra("state") ?: "live"
        if (st == "live") {
            demo = null
            scope.launch { publish(compute(App.engine.ui.value)) }
            return
        }
        val s = WState.entries.firstOrNull { it.name.equals(st, true) } ?: return
        val reply = i.getStringExtra("reply")?.let {
            Reply(WidgetModels.clip(it), i.getStringExtra("source") ?: "Laura", i.getBooleanExtra("local", false),
                System.currentTimeMillis() - i.getLongExtra("ago", 60_000L))
        } ?: stored
        val m = WidgetModel(s, i.getStringExtra("meta") ?: s.meta, reply)
        demo = m
        scope.launch { publish(m) }
    }

    /** A widget's settings changed (configure screen). */
    fun configChanged() {
        _configs.value++
        stored?.let { WidgetPrefs.saveReply(ctx, it, WidgetPrefs.anyShowsText(ctx, widgetIds())) }
        scope.launch { updateWidgets() }
    }

    private suspend fun updateWidgets() = runCatching {
        LauraWidget().updateAll(ctx)
        LauraTalkWidget().updateAll(ctx)
    }.onFailure { Log.w(TAG, "update failed", it) }

    fun widgetIds(): IntArray {
        val m = AppWidgetManager.getInstance(ctx)
        return m.getAppWidgetIds(ComponentName(ctx, LauraWidgetReceiver::class.java)) +
            m.getAppWidgetIds(ComponentName(ctx, LauraTalkWidgetReceiver::class.java))
    }
}
