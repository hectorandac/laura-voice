package dev.hect.lauravoice.car

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import androidx.core.telecom.util.ExperimentalAppActions
import dev.hect.lauravoice.App
import dev.hect.lauravoice.R
import dev.hect.lauravoice.VoiceService
import dev.hect.lauravoice.core.CarCall
import dev.hect.lauravoice.core.CarState
import dev.hect.lauravoice.core.VoiceServiceBase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * Car call mode: in the car Laura is a self-managed Telecom call named "Laura" (Core-Telecom `CallsManager`), so
 * Android Auto shows its call screen, the car's hands-free mic and speakers carry her, and the call's audio focus
 * keeps navigation prompts and media from cutting in. It is an outgoing call the app places and answers itself
 * (never rings). Up while car mode is on, Settings → Car → Car call is on and she is on (`core/CarCall`); car mode
 * coming on turns her on. Hanging up in the car turns her off; leaving the car ends the call (and turns her off
 * again if the call turned her on).
 */
object LauraCall {
    enum class State { IDLE, DIALING, ACTIVE, HELD }

    private const val TAG = "LauraCall"
    private val scope = MainScope()
    private lateinit var app: Context
    private val calls by lazy { CallsManager(app) }
    private var registered = false
    private var job: Job? = null
    private val hangup = MutableStateFlow(false)
    private var endingLocally = false
    /** This drive's call turned her on, so leaving the car turns her off again. */
    private var startedHer = false

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()
    /** When the call went active (elapsedRealtime), for the call notification's chronometer. */
    @Volatile var since = 0L
        private set

    fun start(context: Context) {
        app = context.applicationContext
        scope.launch {
            var wasCar = false
            combine(CarState.active, App.engine.ui.map { it.active }.distinctUntilChanged(), App.carCallFlow) { car, on, en ->
                Triple(car, on, en)
            }.collect { (car, on, enabled) ->
                val configured = App.store.load().configured
                if (CarCall.autoStart(enabled, wasCar, car, on, configured) && micAllowed()) {
                    Log.i(TAG, "car mode on: turning Laura on for a car call")
                    if (turnOn()) startedHer = true
                }
                if (wasCar && !car && startedHer && on) {
                    startedHer = false
                    VoiceServiceBase.stop(app, VoiceService::class.java, "left the car")
                }
                if (!car) startedHer = false
                wasCar = car
                if (CarCall.wanted(enabled, car, on, configured)) place() else end()
            }
        }
    }

    /** "Call Laura" from the car screen: turn her on; the call follows once she is on. */
    fun callLaura(): Boolean {
        val engine = App.engine
        if (!engine.active) return turnOn()
        if (engine.ui.value.muted) engine.setMuted(false)
        return true
    }

    /** Hang up from the car screen or the call notification: ends the call by turning her off. */
    fun hangUp() = VoiceServiceBase.stop(app, VoiceService::class.java, "hung up")

    private fun micAllowed() =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun turnOn(): Boolean = runCatching { VoiceServiceBase.start(app, VoiceService::class.java) }
        .onFailure { Log.w(TAG, "can't turn Laura on from the background", it) }.isSuccess

    private fun end() {
        if (job?.isActive == true) hangup.value = true
    }

    @OptIn(ExperimentalAppActions::class)
    private fun place() {
        if (job?.isActive == true) {
            hangup.value = false
            return
        }
        hangup.value = false
        endingLocally = false
        if (!registered) {
            registered = runCatching { calls.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE) }
                .onFailure { Log.w(TAG, "Telecom registration failed", it) }.isSuccess
            if (!registered) return
        }
        val attrs = CallAttributesCompat(
            displayName = "Laura",
            address = Uri.fromParts("sip", "Laura", null),
            direction = CallAttributesCompat.DIRECTION_OUTGOING,
            callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE,
        )
        _state.value = State.DIALING
        val icon = iconUri()
        job = scope.launch {
            try {
                calls.addCallWithExtensions(
                    attrs,
                    onAnswer = { },
                    onDisconnect = { cause ->
                        // The car (Android Auto, hands-free, Bluetooth) hung up.
                        Log.i(TAG, "disconnected by Telecom: $cause")
                        if (!endingLocally) hangUp()
                    },
                    onSetActive = {
                        _state.value = State.ACTIVE
                        App.engine.setMuted(false)
                    },
                    onSetInactive = {
                        // Held (a real phone call): stop talking and listening until it comes back.
                        _state.value = State.HELD
                        App.engine.stopReply()
                        App.engine.setMuted(true)
                    },
                ) {
                    icon?.let { addCallIconExtension(it) }
                    onCall { inCall() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "car call failed", e)
            } finally {
                over()
            }
        }
    }

    private fun over() {
        if (_state.value != State.IDLE) Log.i(TAG, "car call over")
        _state.value = State.IDLE
        CarState.setCall(false)
    }

    private suspend fun CallControlScope.inCall() {
        val r = setActive()
        if (r !is CallControlResult.Success) {
            Log.w(TAG, "setActive failed: $r")
            disconnect(DisconnectCause(DisconnectCause.ERROR))
            return
        }
        Log.i(TAG, "car call active")
        since = SystemClock.elapsedRealtime()
        _state.value = State.ACTIVE
        CarState.setCall(true)
        // The car's mute button mutes the call's mic in the platform; mirror it so the screens say Muted.
        launch { isMuted.distinctUntilChanged().drop(1).collect { App.engine.setMuted(it) } }
        // Telecom normally picks the car's hands-free; never leave her on the earpiece.
        launch {
            combine(currentCallEndpoint, availableEndpoints) { cur, all -> cur to all }.collect { (cur, all) ->
                val want = all.firstOrNull { it.type == CallEndpointCompat.TYPE_BLUETOOTH }
                    ?: all.firstOrNull { it.type == CallEndpointCompat.TYPE_SPEAKER }.takeIf { cur.type == CallEndpointCompat.TYPE_EARPIECE }
                if (want != null && want.type != cur.type) {
                    Log.i(TAG, "endpoint ${cur.name} -> ${want.name}")
                    requestEndpointChange(want)
                }
            }
        }
        hangup.first { it }
        endingLocally = true
        Log.i(TAG, "ending car call")
        disconnect(DisconnectCause(DisconnectCause.LOCAL))
        over()
        // addCall doesn't return while the collectors above run; the call is gone, so stop them.
        job?.cancel()
    }

    /** Her picture for the car's call screen, shared as a content:// URI (what the call icon extension needs). */
    private fun iconUri(): Uri? = runCatching {
        val f = File(app.filesDir, "car/laura.png")
        if (!f.exists()) {
            f.parentFile?.mkdirs()
            app.resources.openRawResource(R.drawable.car_avatar).use { i -> f.outputStream().use { i.copyTo(it) } }
        }
        FileProvider.getUriForFile(app, app.packageName + ".files", f)
    }.onFailure { Log.w(TAG, "no call icon", it) }.getOrNull()
}
