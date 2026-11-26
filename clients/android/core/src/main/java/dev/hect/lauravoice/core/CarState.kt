package dev.hect.lauravoice.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Car mode. The phone's CarDetector reports Android Auto ([setConnectionType]) and the chosen car Bluetooth device
 * ([setCarBluetooth]); [detected] is either. The user's choice in Settings is [manualOverride] (null = follow
 * detection, true/false = forced). [active] is what the app acts on.
 */
object CarState {
    private var connectionType = CarSignal.NOT_CONNECTED
    private var carBluetooth = false

    private val _detected = MutableStateFlow(false)
    val detected: StateFlow<Boolean> = _detected.asStateFlow()

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** Android Auto (or Automotive OS) is connected right now, whatever the override says. */
    @Volatile var connected = false
        private set

    @Volatile var manualOverride: Boolean? = null
        set(v) {
            field = v
            update()
        }

    fun setConnectionType(t: Int) = synchronized(this) {
        connectionType = t
        update()
    }

    fun setCarBluetooth(on: Boolean) = synchronized(this) {
        carBluetooth = on
        update()
    }

    @Synchronized private fun update() {
        connected = connectionType != CarSignal.NOT_CONNECTED
        _detected.value = CarSignal.active(null, connectionType, carBluetooth)
        _active.value = CarSignal.active(manualOverride, connectionType, carBluetooth)
    }
}
