package dev.hect.lauravoice.core

import android.media.AudioDeviceInfo

enum class Route { MEDIA, CALL }

data class RouteDecision(val route: Route, val fullDuplex: Boolean, val why: String)

/**
 * MEDIA: normal audio mode, VOICE_RECOGNITION mic, replies on the assistant/media stream, so they reach car
 * Bluetooth (A2DP) and headphones and duck music. No platform echo cancellation, so half-duplex unless the
 * output is a wired/USB headset.
 * CALL: MODE_IN_COMMUNICATION, VOICE_COMMUNICATION mic with the platform echo canceller, so voice barge-in
 * works on the loudspeaker. Bluetooth would see a phone call (HFP), and media apps are disturbed while it is on.
 * A car call (a Telecom call named Laura while Android Auto is connected) always routes as CALL: Telecom owns the
 * audio mode and the device (normally the car's hands-free), the app only uses the voice-communication usage/source.
 */
object Routing {
    private val headsets = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
    )
    private val bluetooth = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST, AudioDeviceInfo.TYPE_HEARING_AID,
    )

    fun decide(setting: String, car: Boolean, outputs: Set<Int>, watch: Boolean = false, carCall: Boolean = false): RouteDecision {
        val headset = outputs.any { it in headsets }
        val bt = outputs.any { it in bluetooth }
        return when {
            watch -> RouteDecision(Route.MEDIA, false, "watch")
            carCall -> RouteDecision(Route.CALL, true, "car call")
            setting == "media" -> RouteDecision(Route.MEDIA, headset && !bt && !car, "media (setting)")
            setting == "call" -> RouteDecision(Route.CALL, true, "call (setting)")
            car -> RouteDecision(Route.MEDIA, false, "car mode")
            bt -> RouteDecision(Route.MEDIA, false, "bluetooth audio")
            headset -> RouteDecision(Route.MEDIA, true, "headset")
            else -> RouteDecision(Route.CALL, true, "loudspeaker")
        }
    }
}
