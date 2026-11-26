package dev.hect.lauravoice.widget

import dev.hect.lauravoice.core.Conn
import dev.hect.lauravoice.core.Line
import dev.hect.lauravoice.core.Source
import dev.hect.lauravoice.core.UiState
import dev.hect.lauravoice.core.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetModelTest {
    private val on = UiState(active = true, conn = Conn.ONLINE, view = View.LISTENING)

    private fun state(ui: UiState, configured: Boolean = true, ptt: Boolean = false) = WidgetModels.from(ui, configured, ptt, null).state

    @Test fun states() {
        assertEquals(WState.ERROR, state(UiState(), configured = false))
        assertEquals(WState.OFF, state(UiState()))
        assertEquals(WState.ERROR, state(on.copy(conn = Conn.CERT)))
        assertEquals(WState.OFFLINE, state(on.copy(conn = Conn.CONNECTING)))
        assertEquals(WState.MUTED, state(on.copy(conn = Conn.CONNECTING, muted = true)))
        assertEquals(WState.ELSEWHERE, state(on.copy(conn = Conn.ELSEWHERE)))
        assertEquals(WState.LISTENING, state(on))
        assertEquals(WState.MUTED, state(on.copy(muted = true, view = View.IDLE)))
        assertEquals(WState.THINKING, state(on.copy(view = View.THINKING)))
        assertEquals(WState.SPEAKING, state(on.copy(view = View.SPEAKING, muted = true)))
        assertEquals(WState.IDLE, state(on.copy(view = View.IDLE), ptt = true))
    }

    @Test fun speakingShowsWhoAnswers() {
        val m = WidgetModels.from(on.copy(view = View.SPEAKING, speakingSource = Source("local", "bonsai-9b")), true, false, null)
        assertEquals("Local · Bonsai 9B", m.meta)
    }

    @Test fun levelAndStatsDoNotChangeTheModel() {
        assertEquals(WidgetModels.from(on, true, false, null), WidgetModels.from(on.copy(level = 0.8f, stats = "x"), true, false, null))
    }

    @Test fun lastReply() {
        assertNull(WidgetModels.lastReply(on) { 1L })
        val ui = on.copy(lines = listOf(Line("user", "hi", "t1"), Line("assistant", "  Hello   there ", "t1", source = Source("hermes", "claude-sonnet-5-5"))))
        val r = WidgetModels.lastReply(ui) { 42L }!!
        assertEquals("Hello there", r.text)
        assertEquals("Claude Sonnet 5.5 · via Hermes", r.source)
        assertFalse(r.local)
        assertEquals(42L, r.time)
    }

    @Test fun clip() {
        val long = "word ".repeat(100)
        val c = WidgetModels.clip(long, 40)
        assert(c.length <= 41 && c.endsWith("…"))
    }
}
