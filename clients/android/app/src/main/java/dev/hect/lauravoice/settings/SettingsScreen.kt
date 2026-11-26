package dev.hect.lauravoice.settings

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.hect.lauravoice.App
import dev.hect.lauravoice.LocalPalette
import dev.hect.lauravoice.Palette
import dev.hect.lauravoice.WatchSync
import dev.hect.lauravoice.car.CarBluetoothPicker
import dev.hect.lauravoice.core.CarState
import dev.hect.lauravoice.core.Config
import dev.hect.lauravoice.core.Conn
import dev.hect.lauravoice.core.Tls
import dev.hect.lauravoice.core.UiState
import dev.hect.lauravoice.invocation.invocationReadiness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings: a home page with what changes day to day (listening, language) and one row per rarer area (your voice,
 * car, watch, calling her from anywhere, about), each opening its own page. The server sits behind the status card at
 * the top; Developer is on the About page once unlocked (tap the version seven times). Everything except the
 * connection fields applies immediately.
 */
@Composable
fun SettingsScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    var c by remember { mutableStateOf(App.store.load()) }
    var page by rememberSaveable { mutableStateOf(HOME) }
    var draft by remember { mutableStateOf<Config?>(null) }

    /** Saves a non-connection change right away. */
    fun apply(next: Config) {
        val saved = App.store.load()
        val merged = next.copy(url = saved.url, key = saved.key, pin = saved.pin, car = CarState.active.value)
        App.store.save(merged)
        App.engine.configChanged()
        runCatching { WatchSync.push(ctx, merged) }
        c = next
    }

    val back = {
        if (page == HOME) onDone() else {
            page = HOME
            c = App.store.load()
        }
    }
    BackHandler(enabled = page != HOME) { back() }

    AnimatedContent(
        page, Modifier.fillMaxSize().background(p.canvas),
        transitionSpec = {
            val forward = targetState != HOME
            (slideInHorizontally(tween(260)) { w -> if (forward) w / 4 else -w / 4 } + fadeIn(tween(200))) togetherWith
                (slideOutHorizontally(tween(260)) { w -> if (forward) -w / 4 else w / 4 } + fadeOut(tween(160)))
        },
        label = "page",
    ) { at ->
        Column(
            Modifier.fillMaxSize().background(p.canvas).statusBarsPadding().imePadding()
                .verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 40.dp)
        ) {
            when (at) {
                HOME -> Home(c, ::apply, onDone, open = { page = it }, scanned = { draft = it; page = SERVER })
                SERVER -> {
                    PageTop("Server", back)
                    ServerPage(App.store.load(), draft) { draft = null; c = App.store.load() }
                }
                VOICE -> {
                    PageTop("Your voice", back)
                    VoicePage()
                }
                CAR -> {
                    PageTop("Car", back)
                    CarPage()
                }
                WATCH -> {
                    PageTop("Watch", back)
                    WatchPage(c, onMove = { c = it }, ::apply)
                }
                INVOKE -> {
                    PageTop("Call her from anywhere", back)
                    InvocationSettingsSection()
                }
                ABOUT -> {
                    PageTop("About", back)
                    AboutPage(c, ::apply)
                }
            }
        }
    }
}

private const val HOME = "home"
private const val SERVER = "server"
private const val VOICE = "voice"
private const val CAR = "car"
private const val WATCH = "watch"
private const val INVOKE = "invoke"
private const val ABOUT = "about"

