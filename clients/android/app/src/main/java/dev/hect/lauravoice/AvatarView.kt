package dev.hect.lauravoice

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.WebViewAssetLoader
import dev.hect.lauravoice.core.Conn
import dev.hect.lauravoice.core.UiState
import dev.hect.lauravoice.core.View
import kotlinx.coroutines.delay

/** Hold this long without moving to start push-to-talk; a quicker release is a tap. */
private const val PTT_HOLD_MS = 150L

/** Outside push-to-talk, holding still this long is a long press (opens settings). */
private const val LONG_PRESS_MS = 600L

/** Avatar state for the engine's current view (concept.md, "What the clients must feed the avatar"). */
fun avatarState(ui: UiState): String = when {
    !ui.active -> "sleeping"
    ui.conn == Conn.BAD_KEY || ui.conn == Conn.CERT || ui.conn == Conn.NOT_CONFIGURED -> "error"
    ui.conn != Conn.ONLINE -> "offline"
    ui.view == View.SPEAKING -> "speaking"
    ui.view == View.THINKING -> "thinking"
    ui.muted -> "sleeping"
    ui.holding || ui.partial.isNotEmpty() || ui.awake -> "listening"
    else -> "idle"
}

private class AvatarWeb(context: Context, bg: Int, val onReady: () -> Unit, val onFailed: () -> Unit) : WebView(context) {
    private val main = Handler(Looper.getMainLooper())

    init {
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        @SuppressLint("SetJavaScriptEnabled")
        settings.javaScriptEnabled = true
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) setWebContentsDebuggingEnabled(true)
        settings.allowFileAccess = false
        setBackgroundColor(bg)
        // WRAP_CONTENT (the AndroidView default) makes the page's viewport height 0 and nothing paints.
        layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addJavascriptInterface(object {
            @JavascriptInterface fun ready() { main.post(onReady) }
            @JavascriptInterface fun failed(message: String) { main.post(onFailed) }
        }, "AvatarBridge")
        webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                android.util.Log.i("LauraAvatar", "${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                return true
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                main.post(onFailed)
                return true
            }
        }
        loadUrl("https://appassets.androidplatform.net/assets/avatar/index.html?bg=%06X".format(bg and 0xffffff))
    }

    fun js(code: String) = evaluateJavascript("window.__avatar && __avatar.$code", null)
}

/**
 * The voxel avatar in a WebView (three.js bundled in assets), driven by the engine. Touch goes through an
 * overlay to the same handlers as the orb; [fallback] is drawn if the WebView or WebGL is unavailable.
 * Outside push-to-talk, holding still calls [onLongPress].
 */
@Composable
fun Avatar(
    ui: UiState,
    car: Boolean,
    ptt: Boolean,
    onTap: () -> Unit,
    onPress: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit = {},
    fallback: @Composable () -> Unit,
) {
    val tap by rememberUpdatedState(onTap)
    val press by rememberUpdatedState(onPress)
    val longPress by rememberUpdatedState(onLongPress)
    var failed by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<AvatarWeb?>(null) }
    LaunchedEffect(Unit) {
        delay(3000)
        if (!ready) failed = true
    }
    if (failed) return fallback()

    val engine = App.engine
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    Box(modifier) {
        AndroidView(
            factory = { ctx ->
                runCatching { AvatarWeb(ctx, Bg.toArgb(), { ready = true }, { failed = true }) }
                    .onSuccess { web = it }
                    .getOrElse { failed = true; android.view.View(ctx) }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // A tap keeps the orb's action, holding still starts push-to-talk, and once the finger moves more than
        // ~10 dp the gesture rotates the avatar instead (horizontal = yaw with fling, vertical = pitch).
        Box(Modifier.fillMaxSize().pointerInput(ptt, ui.active) {
            val slop = 10.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown()
                val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                var last = down.position
                var dragging = false
                var talking = false
                var held = false
                val toTalk = ptt && ui.active
                while (true) {
                    val elapsed = android.os.SystemClock.uptimeMillis() - down.uptimeMillis
                    val wait = if (talking || held || dragging) null else (if (toTalk) PTT_HOLD_MS else LONG_PRESS_MS) - elapsed
                    val event = if (wait != null) withTimeoutOrNull(wait.coerceAtLeast(1)) { awaitPointerEvent() } else awaitPointerEvent()
                    if (event == null) {
                        if (toTalk) {
                            talking = true
                            press(true)
                        } else {
                            held = true
                            longPress()
                        }
                        continue
                    }
                    val c = event.changes.firstOrNull { it.id == down.id } ?: break
                    tracker.addPosition(c.uptimeMillis, c.position)
                    if (!c.pressed) {
                        when {
                            dragging -> web?.js("dragEnd(${tracker.calculateVelocity().x / size.width})")
                            talking -> press(false)
                            held -> {}
                            else -> { web?.js("poke()"); tap() }
                        }
                        break
                    }
                    if (!dragging && !held && (c.position - down.position).getDistance() > slop) {
                        dragging = true
                        if (talking) { talking = false; press(false) }
                        web?.js("dragStart()")
                    }
                    if (dragging) {
                        val d = c.position - last
                        web?.js("drag(${d.x / size.width}, ${d.y / size.height})")
                        c.consume()
                    }
                    last = c.position
                }
            }
        })
    }

    val w = web
    if (w == null || !ready) return
    val state = avatarState(ui)
    LaunchedEffect(w, state) { w.js("setBase('$state')") }
    LaunchedEffect(w, car) { w.js("setCar($car)") }
    LaunchedEffect(w) {
        engine.avatarEvents.collect { e ->
            when (e.kind) {
                "state" -> w.js("setState('${e.value}')")
                "reaction" -> if (e.value.all { it.isLetter() || it == '_' }) w.js("triggerReaction('${e.value}')")
                "emotion" -> if (e.value.all { it.isLetter() }) w.js("setEmotion('${e.value}')")
            }
        }
    }
    LaunchedEffect(w) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var lastM = -1f
            var lastA = -1f
            while (true) {
                val m = if (engine.ui.value.muted) 0f else engine.ui.value.level
                val a = engine.ttsLevel()
                if (kotlin.math.abs(m - lastM) > 0.01f || kotlin.math.abs(a - lastA) > 0.01f) {
                    w.js("feed($m, $a)")
                    lastM = m
                    lastA = a
                }
                delay(33)
            }
        }
    }
    DisposableEffect(w, lifecycle) {
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) {
                Lifecycle.Event.ON_RESUME -> { w.onResume(); w.js("pause(false)") }
                Lifecycle.Event.ON_PAUSE -> { w.js("pause(true)"); w.onPause() }
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
}
