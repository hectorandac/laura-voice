package dev.hect.lauravoice.core

/**
 * Car call rules and what the car screens say. In the car Laura is a call (a self-managed Telecom call named
 * "Laura"): it is up while car mode is on, the setting is on and she is on. Hanging up turns her off, which ends
 * the call until she is turned on again (Call Laura in Android Auto, or the phone).
 */
object CarCall {
    fun wanted(enabled: Boolean, car: Boolean, on: Boolean, configured: Boolean): Boolean =
        enabled && car && on && configured

    /** Turn her on for a call: car mode just came on and she's off. */
    fun autoStart(enabled: Boolean, wasCar: Boolean, car: Boolean, on: Boolean, configured: Boolean): Boolean =
        enabled && car && !wasCar && !on && configured

    /** One word or two for the driver. */
    fun status(ui: UiState, call: Boolean): String = when {
        !ui.active -> "Off"
        ui.conn == Conn.ELSEWHERE -> if (ui.onWatch) "On your watch" else "On another device"
        ui.conn != Conn.ONLINE -> "Connecting…"
        ui.muted -> "Muted"
        ui.view == View.SPEAKING -> "Speaking"
        ui.view == View.THINKING -> "Thinking…"
        call || ui.awake -> "Listening"
        else -> "Say \"Laura\""
    }

    /**
     * A short caption: the whole text if it fits in [max] characters, otherwise its latest sentences (what she is
     * saying now), otherwise its tail cut at a word.
     */
    fun caption(text: String, max: Int = 120): String {
        val t = text.replace(Regex("\\s+"), " ").trim()
        if (t.length <= max) return t
        val starts = Regex("(?<=[.!?。？！])\\s+").findAll(t).map { it.range.last + 1 }
        starts.firstOrNull { t.length - it <= max }?.let { return t.substring(it) }
        val tail = t.substring(t.length - (max - 1))
        val cut = tail.indexOf(' ').takeIf { it in 0 until tail.length / 3 } ?: -1
        return "…" + tail.substring(cut + 1)
    }
}