@Composable
private fun Home(c: Config, apply: (Config) -> Unit, onDone: () -> Unit, open: (String) -> Unit, scanned: (Config) -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val live by App.engine.ui.collectAsStateWithLifecycle()
    val carActive by CarState.active.collectAsStateWithLifecycle()
    val carDetected by CarState.detected.collectAsStateWithLifecycle()
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose {}
    }
    val (ready, steps) = remember(resumes) { invocationReadiness(ctx) }
    var message by remember { mutableStateOf("") }

    // Grabber and a chevron: dragging down here closes, like the swipe up that opened it.
    var dy by remember { mutableStateOf(0f) }
    Column(
        Modifier.fillMaxWidth().pointerInput(Unit) {
            detectVerticalDragGestures(onDragStart = { dy = 0f }, onDragEnd = { if (dy > 64.dp.toPx()) onDone() }) { ch, d ->
                dy += d
                ch.consume()
            }
        }
    ) {
        Box(Modifier.fillMaxWidth().height(14.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(32.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.hair2))
        }
        Box(Modifier.offset(x = (-8).dp)) { IconTap(Glyphs.Down, "Close settings", onDone) }
        Text(
            "Settings", Modifier.padding(start = 4.dp, top = 0.dp, bottom = 16.dp), fontSize = 30.sp, fontWeight = FontWeight.Medium,
            letterSpacing = (-0.7).sp, color = p.ink,
        )
    }

    if (!c.configured) Welcome(onScan = { scanQr(ctx, App.store.load(), { scanned(it) }) { message = it } }, onManual = { open(SERVER) }, message)
    else StatusCard(c, live, p) { open(SERVER) }

    Eyebrow("Conversation")
    SCard {
        Block {
            Label("Listening")
            Seg(listOf("handsfree" to "Hands-free", "ptt" to "Push to talk"), c.mode) { apply(c.copy(mode = it)) }
            Hint(
                if (c.mode == "ptt") "Hold her while you talk."
                else "She listens all the time and answers when you talk to her. Tap her to mute."
            )
        }
        Sep()
        Block {
            Label("Language")
            Seg(listOf("auto" to "Auto", "ko" to "한국어", "en" to "English"), c.lang) { apply(c.copy(lang = it)) }
        }
    }

    Eyebrow("Around you")
    SCard {
        NavRow(Glyphs.Voice, "Your voice", voiceSummary(live)) { open(VOICE) }
        Sep(62.dp)
        val mode = App.carMode
        NavRow(
            Glyphs.Car, "Car",
            (when (mode) { "on" -> "Always on"; "off" -> "Off"; else -> "Automatic" }) +
                if (mode == "auto") " · " + (if (carActive) "on now" else if (carDetected) "car detected" else "no car right now") else "",
        ) { open(CAR) }
        Sep(62.dp)
        NavRow(Glyphs.Watch, "Watch", "Reply volume ${"%+.0f".format(c.watchGainDb)} dB") { open(WATCH) }
        Sep(62.dp)
        NavRow(
            Glyphs.Spark, "Call her from anywhere", if (ready == steps) "Ready" else "$ready of $steps ready",
            dot = if (ready == steps) p.good else p.warn,
        ) { open(INVOKE) }
    }

    Eyebrow("App")
    SCard {
        NavRow(Glyphs.Info, "About", "Version ${versionName(ctx)}" + if (App.developer) " · developer options on" else "") { open(ABOUT) }
    }

    Row(Modifier.padding(start = 6.dp, end = 6.dp, top = 18.dp), verticalAlignment = Alignment.Top) {
        Glyph(Glyphs.Mic, p.accentText, 16.dp, Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            "Say “Laura, open settings” or “로라, 설정 열어줘” to come back here any time.",
            fontSize = 13.sp, lineHeight = 19.sp, color = p.ink3,
        )
    }
}

private fun voiceSummary(live: UiState): String = when {
    live.profile.startsWith("voice enrolled") -> "Enrolled" + live.profile.removePrefix("voice enrolled").replace("(", "· ").replace(")", "")
    live.profile == "no voice profile" -> "Not enrolled · she answers any voice"
    live.profile.isNotEmpty() -> live.profile.replaceFirstChar { it.uppercase() }
    else -> "Teach her your voice"
}

private fun versionName(ctx: Context) =
    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"

