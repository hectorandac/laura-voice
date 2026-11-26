package dev.hect.lauravoice.core

import android.media.AudioManager
import dev.hect.lauravoice.core.FocusPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusPolicyTest {
    @Test fun guidanceDucksInsteadOfStopping() {
        assertEquals(Action.Volume(FocusPolicy.DUCK), FocusPolicy.decide(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, false))
    }

    @Test fun exclusiveTransientLossDucksDeeper() {
        assertEquals(Action.Volume(FocusPolicy.DEEP_DUCK), FocusPolicy.decide(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, false))
        assertTrue(FocusPolicy.DEEP_DUCK < FocusPolicy.DUCK)
    }

    @Test fun permanentLossKeepsTalkingQuieter() {
        assertEquals(Action.Volume(FocusPolicy.DUCK), FocusPolicy.decide(AudioManager.AUDIOFOCUS_LOSS, false))
    }

    @Test fun regainRestoresFullVolume() {
        assertEquals(Action.Volume(1f), FocusPolicy.decide(AudioManager.AUDIOFOCUS_GAIN, false))
    }

    @Test fun phoneCallStopsTheReply() {
        for (c in listOf(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, AudioManager.AUDIOFOCUS_LOSS))
            assertEquals(Action.Stop, FocusPolicy.decide(c, true))
        assertEquals(Action.Volume(1f), FocusPolicy.decide(AudioManager.AUDIOFOCUS_GAIN, true))
    }

    @Test fun ownCommunicationModeIsNotACall() {
        assertTrue(FocusPolicy.inCall(AudioManager.MODE_IN_CALL))
        assertTrue(FocusPolicy.inCall(AudioManager.MODE_RINGTONE))
        assertFalse(FocusPolicy.inCall(AudioManager.MODE_IN_COMMUNICATION))
        assertFalse(FocusPolicy.inCall(AudioManager.MODE_NORMAL))
    }
}

class CarSignalTest {
    @Test fun androidAutoTurnsCarModeOnAndOff() {
        assertTrue(CarSignal.active(null, CarSignal.PROJECTION, false))
        assertFalse(CarSignal.active(null, CarSignal.NOT_CONNECTED, false))
    }

    @Test fun chosenBluetoothIsAFallback() {
        assertTrue(CarSignal.active(null, CarSignal.NOT_CONNECTED, true))
    }

    @Test fun manualOverrideWins() {
        assertFalse(CarSignal.active(false, CarSignal.PROJECTION, true))
        assertTrue(CarSignal.active(true, CarSignal.NOT_CONNECTED, false))
    }
}
