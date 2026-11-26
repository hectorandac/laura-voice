package dev.hect.lauravoice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color as AColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hect.lauravoice.core.CarState
import dev.hect.lauravoice.core.Conn
import dev.hect.lauravoice.core.Enroll
import dev.hect.lauravoice.core.ServerMsg
import dev.hect.lauravoice.core.UiState
import dev.hect.lauravoice.core.View
import dev.hect.lauravoice.core.VoiceServiceBase
import dev.hect.lauravoice.settings.SettingsScreen
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    /** Bumped on every onStart: opening the app turns Laura on. */
    private val starts = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
        )
        setContent {
            LauraTheme {
                Surface(Modifier.fillMaxSize(), color = Bg) { Root(starts.intValue) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        starts.intValue++
    }
}

internal fun hasMic(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

@Composable
private fun Root(starts: Int) {
    val ctx = LocalContext.current
    var settings by remember { mutableStateOf(!App.store.load().configured) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) VoiceServiceBase.start(ctx, VoiceService::class.java)
    }
    val turnOn = {
        if (hasMic(ctx)) VoiceServiceBase.start(ctx, VoiceService::class.java)
        else launcher.launch(buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray())
    }
    LaunchedEffect(starts) {
        runCatching { WatchSync.push(ctx, App.store.load()) }
        if (App.store.load().configured && !App.engine.active) turnOn()
    }
    App.engine.ui.collectAsStateWithLifecycle().value.enroll?.let { EnrollDialog(it) }
    if (settings) {
        val close = {
            settings = false
            if (App.store.load().configured && !App.engine.active) turnOn()
        }
        BackHandler(onBack = close)
        SettingsScreen(onDone = close)
    } else {
        MainScreen(onSettings = { settings = true }, turnOn = turnOn)
    }
}

@Composable
private fun MainScreen(onSettings: () -> Unit, turnOn: () -> Unit) {
    val engine = App.engine
    val ui by engine.ui.collectAsStateWithLifecycle()
    val car by CarState.active.collectAsStateWithLifecycle()
    val ptt = App.store.load().mode == "ptt"
    val haptics = LocalHapticFeedback.current

    val onTap = {
        when {
            !ui.active -> turnOn()
            ui.conn == Conn.CERT || ui.conn == Conn.BAD_KEY || ui.conn == Conn.NOT_CONFIGURED -> onSettings()
            ui.conn == Conn.ELSEWHERE || ui.conn == Conn.OFFLINE -> engine.reconnect()
            ui.view == View.SPEAKING -> engine.stopReply()
            !ptt -> engine.setMuted(!ui.muted)
        }
    }
    val onPress = { down: Boolean -> if (down) engine.pttDown() else engine.pttUp() }
    val openSettings = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onSettings()
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        Avatar(ui, car, ptt, onTap, onPress, Modifier.fillMaxSize(), onLongPress = openSettings) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Orb(ui, ptt, onTap, onPress) }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (a in ui.approvals) ApprovalCard(a) { choice -> engine.approve(a.approvalId, choice) }
            Extras(ui)
            StatusLabel(ui, ptt)
            Handle(onSettings)
        }
    }
}

/** What the tiny label under the avatar says, and whether it stays (needs attention) or fades. */
private fun status(ui: UiState, ptt: Boolean): Pair<String, Boolean> = when {
    !ui.active -> "Tap to wake Laura" to false
    ui.conn == Conn.ELSEWHERE -> (if (ui.onWatch) "On your watch · tap to use the phone" else "In use on another device · tap to use here") to true
    ui.conn == Conn.CERT -> "Certificate not trusted · tap for settings" to true
    ui.conn == Conn.BAD_KEY -> "Access key rejected · tap for settings" to true
    ui.conn == Conn.NOT_CONFIGURED -> "Not set up · tap for settings" to true
    ui.conn == Conn.CONNECTING -> "Connecting…" to false
    ui.conn != Conn.ONLINE -> "Offline · reconnecting" to true
    ui.view == View.SPEAKING -> (ui.speakingSource?.label ?: "") to false
    ui.muted -> "Muted · tap to listen" to true
    ptt -> "Hold to talk" to false
    else -> "" to false
}

@Composable
private fun StatusLabel(ui: UiState, ptt: Boolean) {
    val (text, sticky) = status(ui, ptt)
    var shown by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(text, sticky) {
        if (text.isEmpty()) {
            visible = false
            return@LaunchedEffect
        }
        shown = text
        visible = true
        if (!sticky) {
            delay(3000)
            visible = false
        }
    }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(600), label = "status")
    Text(
        shown, Modifier.fillMaxWidth().alpha(alpha), textAlign = TextAlign.Center,
        color = TextMuted, fontSize = 12.sp, maxLines = 1,
    )
}

