package dev.hect.lauravoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarCallTest {
    @Test fun wantedOnlyInTheCarWhileOn() {
        assertTrue(CarCall.wanted(enabled = true, car = true, on = true, configured = true))
        assertFalse(CarCall.wanted(enabled = false, car = true, on = true, configured = true))
        assertFalse(CarCall.wanted(enabled = true, car = false, on = true, configured = true))
        assertFalse(CarCall.wanted(enabled = true, car = true, on = false, configured = true))
        assertFalse(CarCall.wanted(enabled = true, car = true, on = true, configured = false))
    }

    @Test fun autoStartsOnlyWhenTheCarConnects() {
        assertTrue(CarCall.autoStart(enabled = true, wasCar = false, car = true, on = false, configured = true))
        // Hung up during the drive: no redial until the car connects again.
        assertFalse(CarCall.autoStart(enabled = true, wasCar = true, car = true, on = false, configured = true))
        assertFalse(CarCall.autoStart(enabled = true, wasCar = false, car = true, on = true, configured = true))
        assertFalse(CarCall.autoStart(enabled = false, wasCar = false, car = true, on = false, configured = true))
    }

    @Test fun status() {
        val on = UiState(active = true, conn = Conn.ONLINE, view = View.IDLE)
        assertEquals("Off", CarCall.status(UiState(), true))
        assertEquals("Listening", CarCall.status(on, true))
        assertEquals("Say \"Laura\"", CarCall.status(on, false))
        assertEquals("Speaking", CarCall.status(on.copy(view = View.SPEAKING), true))
        assertEquals("Muted", CarCall.status(on.copy(muted = true, view = View.SPEAKING), true))
        assertEquals("Connecting…", CarCall.status(on.copy(conn = Conn.CONNECTING), true))
    }

    @Test fun captionKeepsShortTextAndTheLatestSentences() {
        assertEquals("Hi there.", CarCall.caption("  Hi   there. "))
        val long = "First sentence that is fairly long and goes on. Second one is here. Third and last."
        assertEquals("Second one is here. Third and last.", CarCall.caption(long, 40))
        val c = CarCall.caption("one two three four five six seven eight nine ten eleven", 20)
        assertTrue(c.startsWith("…"))
        assertTrue(c.length <= 20)
        assertTrue(c.endsWith("eleven"))
        assertEquals("두 번째 문장이에요.", CarCall.caption("첫 번째 문장은 조금 길어요. 두 번째 문장이에요.", 12))
    }
}
