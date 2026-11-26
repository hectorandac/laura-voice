package dev.hect.lauravoice.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hect.lauravoice.App
import dev.hect.lauravoice.TextMuted
import dev.hect.lauravoice.TextPrimary
import dev.hect.lauravoice.VoiceService
import dev.hect.lauravoice.WatchSync
import dev.hect.lauravoice.core.Config
import dev.hect.lauravoice.core.Line
import dev.hect.lauravoice.core.VoiceServiceBase
import dev.hect.lauravoice.hasMic

/** Hidden until the version in About is tapped seven times: audio internals, live stats and the conversation log. */
@Composable
internal fun DeveloperSection(c: Config, apply: (Config) -> Unit, onHide: () -> Unit) {
    val ctx = LocalContext.current
    val ui by App.engine.ui.collectAsStateWithLifecycle()
    Section("Developer") {
        Field("Audio route") {
            Segmented(listOf("auto" to "Auto", "media" to "Media", "call" to "Call"), c.route) { apply(c.copy(route = it)) }
        }
        Hint(
            "Media: replies play like music (car Bluetooth, headphones); no echo cancellation, so the mic pauses while " +
                "Laura talks. Call: echo-cancelled call audio with voice barge-in on the loudspeaker. Auto picks Media " +
                "for car mode, Bluetooth and headsets, Call on the loudspeaker."
        )
        Field("Codec") {
            Segmented(listOf("opus" to "Opus", "pcm" to "PCM"), c.codec) { apply(c.copy(codec = it)) }
        }
        Text(
            listOfNotNull(
                ui.stats.ifEmpty { null },
                ui.route.ifEmpty { null }?.let { "audio: $it" },
                ui.lauraConnected?.let { if (it) "Laura linked" else "Laura offline" },
                ui.profile.ifEmpty { null }?.let { "profile: $it" },
                ui.error.ifEmpty { null }?.let { "error: $it" },
            ).joinToString("\n").ifEmpty { "No stats yet" },
            fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                if (ui.active) VoiceServiceBase.stop(ctx, VoiceService::class.java, "developer settings")
                else if (hasMic(ctx)) VoiceServiceBase.start(ctx, VoiceService::class.java)
            }) { Text(if (ui.active) "Turn off" else "Turn on") }
            OutlinedButton(onClick = { App.engine.reconnect() }) { Text("Reconnect") }
        }
        OutlinedButton(onClick = { WatchSync.push(ctx, App.store.load()) }, Modifier.fillMaxWidth()) {
            Text("Send settings to watch")
        }
        Field("Conversation") { Log(ui.lines) }
        TextButton(onClick = onHide) { Text("Hide developer options") }
    }
}

@Composable
private fun Log(lines: List<Line>) {
    if (lines.isEmpty()) return Hint("Nothing yet.")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (l in lines.takeLast(30)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    l.text,
                    Modifier.weight(1f, fill = false).clip(RoundedCornerShape(12.dp))
                        .background(
                            when (l.role) {
                                "user" -> Color(0xFF2B2640)
                                "system" -> Color(0x33F87171)
                                "task" -> Color(0xFF16242B)
                                "ignored", "recalled" -> Color(0x14FFFFFF)
                                else -> Color(0xFF221F30)
                            }
                        ).padding(horizontal = 10.dp, vertical = 6.dp),
                    color = if (l.cut || l.role == "ignored" || l.role == "recalled") TextMuted else TextPrimary,
                    textDecoration = if (l.cut) TextDecoration.LineThrough else null,
                    fontSize = 13.sp,
                )
                if (l.recallable()) TextButton(onClick = { App.engine.recall(l.turnId!!) }) { Text("For Laura", fontSize = 12.sp) }
            }
            l.source?.let { Text(it.label, Modifier.padding(start = 6.dp), fontSize = 10.sp, color = TextMuted) }
        }
    }
}