/** Things that need a tap only when they exist: "For Laura" for an ignored utterance, running tasks. */
@Composable
private fun Extras(ui: UiState) {
    val engine = App.engine
    val ignored = ui.lastIgnored
    var recallable by remember(ignored) { mutableStateOf(ignored?.recallable() == true) }
    LaunchedEffect(ignored) {
        if (ignored != null && recallable) {
            delay(maxOf(0L, ignored.time + dev.hect.lauravoice.core.RECALL_MS - android.os.SystemClock.elapsedRealtime()))
            recallable = false
        }
    }
    AnimatedVisibility(recallable && ignored != null, enter = fadeIn(), exit = fadeOut()) {
        Pill("“${ignored?.text.orEmpty().take(40)}”", "For Laura") { ignored?.turnId?.let(engine::recall) }
    }
    val tasks = ui.lines.filter { l ->
        l.role == "task" && l.text.substringBefore('\n').let { it.endsWith("· running") || it.endsWith("· queued") }
    }
    for (t in tasks) Pill(t.text.substringBefore('\n'), "Cancel") { t.turnId?.let(engine::cancelTask) }
}

@Composable
private fun Pill(text: String, action: String, onAction: () -> Unit) {
    Row(
        Modifier.widthIn(max = 420.dp).clip(RoundedCornerShape(50)).background(Color(0x1FFFFFFF)).padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f, fill = false), color = TextSecondary, fontSize = 13.sp, maxLines = 1)
        TextButton(onClick = onAction) { Text(action, fontSize = 13.sp) }
    }
}

/** A faint bar at the bottom: tap or swipe up for settings (long-pressing the avatar works too, outside push-to-talk). */
@Composable
private fun Handle(onOpen: () -> Unit) {
    var dy by remember { mutableStateOf(0f) }
    Box(
        Modifier.fillMaxWidth().height(28.dp)
            .pointerInput(Unit) { detectTapGestures { onOpen() } }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dy = 0f },
                    onDragEnd = { if (dy < -24.dp.toPx()) onOpen() },
                ) { change, d -> dy += d; change.consume() }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(50)).background(Color(0x2EFFFFFF)))
    }
}

/** Drawn only if the WebView avatar can't run. */
@Composable
private fun Orb(ui: UiState, ptt: Boolean, onTap: () -> Unit, onPress: (Boolean) -> Unit) {
    val target = when {
        !ui.active || ui.view == View.OFFLINE -> Color(0xFF3A3650)
        ui.muted -> Color(0xFF4B4760)
        ui.view == View.SPEAKING -> Color(0xFF22D3EE)
        ui.view == View.THINKING -> Color(0xFFF0ABFC)
        ui.holding || ui.view == View.LISTENING -> Accent
        else -> Color(0xFF5B5480)
    }
    val color by animateColorAsState(target, label = "orb")
    val level by animateFloatAsState(if (ui.muted || !ui.active) 0f else ui.level, label = "level")
    Canvas(
        Modifier.size(220.dp).pointerInput(ptt, ui.active) {
            detectTapGestures(
                onPress = {
                    if (ptt && ui.active) {
                        onPress(true)
                        tryAwaitRelease()
                        onPress(false)
                    }
                },
                onTap = { onTap() },
            )
        }
    ) {
        val r = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent), c, r), r, c)
        drawCircle(color, r * (0.55f + 0.25f * level), c)
        drawCircle(Color.White.copy(alpha = 0.15f), r * 0.3f, c)
    }
}

/** Approval requests stay on screen until answered: these buttons are the only way an approval_response is sent. */
@Composable
private fun ApprovalCard(a: ServerMsg.ApprovalRequest, onChoice: (String) -> Unit) {
    Column(
        Modifier.widthIn(max = 480.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xF02A2113))
            .padding(14.dp)
    ) {
        Text("Approval needed", color = Warn, fontSize = 13.sp)
        Text(a.description.ifEmpty { a.text }, fontSize = 14.sp, color = TextPrimary)
        if (a.command.isNotEmpty()) Text(a.command, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFFD6D3E3))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (c in a.choices) TextButton(onClick = { onChoice(c) }) {
                Text(mapOf("once" to "Once", "session" to "Session", "always" to "Always", "deny" to "Deny")[c] ?: c)
            }
        }
    }
}

@Composable
private fun EnrollDialog(e: Enroll) {
    val engine = App.engine
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Voice enrollment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (e.total > 0 && !e.done) Text("${e.index + 1} / ${e.total}", color = TextMuted)
                if (e.text.isNotEmpty()) Text(e.text, fontSize = 20.sp)
                Text(e.status, color = TextSecondary)
            }
        },
        confirmButton = {
            TextButton(onClick = { engine.enroll(if (e.done) "close" else "cancel"); if (!e.done) engine.enroll("close") }) {
                Text(if (e.done) "Close" else "Cancel")
            }
        },
    )
}
