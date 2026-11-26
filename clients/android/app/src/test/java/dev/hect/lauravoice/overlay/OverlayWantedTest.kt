package dev.hect.lauravoice.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayWantedTest {
    private fun wanted(bubbleOn: Boolean = false, invoked: Boolean = false, leaving: Boolean = false, byWidget: Boolean = false) =
        LauraOverlay.wanted(bubbleOn, invoked, leaving, byWidget)

    @Test fun sideKeyAndAssistShowHer() {
        assertTrue(wanted(invoked = true))
        assertTrue(wanted(bubbleOn = true, invoked = true))
    }

    @Test fun widgetTurnsDont() {
        assertFalse(wanted(invoked = true, byWidget = true))
        assertFalse(wanted(bubbleOn = true, invoked = true, byWidget = true))
    }

    @Test fun bubbleSettingOutsideInvocations() {
        assertTrue(wanted(bubbleOn = true))
        assertFalse(wanted(bubbleOn = false))
        assertFalse(wanted(bubbleOn = true, leaving = true))
    }
}
