package dev.hect.lauravoice.wear

import android.app.Application
import android.content.Context
import android.content.Context.MODE_PRIVATE
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.hect.lauravoice.core.AndroidAudio
import dev.hect.lauravoice.core.Close
import dev.hect.lauravoice.core.ConfigStore
import dev.hect.lauravoice.core.Connector
import dev.hect.lauravoice.core.RelayFrames
import dev.hect.lauravoice.core.Transport
import dev.hect.lauravoice.core.TransportListener
import dev.hect.lauravoice.core.VoiceEngine
import dev.hect.lauravoice.core.WsConnector
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class WearApp : Application() {
    override fun onCreate() {
        super.onCreate()
        app = this
        store = ConfigStore(this)
        val prefs = getSharedPreferences("laura", MODE_PRIVATE)
        if (!prefs.getBoolean("init", false)) {
            store.save(store.load().copy(mode = "ptt", url = "relay://phone"))
            prefs.edit().putBoolean("init", true).apply()
        }
        engine = VoiceEngine("wear", AndroidAudio(this, mediaUsage = true, gainDb = { store.load().watchGainDb }), ::config, { c ->
            if (c.transport == "direct") WsConnector(c) else ChannelConnector(this)
        }, watch = true)
    }

    private fun config() = store.load().let { c ->
        if (c.transport == "direct") c.copy(url = getSharedPreferences("laura", MODE_PRIVATE).getString("direct_url", "")!!)
        else c.copy(url = "relay://phone")
    }

    companion object {
        /** Settings live on the phone (mode, language, car, gateway); the watch mirrors its Data Layer item. */
        fun apply(ctx: Context, item: DataItem) {
            val m = DataMapItem.fromDataItem(item).dataMap
            ctx.getSharedPreferences("laura", MODE_PRIVATE).edit().putString("direct_url", m.getString("url", "")).apply()
            store.save(
                store.load().copy(
                    key = m.getString("key", ""), pin = m.getString("pin", ""), lang = m.getString("lang", "auto"),
                    mode = m.getString("mode", "ptt"), car = m.getBoolean("car", false),
                    watchVolume = m.getFloat("volume", 0.75f),
                )
            )
            engine.configChanged()
        }

        fun syncConfig() {
            val ctx = app
            Wearable.getDataClient(ctx).dataItems.addOnSuccessListener { items ->
                items.forEach { if (it.uri.path == RelayFrames.CONFIG_PATH) apply(ctx, it) }
                items.release()
            }
        }

        private lateinit var app: Context
        lateinit var store: ConfigStore
            private set
        lateinit var engine: VoiceEngine
            private set
    }
}

/** Receives the gateway settings pushed from the phone app (used by direct mode). */
class ConfigListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        for (e in events) if (e.dataItem.uri.path == RelayFrames.CONFIG_PATH) WearApp.apply(this, e.dataItem)
    }
}

/** Tunnels the protocol to the phone app over a Data Layer channel; the phone holds the WebSocket. */
class ChannelConnector(private val ctx: Context) : Connector {
    override fun connect(listener: TransportListener): Transport {
        val done = AtomicBoolean(false)
        val cc = Wearable.getChannelClient(ctx)
        var out: DataOutputStream? = null
        var channel: ChannelClient.Channel? = null
        fun finish(code: Int, reason: String) {
            if (!done.compareAndSet(false, true)) return
            channel?.let { runCatching { cc.close(it) } }
            listener.onClosed(code, reason)
        }
        thread(name = "laura-channel") {
            try {
                val nodes = Tasks.await(
                    Wearable.getCapabilityClient(ctx).getCapability(RelayFrames.CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                ).nodes
                val node = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
                    ?: throw IOException("phone not reachable (Laura phone app installed?)")
                val ch = Tasks.await(cc.openChannel(node.id, RelayFrames.PATH))
                channel = ch
                val input = DataInputStream(Tasks.await(cc.getInputStream(ch)).buffered())
                out = DataOutputStream(Tasks.await(cc.getOutputStream(ch)).buffered())
                if (done.get()) {
                    runCatching { cc.close(ch) }
                    return@thread
                }
                listener.onOpen()
                while (!done.get()) {
                    val (type, payload) = RelayFrames.read(input) ?: break
                    when (type) {
                        RelayFrames.TEXT -> listener.onText(String(payload))
                        RelayFrames.BINARY -> listener.onBinary(payload)
                        RelayFrames.CLOSE -> {
                            val (code, reason) = RelayFrames.parseClose(payload)
                            return@thread finish(code, reason)
                        }
                    }
                }
                finish(Close.ABNORMAL, "relay ended")
            } catch (e: Exception) {
                finish(Close.ABNORMAL, e.message ?: e.javaClass.simpleName)
            }
        }
        return object : Transport {
            override fun send(text: String) = write(RelayFrames.TEXT, text.toByteArray())
            override fun send(bytes: ByteArray) = write(RelayFrames.BINARY, bytes)
            private fun write(type: Int, b: ByteArray): Boolean {
                val o = out ?: return false
                if (done.get()) return false
                return runCatching { RelayFrames.write(o, type, b) }.onFailure { finish(Close.ABNORMAL, "write failed") }.isSuccess
            }
            override fun close(code: Int) {
                out?.let { o -> runCatching { RelayFrames.write(o, RelayFrames.CLOSE, RelayFrames.closePayload(code, "")) } }
                finish(code, "client")
            }
        }
    }
}
