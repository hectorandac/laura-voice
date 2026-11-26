package dev.hect.lauravoice.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service (microphone type) that owns the engine while the assistant is on, so capture keeps
 * running with the screen off. Notification actions: mute/unmute, stop reply, turn off.
 */
abstract class VoiceServiceBase : Service() {
    abstract val engine: VoiceEngine
    abstract val activityClass: Class<*>

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wake: PowerManager.WakeLock? = null
    private var started = false

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            engine.networkAvailable()
        }
    }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            engine.devicesChanged()
        }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            engine.devicesChanged()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Assistant", NotificationManager.IMPORTANCE_LOW))
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netCallback)
        getSystemService(AudioManager::class.java).registerAudioDeviceCallback(deviceCallback, null)
        scope.launch {
            engine.ui.map { listOf(it.conn, it.view, it.muted, it.lastIgnored?.turnId, it.onWatch) }.distinctUntilChanged().collect {
                if (started) nm.notify(NOTIFICATION_ID, notification())
                if (it[3] != null) launch {
                    delay(RECALL_MS + 500)
                    if (started) nm.notify(NOTIFICATION_ID, notification())
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                engine.deactivate("turn off (${intent.getStringExtra("why") ?: "notification"})")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_MUTE -> engine.setMuted(!engine.ui.value.muted)
            ACTION_STOP_REPLY -> engine.stopReply()
            ACTION_RECALL -> engine.recallLast()
            ACTION_RECONNECT -> engine.reconnect()
        }
        if (!started) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            started = true
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "laura:voice").apply { acquire() }
            engine.activate()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        engine.deactivate("service destroyed")
        wake?.let { if (it.isHeld) it.release() }
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback) }
        getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(deviceCallback)
        scope.cancel()
        super.onDestroy()
    }

    private fun action(action: String, req: Int): PendingIntent = PendingIntent.getService(
        this, req, Intent(this, javaClass).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun notification(): Notification {
        val s = engine.ui.value
        val title = when {
            s.conn == Conn.ELSEWHERE -> if (s.onWatch) "On your watch" else "In use on another device"
            s.conn != Conn.ONLINE -> "Connecting…"
            s.muted -> "Muted"
            s.view == View.SPEAKING -> "Speaking"
            s.view == View.THINKING -> "Thinking…"
            else -> "Listening"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, activityClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE
        )
        val ignored = s.lastIgnored?.takeIf { it.recallable() }
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_laura)
            .setContentTitle("Laura · $title")
            .setContentText(if (ignored != null) "Ignored: ${ignored.text}" else s.route)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (s.conn == Conn.ELSEWHERE) b.addAction(0, "Use phone", action(ACTION_RECONNECT, 5))
        if (ignored != null) b.addAction(0, "For Laura", action(ACTION_RECALL, 4))
        b.addAction(0, if (s.muted) "Unmute" else "Mute", action(ACTION_MUTE, 1))
        if (ignored == null && s.conn != Conn.ELSEWHERE) b.addAction(0, "Stop reply", action(ACTION_STOP_REPLY, 2))
        return b.addAction(0, "Turn off", action(ACTION_STOP, 3)).build()
    }

    companion object {
        const val CHANNEL = "laura"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "dev.hect.lauravoice.STOP"
        const val ACTION_MUTE = "dev.hect.lauravoice.MUTE"
        const val ACTION_STOP_REPLY = "dev.hect.lauravoice.STOP_REPLY"
        const val ACTION_RECALL = "dev.hect.lauravoice.RECALL"
        const val ACTION_RECONNECT = "dev.hect.lauravoice.RECONNECT"

        fun start(context: Context, cls: Class<out VoiceServiceBase>) =
            ContextCompat.startForegroundService(context, Intent(context, cls))

        fun stop(context: Context, cls: Class<out VoiceServiceBase>, why: String) =
            context.startService(Intent(context, cls).setAction(ACTION_STOP).putExtra("why", why))
    }
}
