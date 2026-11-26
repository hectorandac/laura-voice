package dev.hect.lauravoice.core

/**
 * Car mode from hard signals only: Android Auto (or Automotive OS) connected, or the car's Bluetooth device
 * chosen in settings. No activity recognition: guessing "driving" from motion is what made her think he was
 * driving when he wasn't. A manual override (null = automatic) wins over both.
 */
object CarSignal {
    /** androidx.car.app.connection.CarConnection types. */
    const val NOT_CONNECTED = 0
    const val NATIVE = 1
    const val PROJECTION = 2

    fun active(override: Boolean?, connectionType: Int, carBluetooth: Boolean): Boolean =
        override ?: (connectionType == PROJECTION || connectionType == NATIVE || carBluetooth)
}
