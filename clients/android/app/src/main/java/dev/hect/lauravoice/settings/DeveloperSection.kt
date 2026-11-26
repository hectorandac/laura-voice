package dev.hect.lauravoice.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hect.lauravoice.App
import dev.hect.lauravoice.LocalPalette
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
    val p = LocalPalette.current
    val ui by App.engine.ui.collectAsStateWithLifecycle()
    Eyebrow("Developer")
    SCard {
        Block {
            Label("Audio route")
            Seg(listOf("auto" to "Auto", "media" to "Media", "call" to "Call"), c.route) { apply(c.copy(route = it)) }
            Hint(
                "Media: replies play like music (car Bluetooth, headphones); no echo cancellation, so the mic pauses while " +
                    "Laura talks. Call: echo-cancelled call audio with voice barge-in on the loudspeaker. Auto picks Media " +
                    "for car mode, Bluetooth and headsets, Call on the loudspeaker."
            )
        }
        Sep()
        Block {
            Label("Codec")
            Seg(listOf("opus" to "Opus", "pcm" to "PCM"), c.codec) { apply(c.copy(codec = it)) }
        }
        Sep()
        Text(
            listOfNotNull(
                ui.stats.ifEmpty { null },
                ui.route.ifEmpty { null }?.let { "audio: $it" },
                ui.lauraConnected?.let { if (it) "Laura linked" else "Laura offline" },
                ui.profile.ifEmpty { null }?.let { "profile: $it" },
                ui.error.ifEmpty { null }?.let { "error: $it" },
            ).joinToString("\n").ifEmpty { "No stats yet" },
            Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.s2).padding(horizontal = 12.dp, vertical = 10.dp),
            fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 18.sp, color = p.ink3,
        )
        Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SButton(if (ui.active) "Turn off" else "Turn on", {
                if (ui.active) VoiceServiceBase.stop(ctx, VoiceService::class.java, "developer settings")
                else if (hasMic(ctx)) VoiceServiceBase.start(ctx, VoiceService::class.java)
            }, Modifier.weight(1f), small = true)
            SButton("Reconnect", { App.engine.reconnect() }, Modifier.weight(1f), small = true)
            SButton("Send to watch", { WatchSync.push(ctx, App.store.load()) }, Modifier.weight(1.3f), small = true)
        }
    }
    Eyebrow("Conversation")
    SCard { Block { Log(ui.lines) } }
    SButton("Hide developer options", onHide, Modifier.padding(top = 8.dp), tone = Tone.Ghost)
}

@Composable
private fun Log(lines: List<Line>) {
    val p = LocalPalette.current
    if (lines.isEmpty()) return Hint("Nothing yet.", top = 0.dp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (l in lines.takeLast(30)) {
            val mine = l.role == "user"
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    l.text,
                    Modifier.weight(1f, fill = false).clip(RoundedCornerShape(12.dp))
                        .background(
                            when (l.role) {
                                "user" -> p.accentSoft
                                "system" -> p.bad.copy(alpha = 0.18f)
                                "task" -> p.s3
                                "ignored", "recalled" -> Color0
                                else -> p.s2
                            }
                        ).padding(horizontal = 11.dp, vertical = 7.dp),
                    color = if (l.cut || l.role == "ignored" || l.role == "recalled") p.ink3 else p.ink,
                    textDecoration = if (l.cut) TextDecoration.LineThrough else null,
                    fontSize = 13.sp,
                )
                if (l.recallable()) SButton("For Laura", { App.engine.recall(l.turnId!!) }, tone = Tone.Ghost)
            }
            l.source?.let { Box(Modifier.padding(start = 6.dp)) { Text(it.label, fontSize = 10.sp, color = p.ink3) } }
        }
    }
}

private val Color0 = androidx.compose.ui.graphics.Color(0x14808080)
