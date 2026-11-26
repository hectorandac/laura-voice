package dev.hect.lauravoice.core

import android.content.Context
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

data class Config(
    val url: String = "",
    val key: String = "",
    val pin: String = "",
    val lang: String = "auto",
    val mode: String = "handsfree",
    val car: Boolean = false,
    val route: String = "auto",
    val codec: String = "opus",
    val transport: String = "phone",
    /** Watch reply volume, 0..1; 0.5 = unity gain, 1 = +12 dB through the loudness enhancer. */
    val watchVolume: Float = 0.75f,
) {
    val watchGainDb: Float get() = (watchVolume - 0.5f) * 24f

    val configured: Boolean get() = url.isNotBlank()

    /** Gateway WebSocket URL with the access key; the pin never leaves the device. */
    fun wsUrl(): String {
        val u = URI(url.trim())
        val scheme = when (u.scheme?.lowercase()) {
            "https", "wss" -> "wss"
            "http", "ws" -> "ws"
            else -> throw IllegalArgumentException("unsupported scheme in $url")
        }
        val path = if (u.rawPath.isNullOrEmpty() || u.rawPath == "/") "/ws" else u.rawPath
        val params = query(u.rawQuery).filterKeys { it != "k" && it != "pin" }.toMutableMap()
        if (key.isNotEmpty()) params["k"] = key
        val q = params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val port = if (u.port > 0) ":${u.port}" else ""
        return "$scheme://${u.host}$port$path" + if (q.isEmpty()) "" else "?$q"
    }

    fun context(): Map<String, Any> = if (car) mapOf("car" to true) else emptyMap()

    companion object {
        /** Accepts a pasted or scanned gateway URL: wss://host:8443/ws?k=KEY&pin=SHA256. */
        fun parse(text: String, base: Config = Config()): Config? {
            val t = text.trim()
            val u = runCatching { URI(t) }.getOrNull() ?: return null
            if (u.scheme?.lowercase() !in setOf("wss", "ws", "https", "http") || u.host.isNullOrEmpty()) return null
            val q = query(u.rawQuery) + query(u.rawFragment)
            val port = if (u.port > 0) ":${u.port}" else ""
            val path = if (u.rawPath.isNullOrEmpty()) "" else u.rawPath
            return base.copy(
                url = "${u.scheme.lowercase()}://${u.host}$port$path",
                key = q["k"] ?: base.key,
                pin = q["pin"]?.let(::normPin) ?: base.pin,
                lang = q["lang"] ?: base.lang,
            )
        }

        fun normPin(pin: String): String =
            pin.trim().lowercase().removePrefix("sha256/").replace(":", "").replace(" ", "")

        private fun query(raw: String?): Map<String, String> =
            raw.orEmpty().split("&").filter { it.isNotEmpty() }.associate {
                val i = it.indexOf('=')
                if (i < 0) dec(it) to "" else dec(it.substring(0, i)) to dec(it.substring(i + 1))
            }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")
    }
}

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("laura", Context.MODE_PRIVATE)

    /** Where car mode comes from when set (the phone's car detector); otherwise the old "car" preference. */
    @Volatile var carSource: (() -> Boolean)? = null

    fun load() = Config(
        url = prefs.getString("url", "")!!,
        key = prefs.getString("key", "")!!,
        pin = prefs.getString("pin", "")!!,
        lang = prefs.getString("lang", "auto")!!,
        mode = prefs.getString("mode", "handsfree")!!,
        car = carSource?.invoke() ?: prefs.getBoolean("car", false),
        route = prefs.getString("route", "auto")!!,
        codec = prefs.getString("codec", "opus")!!,
        transport = prefs.getString("transport", "phone")!!,
        watchVolume = prefs.getFloat("watch_volume", 0.75f),
    )

    fun save(c: Config) {
        prefs.edit()
            .putString("url", c.url).putString("key", c.key).putString("pin", c.pin)
            .putString("lang", c.lang).putString("mode", c.mode).putBoolean("car", c.car)
            .putString("route", c.route).putString("codec", c.codec).putString("transport", c.transport)
            .putFloat("watch_volume", c.watchVolume)
            .apply()
    }
}
