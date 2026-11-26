package dev.hect.lauravoice.widget

import dev.hect.lauravoice.core.Conn
import dev.hect.lauravoice.core.UiState
import dev.hect.lauravoice.core.View

/** What a widget shows; derived from the engine and only republished when it changes (see [WidgetSync]). */
data class WidgetModel(
    val state: WState = WState.OFF,
    /** Second line: who is answering, the mode, or what to do. */
    val meta: String = WState.OFF.meta,
    val reply: Reply? = null,
    /** She is on (the voice service runs), so the widget offers End in place of Talk. */
    val on: Boolean = state.on,
) {
    val face: String get() = state.face
    val canMute: Boolean get() = state.canMute
    val muted: Boolean get() = state == WState.MUTED
    /** The main control: End while she is on, Talk otherwise. Tapping her face is always Talk. */
    val primary: Primary get() = if (on) Primary.END else Primary.TALK
}

enum class Primary { TALK, END }

data class Reply(val text: String, val source: String, val local: Boolean, val time: Long)

enum class WState(val label: String, val face: String, val tone: Tone, val meta: String, val canMute: Boolean, val on: Boolean) {
    OFF("Off", "sleeping", Tone.OFF, "Tap to talk", false, false),
    IDLE("Ready", "idle", Tone.IDLE, "Push-to-talk · tap to talk", true, true),
    LISTENING("Listening", "listening", Tone.LISTEN, "Hands-free", true, true),
    THINKING("Thinking…", "thinking", Tone.THINK, "Working on it", true, true),
    SPEAKING("Speaking", "speaking", Tone.SPEAK, "", true, true),
    MUTED("Muted", "sleeping", Tone.OFF, "Not sending audio", true, true),
    ELSEWHERE("In use elsewhere", "idle", Tone.OFF, "Tap to take it back", false, true),
    OFFLINE("Connecting…", "offline", Tone.OFF, "Gateway unreachable, retrying", true, true),
    // on or off: a bad key or certificate can be shown while the service still runs
    ERROR("Needs setup", "error", Tone.BAD, "Open Laura to fix the connection", false, false),
}

enum class Tone { OFF, IDLE, LISTEN, THINK, SPEAK, BAD }

object WidgetModels {
    /** Maps the engine state; mic level and stats are left out on purpose so they never cause an update. */
    fun from(ui: UiState, configured: Boolean, ptt: Boolean, reply: Reply?): WidgetModel {
        val s = when {
            !configured || ui.conn == Conn.BAD_KEY || ui.conn == Conn.CERT || ui.conn == Conn.NOT_CONFIGURED -> WState.ERROR
            !ui.active -> WState.OFF
            ui.conn == Conn.ELSEWHERE -> WState.ELSEWHERE
            ui.view == View.SPEAKING -> WState.SPEAKING
            ui.view == View.THINKING -> WState.THINKING
            // muted wins over connecting: it is the one the user chose and can undo here
            ui.muted -> WState.MUTED
            ui.conn != Conn.ONLINE -> WState.OFFLINE
            ui.view == View.LISTENING || ui.holding || ui.awake || ui.partial.isNotEmpty() -> WState.LISTENING
            else -> WState.IDLE
        }
        val meta = when (s) {
            WState.SPEAKING -> ui.speakingSource?.label ?: reply?.source ?: "Answering"
            WState.ELSEWHERE -> if (ui.onWatch) "On your watch · tap to take it back" else s.meta
            WState.LISTENING -> if (ptt) "Push-to-talk" else s.meta
            WState.IDLE -> if (ptt) s.meta else "Hands-free · tap to talk"
            else -> s.meta
        }
        return WidgetModel(s, meta, reply, on = s.on || (s == WState.ERROR && ui.active))
    }

    /** The newest assistant reply in the conversation, if any; [timeOf] gives a stable time per turn. */
    fun lastReply(ui: UiState, timeOf: (String) -> Long): Reply? =
        ui.lines.lastOrNull { it.role == "assistant" && it.text.isNotBlank() }?.let {
            Reply(clip(it.text), it.source?.label ?: "Laura", it.source?.local ?: false, timeOf(it.turnId ?: it.text.take(24)))
        }

    /** Widgets show three or four lines; keep the stored and bundled text short. */
    fun clip(text: String, max: Int = 320): String {
        val t = text.trim().replace(Regex("\\s+"), " ")
        return if (t.length <= max) t else t.take(max).substringBeforeLast(' ') + "…"
    }
}
