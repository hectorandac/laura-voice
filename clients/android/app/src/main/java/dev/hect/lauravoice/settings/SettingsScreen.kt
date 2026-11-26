package dev.hect.lauravoice.settings

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.hect.lauravoice.App
import dev.hect.lauravoice.Bad
import dev.hect.lauravoice.Good
import dev.hect.lauravoice.SurfaceTone
import dev.hect.lauravoice.TextMuted
import dev.hect.lauravoice.TextPrimary
import dev.hect.lauravoice.TextSecondary
import dev.hect.lauravoice.Warn
import dev.hect.lauravoice.WatchSync
import dev.hect.lauravoice.car.CarBluetoothPicker
import dev.hect.lauravoice.core.CarState
import dev.hect.lauravoice.core.Config
import dev.hect.lauravoice.core.Conn
import dev.hect.lauravoice.core.Tls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one settings screen: Connection, Voice & language, Car, Watch, Invocation, About, and Developer once unlocked
 * (tap the version seven times). Everything except the connection fields applies immediately.
 */
@Composable
fun SettingsScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var c by remember { mutableStateOf(App.store.load()) }
    var developer by remember { mutableStateOf(App.developer) }

    /** Saves a non-connection change right away. */
    fun apply(next: Config) {
        val saved = App.store.load()
        val merged = next.copy(url = saved.url, key = saved.key, pin = saved.pin, car = CarState.active.value)
        App.store.save(merged)
        App.engine.configChanged()
        runCatching { WatchSync.push(ctx, merged) }
        c = next
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onDone)
            Text("Settings", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ConnectionSection(c, onSaved = { c = App.store.load() })
            Section("Voice & language") {
                Field("Listening") {
                    Segmented(listOf("handsfree" to "Hands-free", "ptt" to "Push to talk"), c.mode) { apply(c.copy(mode = it)) }
                }
                Hint(
                    if (c.mode == "ptt") "Hold the avatar while you talk."
                    else "Laura listens all the time and answers when you talk to her. Tap the avatar to mute."
                )
                Field("Language") {
                    Segmented(listOf("auto" to "Auto", "ko" to "한국어", "en" to "English"), c.lang) { apply(c.copy(lang = it)) }
                }
                VoiceProfile()
            }
            CarSection()
            Section("Watch") {
                Field("Reply volume  ${"%+.0f".format(c.watchGainDb)} dB") {
                    Slider(
                        value = c.watchVolume, onValueChange = { c = c.copy(watchVolume = it) },
                        onValueChangeFinished = { apply(c) }, valueRange = 0f..1f, steps = 7,
                    )
                }
                Hint("Loudness of replies on the watch speaker. The watch uses the phone's other settings.")
            }
            Section("Invocation") { InvocationSettingsSection() }
            AboutSection(developer) { developer = it }
            if (developer) DeveloperSection(c, ::apply, onHide = { App.developer = false; developer = false })
        }
    }
}

