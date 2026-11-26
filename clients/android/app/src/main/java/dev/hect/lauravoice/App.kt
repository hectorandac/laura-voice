package dev.hect.lauravoice

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.util.Log
import dev.hect.lauravoice.car.CarDetector
import dev.hect.lauravoice.core.AndroidAudio
import dev.hect.lauravoice.core.CarState
import dev.hect.lauravoice.core.ConfigStore
import dev.hect.lauravoice.core.VoiceEngine
import dev.hect.lauravoice.core.VoiceServiceBase
import dev.hect.lauravoice.core.WsConnector
import dev.hect.lauravoice.overlay.LauraOverlay
import kotlinx.coroutines.MainScope
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
        store = ConfigStore(this).apply { carSource = { CarState.active.value } }
        engine = VoiceEngine("android", AndroidAudio(this, carMedia = { CarState.connected }), store::load, { WsConnector(it) })
        CarDetector(this).start()
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
        overlay?.stop()
        overlay = null
        super.onDestroy()
    }
}
