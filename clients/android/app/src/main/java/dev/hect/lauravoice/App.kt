package dev.hect.lauravoice

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.content.pm.ServiceInfo
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import dev.hect.lauravoice.car.CarDetector
import dev.hect.lauravoice.car.LauraCall
import dev.hect.lauravoice.core.AndroidAudio
import dev.hect.lauravoice.core.CarState
import dev.hect.lauravoice.core.ConfigStore
import dev.hect.lauravoice.core.VoiceCommands
import dev.hect.lauravoice.core.VoiceEngine
import dev.hect.lauravoice.core.VoiceServiceBase
import dev.hect.lauravoice.core.WsConnector
import dev.hect.lauravoice.overlay.LauraOverlay
import dev.hect.lauravoice.car.CallNotification
import dev.hect.lauravoice.widget.WidgetSync
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        app = this
        migrate()
        CarState.manualOverride = carOverride(carMode)
        // Engine, overlay and watch read car mode through the store, which follows CarState.active (the old "car"
        // pref is no longer read).
        store = ConfigStore(this).apply {
            carSource = { CarState.active.value }
            carCallSource = { CarState.call.value }
        }
        val audio = AndroidAudio(this, carMedia = { CarState.connected }, telecomCall = { CarState.call.value })
        engine = VoiceEngine("android", audio, store::load, { WsConnector(it) })
        engine.onCommand = ::command
        _carCall.value = prefs.getBoolean("car_call", true)
        CarDetector(this).start()
        LauraCall.start(this)
        MainScope().launch {
            CarState.call.drop(1).collect { on ->
                Log.i("Laura", "car call $on")
                engine.configChanged()
            }
        }
        WidgetSync.start(this)
        MainScope().launch {
            CarState.active.drop(1).collect { on ->
                Log.i("Laura", "car mode $on")
                engine.configChanged()
                runCatching { WatchSync.push(this@App, store.load()) }
            }
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            var started = 0
            override fun onActivityStarted(a: Activity) { started++; _visible.value = true }
            override fun onActivityStopped(a: Activity) { started--; _visible.value = started > 0 }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    /**
     * "Open settings" said to Laura. Opening an activity from the background needs "Display over other apps"; without
     * it (and with no Laura screen showing) she answers the turn as usual instead.
     */
    private fun command(cmd: String): Boolean {
        if (cmd != VoiceCommands.OPEN_SETTINGS || !(visible.value || Settings.canDrawOverlays(this))) return false
        startActivity(
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
        return true
    }

    /** One-time pref migrations for existing installs. */
    private fun migrate() {
        val p = getSharedPreferences("laura", MODE_PRIVATE)
        if (p.getInt("prefs_version", 0) < 1) {
            // Hands-free without the wake word is the product default now; the old manual car switch gives way to
            // automatic detection.
            p.edit().putString("mode", "handsfree").putBoolean("wake", false).remove("wake_sensitivity").remove("wake_hop")
                .putString("car_mode", "auto").putBoolean("car", false).putInt("prefs_version", 1).apply()
            File(filesDir, "wake").deleteRecursively()
        }
    }

    companion object {
        lateinit var store: ConfigStore
            private set
        lateinit var engine: VoiceEngine
            private set
        private val _visible = MutableStateFlow(false)
        /** One of the app's activities is on screen. */
        val visible: StateFlow<Boolean> = _visible

        private lateinit var app: App
        private val prefs get() = app.getSharedPreferences("laura", MODE_PRIVATE)

        /** Car mode setting: "auto" (follow detection), "on" or "off". */
        var carMode: String
            get() = prefs.getString("car_mode", "auto")!!
            set(v) {
                prefs.edit().putString("car_mode", v).apply()
                CarState.manualOverride = carOverride(v)
            }

        private fun carOverride(mode: String): Boolean? = when (mode) {
            "on" -> true
            "off" -> false
            else -> null
        }

        private val _carCall = MutableStateFlow(true)
        /** Car call mode (default on): in the car Laura is a call (`car/LauraCall`). */
        val carCallFlow: StateFlow<Boolean> = _carCall
        var carCall: Boolean
            get() = _carCall.value
            set(v) {
                prefs.edit().putBoolean("car_call", v).apply()
                _carCall.value = v
            }

        /** Developer options unlocked from About. */
        var developer: Boolean
            get() = prefs.getBoolean("developer", false)
            set(v) = prefs.edit().putBoolean("developer", v).apply()
    }
}

class VoiceService : VoiceServiceBase() {
    override val engine get() = App.engine
    override val activityClass = MainActivity::class.java
    private var overlay: LauraOverlay? = null
    private val callScope = MainScope()

    private val inCall get() = LauraCall.state.value != LauraCall.State.IDLE

    override fun foregroundTypes(): Int = super.foregroundTypes() or
        if (inCall) ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL else 0

    override fun overrideNotification(): Notification? = if (inCall) CallNotification.build(this) else null

    override fun onCreate() {
        super.onCreate()
        callScope.launch { LauraCall.state.drop(1).collect { promote() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val r = super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            overlay?.stop()
            overlay = null
        } else if (overlay == null) {
            overlay = LauraOverlay(this).also { it.start() }
        }
        return r
    }

    override fun onDestroy() {
        callScope.cancel()
        overlay?.stop()
        overlay = null
        super.onDestroy()
    }
}