@Composable
private fun ConnectionSection(saved: Config, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var c by remember { mutableStateOf(saved) }
    var message by remember { mutableStateOf("") }
    var probe by remember { mutableStateOf<Pair<String, String>?>(null) }
    val live by App.engine.ui.collectAsStateWithLifecycle()
    val dirty = c.url != saved.url || c.key != saved.key || c.pin != saved.pin

    fun save() {
        val next = App.store.load().copy(url = c.url, key = c.key, pin = c.pin)
        App.store.save(next)
        runCatching { WatchSync.push(ctx, next) }
        App.engine.configChanged()
        App.engine.reconnect()
        onSaved()
        message = ""
    }

    Section("Connection") {
        val (state, color) = if (!saved.configured) "Not set up" to TextMuted else when (live.conn) {
            Conn.ONLINE -> "Connected" to Good
            Conn.CONNECTING -> "Connecting…" to Warn
            Conn.ELSEWHERE -> (if (live.onWatch) "On your watch" else "In use on another device") to Warn
            Conn.CERT -> "Certificate not trusted" to Bad
            Conn.BAD_KEY -> "Access key rejected" to Bad
            Conn.NOT_CONFIGURED -> "Not set up" to TextMuted
            Conn.OFFLINE -> (if (live.active) "Offline" else "Off") to TextMuted
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(color))
            Spacer(Modifier.width(8.dp))
            Text(state, fontSize = 14.sp, color = TextPrimary)
            live.lauraConnected?.let { Text(if (it) "  ·  Laura linked" else "  ·  Laura offline", fontSize = 13.sp, color = TextMuted) }
        }
        OutlinedButton(onClick = {
            val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            GmsBarcodeScanning.getClient(ctx, opts).startScan()
                .addOnSuccessListener { b ->
                    val parsed = b.rawValue?.let { Config.parse(it, c) }
                    if (parsed != null) {
                        c = c.copy(url = parsed.url, key = parsed.key, pin = parsed.pin)
                        message = "Scanned ${parsed.url}"
                    } else message = "That QR code isn't a Laura server link"
                }
                .addOnFailureListener { message = "Scanner unavailable: ${it.message}" }
        }, Modifier.fillMaxWidth()) { Text("Scan setup QR code") }
        OutlinedTextField(
            c.url, { v -> c = (if ('?' in v || '#' in v) Config.parse(v, c) else null) ?: c.copy(url = v.trim()) },
            Modifier.fillMaxWidth(), label = { Text("Server URL") }, singleLine = true,
            placeholder = { Text("wss://host:8443/ws") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            c.key, { c = c.copy(key = it.trim()) }, Modifier.fillMaxWidth(), label = { Text("Access key") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
        )
        OutlinedTextField(
            c.pin, { c = c.copy(pin = Config.normPin(it)) }, Modifier.fillMaxWidth(),
            label = { Text("Certificate pin (SHA-256)") }, singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
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
            }, enabled = c.url.startsWith("wss://") || c.url.startsWith("https://")) { Text("Fetch certificate") }
            Spacer(Modifier.weight(1f))
            Button(onClick = ::save, enabled = dirty && c.configured) { Text("Save & connect") }
        }
        if (message.isNotEmpty()) Text(message, color = Warn, fontSize = 13.sp)
    }

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
private fun VoiceProfile() {
    val live by App.engine.ui.collectAsStateWithLifecycle()
    val online = live.conn == Conn.ONLINE
    Field("Voice profile" + if (live.profile.isNotEmpty()) ": ${live.profile}" else "") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { App.engine.enroll("start") }, enabled = online) { Text("Enroll my voice") }
            OutlinedButton(onClick = { App.engine.enroll("delete") }, enabled = online) { Text("Delete") }
        }
    }
    Hint("Lets Laura tell your voice from other people's." + if (!online) " Needs a connection." else "")
}

@Composable
private fun CarSection() {
    var mode by remember { mutableStateOf(App.carMode) }
    val detected by CarState.detected.collectAsStateWithLifecycle()
    val active by CarState.active.collectAsStateWithLifecycle()
    Section("Car") {
        Field("Car mode") {
            Segmented(listOf("auto" to "Automatic", "on" to "Always on", "off" to "Off"), mode) {
                mode = it
                App.carMode = it
            }
        }
        Hint(
            when (mode) {
                "auto" -> "Turns on by itself in the car (Android Auto or the car's Bluetooth). " +
                    if (active) "On now." else if (detected) "Car detected." else "No car detected."
                "on" -> "Car mode stays on, even away from the car."
                else -> "Car mode stays off, even in the car."
            }
        )
        if (mode == "auto") {
            CarBluetoothPicker()
            Hint("Optional: car mode also turns on while the phone is connected to this device.")
        }
    }
}

@Composable
private fun AboutSection(developer: Boolean, onDeveloper: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    var taps by remember { mutableIntStateOf(0) }
    var toast by remember { mutableStateOf<Toast?>(null) }
    fun say(text: String) {
        toast?.cancel()
        toast = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).also { it.show() }
    }
    Section("About") {
        Text("Laura", fontSize = 15.sp, color = TextPrimary)
        Text(
            "Version $version", Modifier.clickable {
                if (developer) return@clickable
                taps++
                when {
                    taps >= 7 -> {
                        App.developer = true
                        onDeveloper(true)
                        say("Developer options on")
                    }
                    taps >= 4 -> say("${7 - taps} more")
                }
            }.padding(vertical = 4.dp),
            fontSize = 13.sp, color = TextMuted,
        )
    }
}

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title.uppercase(), fontSize = 12.sp, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.primary)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SurfaceTone).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

@Composable
internal fun Field(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 13.sp, color = TextSecondary)
        content()
    }
}

@Composable
internal fun Hint(text: String) = Text(text, fontSize = 12.sp, color = TextMuted)

@Composable
internal fun Segmented(options: List<Pair<String, String>>, value: String, onChange: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (v, label) ->
            SegmentedButton(
                selected = value == v, onClick = { onChange(v) }, shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = {},
            ) { Text(label, fontSize = 13.sp, maxLines = 1) }
        }
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(18.dp)) {
            val w = 2.dp.toPx()
            val col = Color(0xFFECEAF5)
            drawLine(col, Offset(size.width * 0.65f, size.height * 0.1f), Offset(size.width * 0.25f, size.height * 0.5f), w, StrokeCap.Round)
            drawLine(col, Offset(size.width * 0.25f, size.height * 0.5f), Offset(size.width * 0.65f, size.height * 0.9f), w, StrokeCap.Round)
        }
    }
}