private fun connState(saved: Config, live: UiState, p: Palette) = if (!saved.configured) "Not set up" to p.ink4 else when (live.conn) {
    Conn.ONLINE -> "Connected" to p.good
    Conn.CONNECTING -> "Connecting…" to p.warn
    Conn.ELSEWHERE -> (if (live.onWatch) "On your watch" else "In use on another device") to p.warn
    Conn.CERT -> "Certificate not trusted" to p.bad
    Conn.BAD_KEY -> "Access key rejected" to p.bad
    Conn.NOT_CONFIGURED -> "Not set up" to p.ink4
    Conn.OFFLINE -> (if (live.active) "Offline" else "Off") to p.ink4
}

private fun laura(live: UiState) = live.lauraConnected?.let { if (it) " · Laura linked" else " · Laura offline" }.orEmpty()

@Composable
private fun StatusCard(c: Config, live: UiState, p: Palette, onClick: () -> Unit) {
    val (state, color) = connState(c, live, p)
    val host = remember(c.url) { runCatching { Uri.parse(c.url.replace("wss://", "https://")).let { u -> u.host + if (u.port > 0) ":${u.port}" else "" } }.getOrNull() ?: c.url }
    SCard(Modifier.clickable(onClick = onClick), glow = true) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            LauraFace()
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Laura", fontSize = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.2).sp, color = p.ink)
                Box(Modifier.padding(top = 3.dp)) { StatusLine(color, state + laura(live)) }
                Text(
                    host + if (c.pin.isNotEmpty()) " · certificate pinned" else "", Modifier.padding(top = 2.dp),
                    fontSize = 12.5.sp, color = p.ink3, maxLines = 1,
                )
            }
            Glyph(Glyphs.Right, p.ink4, 18.dp)
        }
    }
}

@Composable
private fun Welcome(onScan: () -> Unit, onManual: () -> Unit, message: String) {
    val p = LocalPalette.current
    SCard(glow = true) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 20.dp)) {
            LauraFace(88.dp)
            Text("Hi, I'm Laura", Modifier.padding(top = 14.dp), fontSize = 26.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.6).sp, color = p.ink)
            Text(
                "Connect me to your Laura server and I'm ready to talk, in English or 한국어.", Modifier.padding(top = 6.dp),
                fontSize = 14.5.sp, lineHeight = 22.sp, color = p.ink2,
            )
            Spacer(Modifier.height(20.dp))
            SButton("Scan setup QR code", onScan, Modifier.fillMaxWidth(), tone = Tone.Primary, icon = Glyphs.Qr)
            Spacer(Modifier.height(10.dp))
            SButton("Enter server details", onManual, Modifier.fillMaxWidth())
            if (message.isNotEmpty()) Text(message, Modifier.padding(top = 10.dp), fontSize = 13.sp, color = p.warn)
        }
    }
}

private fun scanQr(ctx: Context, base: Config, onConfig: (Config) -> Unit, onMessage: (String) -> Unit) {
    val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    GmsBarcodeScanning.getClient(ctx, opts).startScan()
        .addOnSuccessListener { b ->
            val parsed = b.rawValue?.let { Config.parse(it, base) }
            if (parsed != null) onConfig(parsed) else onMessage("That QR code isn't a Laura server link")
        }
        .addOnFailureListener { onMessage("Scanner unavailable: ${it.message}") }
}

