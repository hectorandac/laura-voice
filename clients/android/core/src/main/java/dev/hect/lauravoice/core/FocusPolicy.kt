package dev.hect.lauravoice.core

import android.media.AudioManager

/**
 * What a reply does when another app takes audio focus while Laura speaks.
 *
 * Her focus request is transient-may-duck with CONTENT_TYPE_SPEECH and willPauseWhenDucked, so the system never
 * ducks or pauses her by itself: it calls us and we pick the volume. Navigation guidance (transient-may-duck)
 * and notifications get her quieter, not stopped. A transient exclusive loss (another assistant, a car's own
 * prompt under projection) gets her quieter still. Only a phone call stops the reply. A permanent loss (the user
 * started music) keeps her ducked until the reply ends; the next reply asks for focus again.
 */
object FocusPolicy {
    const val DUCK = 0.35f      // about -9 dB
    const val DEEP_DUCK = 0.15f // about -16 dB

    sealed interface Action {
        data class Volume(val gain: Float) : Action
        data object Stop : Action
    }

    fun decide(change: Int, inCall: Boolean): Action = when (change) {
        AudioManager.AUDIOFOCUS_GAIN, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE ->
            Action.Volume(1f)
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> if (inCall) Action.Stop else Action.Volume(DUCK)
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (inCall) Action.Stop else Action.Volume(DEEP_DUCK)
        AudioManager.AUDIOFOCUS_LOSS -> if (inCall) Action.Stop else Action.Volume(DUCK)
        else -> Action.Volume(1f)
    }

    fun inCall(mode: Int): Boolean =
        mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_CALL_SCREENING
}
