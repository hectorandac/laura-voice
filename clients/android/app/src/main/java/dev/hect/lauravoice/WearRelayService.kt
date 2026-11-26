package dev.hect.lauravoice

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.hect.lauravoice.core.Close
import dev.hect.lauravoice.core.Config
import dev.hect.lauravoice.core.RelayBus
import dev.hect.lauravoice.core.RelayFrames
import dev.hect.lauravoice.core.TransportListener
import dev.hect.lauravoice.core.WsConnector
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.concurrent.thread

/** Relays a watch session to the gateway: the watch opens a channel, the phone opens the WebSocket. */
class WearRelayService : WearableListenerService() {
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path == RelayFrames.PATH) Relay(applicationContext, channel).start()
    }
}

private const val SILENCE_MS = 10_000L

private class Relay(private val ctx: Context, private val channel: ChannelClient.Channel) : Thread("laura-relay") {
    override fun run() {
        val cc = Wearable.getChannelClient(ctx)
        val wake = ctx.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "laura:relay").apply { acquire(60 * 60 * 1000L) }
        RelayBus.set(true)
        try {
            val input = DataInputStream(Tasks.await(cc.getInputStream(channel)).buffered())
            val output = DataOutputStream(Tasks.await(cc.getOutputStream(channel)).buffered())
            val cfg = App.store.load()
            if (!cfg.configured) {
                RelayFrames.write(output, RelayFrames.CLOSE, RelayFrames.closePayload(Close.ABNORMAL, "phone app not configured"))
                return
            }
            val ws = WsConnector(cfg).connect(object : TransportListener {
                override fun onOpen() = Unit
                override fun onText(text: String) = forward(output, RelayFrames.TEXT, text.toByteArray())
                override fun onBinary(bytes: ByteArray) = forward(output, RelayFrames.BINARY, bytes)
                override fun onClosed(code: Int, reason: String) {
                    forward(output, RelayFrames.CLOSE, RelayFrames.closePayload(code, reason))
                    runCatching { output.close() }
                }
            })
            // The watch pings every 2 s while its app is open; silence means it is gone, so end the relay.
            val lastRx = java.util.concurrent.atomic.AtomicLong(SystemClock.elapsedRealtime())
            val watchdog = thread(name = "laura-relay-watchdog", isDaemon = true) {
                try {
                    while (SystemClock.elapsedRealtime() - lastRx.get() < SILENCE_MS) Thread.sleep(1000)
                    Log.i("LauraRelay", "watch silent, ending relay")
                    runCatching { Tasks.await(cc.close(channel)) }
                    runCatching { input.close() }
                } catch (_: InterruptedException) {
                }
            }
            try {
                while (true) {
                    val (type, payload) = RelayFrames.read(input) ?: break
                    lastRx.set(SystemClock.elapsedRealtime())
                    when (type) {
                        RelayFrames.TEXT -> ws.send(String(payload))
                        RelayFrames.BINARY -> ws.send(payload)
                        RelayFrames.CLOSE -> break
                    }
                }
            } finally {
                watchdog.interrupt()
                ws.close()
                Log.i("LauraRelay", "relay ended")
            }
        } catch (e: Exception) {
            Log.w("LauraRelay", "relay ended", e)
        } finally {
            runCatching { Tasks.await(cc.close(channel)) }
            RelayBus.set(false)
            if (wake.isHeld) wake.release()
        }
    }

    private fun forward(out: DataOutputStream, type: Int, payload: ByteArray) {
        runCatching { RelayFrames.write(out, type, payload) }
    }
}

object WatchSync {
    /** Shares the gateway settings with the watch for its direct (Wi-Fi/LTE) mode. */
    fun push(ctx: Context, c: Config) {
        val req = PutDataMapRequest.create(RelayFrames.CONFIG_PATH).apply {
            dataMap.putString("url", c.url)
            dataMap.putString("key", c.key)
            dataMap.putString("pin", c.pin)
            dataMap.putString("lang", c.lang)
            dataMap.putString("mode", c.mode)
            dataMap.putBoolean("car", c.car)
            dataMap.putFloat("volume", c.watchVolume)
            dataMap.putLong("t", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(ctx).putDataItem(req)
    }
}