@Composable
private fun ServerPage(saved: Config, scanned: Config?, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(saved) }
    var c by remember { mutableStateOf(scanned?.let { saved.copy(url = it.url, key = it.key, pin = it.pin) } ?: saved) }
    var message by remember { mutableStateOf(if (scanned != null) "Scanned ${scanned.url}" else "") }
    var probe by remember { mutableStateOf<Pair<String, String>?>(null) }
    val live by App.engine.ui.collectAsStateWithLifecycle()
    val dirty = c.url != saved.url || c.key != saved.key || c.pin != saved.pin

    fun save() {
        val next = App.store.load().copy(url = c.url, key = c.key, pin = c.pin)
        App.store.save(next)
        runCatching { WatchSync.push(ctx, next) }
        App.engine.configChanged()
        App.engine.reconnect()
        saved = App.store.load()
        onSaved()
        message = ""
    }

    val (state, color) = connState(saved, live, p)
    SCard(Modifier.padding(top = 6.dp), glow = true) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            StatusLine(color, state, 14.5.sp, p.ink)
            val detail = laura(live).removePrefix(" · ")
            if (detail.isNotEmpty()) Text(detail, Modifier.padding(start = 19.dp, top = 2.dp), fontSize = 12.5.sp, color = p.ink3)
        }
    }
    SButton(
        "Scan setup QR code", {
            scanQr(ctx, c, { parsed -> c = c.copy(url = parsed.url, key = parsed.key, pin = parsed.pin); message = "Scanned ${parsed.url}" }) { message = it }
        }, Modifier.fillMaxWidth().padding(top = 16.dp), icon = Glyphs.Qr,
    )
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, start = 2.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(p.hair))
        Text("or enter it yourself", Modifier.padding(horizontal = 10.dp), fontSize = 12.5.sp, color = p.ink3)
        Box(Modifier.weight(1f).height(1.dp).background(p.hair))
    }
    Input(
        "Server URL", c.url, { v -> c = (if ('?' in v || '#' in v) Config.parse(v, c) else null) ?: c.copy(url = v.trim()) },
        placeholder = "wss://host:8443/ws", keyboard = KeyboardType.Uri,
    )
    Input("Access key", c.key, { c = c.copy(key = it.trim()) }, secret = true)
    Input("Certificate pin (SHA-256)", c.pin, { c = c.copy(pin = Config.normPin(it)) }, mono = true)
    Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        SButton("Fetch certificate", {
            scope.launch {
                message = "Fetching certificate…"
                val res = withContext(Dispatchers.IO) {
                    runCatching {
                        val cert = Tls.probe(c.url.replace("wss://", "https://"))
                        Tls.sha256(cert) to cert.subjectX500Principal.name
                    }
                }
                res.onSuccess { probe = it; message = "" }.onFailure { message = "Could not reach the server: ${it.message}" }
            }
        }, tone = Tone.Ghost, icon = Glyphs.Lock, enabled = c.url.startsWith("wss://") || c.url.startsWith("https://"))
        Spacer(Modifier.weight(1f))
        SButton("Save & connect", ::save, tone = Tone.Primary, enabled = dirty && c.configured)
    }
    if (message.isNotEmpty()) Text(message, Modifier.padding(top = 10.dp, start = 2.dp), color = p.warn, fontSize = 13.sp)
    Hint("The key and pin stay on this phone; only the key is sent to the server.", Modifier.padding(horizontal = 2.dp), top = 16.dp)

    probe?.let { (sha, subject) ->
        AlertDialog(
            onDismissRequest = { probe = null },
            title = { Text("Trust this certificate?") },
            text = {
                Text(
                    "$subject\n\nSHA-256\n${sha.chunked(2).joinToString(":")}\n\nCompare with " +
                        "`laura_voice.py fingerprint` or openssl on the server before trusting.",
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                )
            },
            confirmButton = { TextButton(onClick = { c = c.copy(pin = sha); probe = null }) { Text("Trust") } },
            dismissButton = { TextButton(onClick = { probe = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun VoicePage() {
    val p = LocalPalette.current
    val live by App.engine.ui.collectAsStateWithLifecycle()
    val online = live.conn == Conn.ONLINE
    SCard(Modifier.padding(top = 6.dp)) {
        Block {
            Label("Voice profile", live.profile.ifEmpty { null })
            Text(
                "Lets Laura tell your voice from other people's: she only answers you, and only you can talk over her. " +
                    "You read about six short sentences aloud, in Korean and English.",
                fontSize = 13.5.sp, lineHeight = 20.sp, color = p.ink2,
            )
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SButton("Enroll my voice", { App.engine.enroll("start") }, Modifier.weight(1f), tone = Tone.Primary, enabled = online, icon = Glyphs.Mic)
                SButton("Delete", { App.engine.enroll("delete") }, enabled = online)
            }
            if (!online) Hint("Needs a connection.")
        }
    }
}

/** Car mode UI. The logic (App.carMode, CarState, App.carCall, the Bluetooth picker) is the car lane's. */
@Composable
private fun CarPage() {
    var mode by remember { mutableStateOf(App.carMode) }
    val detected by CarState.detected.collectAsStateWithLifecycle()
    val active by CarState.active.collectAsStateWithLifecycle()
    SCard(Modifier.padding(top = 6.dp)) {
        Block {
            Label("Car mode")
            Seg(listOf("auto" to "Automatic", "on" to "Always on", "off" to "Off"), mode) {
                mode = it
                App.carMode = it
            }
            Hint(
                when (mode) {
                    "auto" -> "Turns on by itself in the car (Android Auto or the car's Bluetooth). " +
                        if (active) "On now." else if (detected) "Car detected." else "No car detected."
                    "on" -> "Car mode stays on, even away from the car."
                    else -> "Car mode stays off, even in the car."
                }
            )
        }
    }
    if (mode == "auto") {
        Eyebrow("Detection")
        SCard { CarBluetoothPicker() }
        Hint("Optional: car mode also turns on while the phone is connected to this device.", Modifier.padding(horizontal = 6.dp), top = 8.dp)
    }
    Eyebrow("Android Auto")
    var call by remember { mutableStateOf(App.carCall) }
    SCard {
        SwitchRow(
            "Car call",
            if (call) "In the car Laura is a call: Android Auto shows her call screen, she uses the car's mic and " +
                "speakers, and navigation prompts and music wait while the call is up. She turns on when the car " +
                "connects; hanging up turns her off (Call Laura in Android Auto brings her back)."
            else "Laura plays through the car as a media app; navigation prompts make her quieter.",
            call,
        ) {
            call = it
            App.carCall = call
        }
    }
}

@Composable
private fun WatchPage(c: Config, onMove: (Config) -> Unit, apply: (Config) -> Unit) {
    val p = LocalPalette.current
    SCard(Modifier.padding(top = 6.dp)) {
        Block {
            Label("Reply volume", "${"%+.0f".format(c.watchGainDb)} dB")
            Slider(
                value = c.watchVolume, onValueChange = { onMove(c.copy(watchVolume = it)) },
                onValueChangeFinished = { apply(c) }, valueRange = 0f..1f, steps = 7,
                colors = SliderDefaults.colors(
                    thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.s2,
                    activeTickColor = p.onAccent.copy(alpha = 0.4f), inactiveTickColor = p.ink4,
                ),
            )
            Hint("Loudness of replies on the watch speaker. The watch uses the phone's other settings.", top = 4.dp)
        }
    }
}

@Composable
private fun AboutPage(c: Config, apply: (Config) -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val version = remember { versionName(ctx) }
    var developer by remember { mutableStateOf(App.developer) }
    var taps by remember { mutableIntStateOf(0) }
    var toast by remember { mutableStateOf<Toast?>(null) }
    fun say(text: String) {
        toast?.cancel()
        toast = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).also { it.show() }
    }
    SCard(Modifier.padding(top = 6.dp), glow = true) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            LauraFace()
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Laura", fontSize = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.2).sp, color = p.ink)
                Text(
                    "Version $version", Modifier.clickable {
                        if (developer) return@clickable
                        taps++
                        when {
                            taps >= 7 -> {
                                App.developer = true
                                developer = true
                                say("Developer options on")
                            }
                            taps >= 4 -> say("${7 - taps} more")
                        }
                    }.padding(vertical = 4.dp),
                    fontSize = 13.5.sp, color = p.ink3,
                )
            }
        }
    }
    if (developer) DeveloperSection(c, apply, onHide = { App.developer = false; developer = false })
}
