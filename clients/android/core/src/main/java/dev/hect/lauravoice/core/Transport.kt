package dev.hect.lauravoice.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

interface Transport {
    fun send(text: String): Boolean
    fun send(bytes: ByteArray): Boolean
    fun close(code: Int = Close.NORMAL)
}

interface TransportListener {
    fun onOpen()
    fun onText(text: String)
    fun onBinary(bytes: ByteArray)
    /** Called once. Connection failures map to [Close] codes (403 -> BAD_KEY, TLS -> CERT, else ABNORMAL). */
    fun onClosed(code: Int, reason: String)
}

fun interface Connector {
    fun connect(listener: TransportListener): Transport
}

object Tls {
    fun sha256(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

    /** Trusts exactly the certificate whose SHA-256 matches [pin]; nothing else, no CA involved. */
    class PinnedTrustManager(pin: String) : X509TrustManager {
        private val want = Config.normPin(pin)
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException()
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val got = chain.firstOrNull()?.let(::sha256)
            if (got != want) throw CertificateException("certificate pin mismatch: server sha256 $got")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun client(base: OkHttpClient, pin: String): OkHttpClient {
        if (pin.isBlank()) return base
        val tm = PinnedTrustManager(pin)
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
        // The pin identifies the server exactly, so the hostname check adds nothing (the gateway is reached by IP).
        return base.newBuilder().sslSocketFactory(ctx.socketFactory, tm).hostnameVerifier { _, _ -> true }.build()
    }

    /** Reads the server certificate without trusting it, so the user can compare and pin it (trust on first use). */
    fun probe(url: String): X509Certificate {
        val u = URI(url.trim())
        val port = if (u.port > 0) u.port else 443
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        val raw = java.net.Socket()
        raw.connect(InetSocketAddress(u.host, port), 8000)
        (ctx.socketFactory.createSocket(raw, u.host, port, true) as SSLSocket).use { s ->
            s.soTimeout = 8000
            s.startHandshake()
            return s.session.peerCertificates[0] as X509Certificate
        }
    }
}

class WsConnector(private val config: Config, base: OkHttpClient = shared) : Connector {
    private val client = Tls.client(base, config.pin)

    override fun connect(listener: TransportListener): Transport {
        val done = AtomicBoolean(false)
        fun closed(code: Int, reason: String) {
            if (done.compareAndSet(false, true)) listener.onClosed(code, reason)
        }
        val ws = client.newWebSocket(Request.Builder().url(config.wsUrl()).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
            override fun onMessage(webSocket: WebSocket, text: String) = listener.onText(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onBinary(bytes.toByteArray())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(Close.NORMAL, null)
                closed(code, reason)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed(code, reason)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = when {
                    response?.code == 403 || response?.code == 401 -> Close.BAD_KEY
                    t is SSLException || t.cause is CertificateException -> Close.CERT
                    else -> Close.ABNORMAL
                }
                closed(code, t.message ?: t.javaClass.simpleName)
            }
        })
        return object : Transport {
            override fun send(text: String) = ws.send(text)
            override fun send(bytes: ByteArray) = ws.send(bytes.toByteString())
            override fun close(code: Int) {
                ws.close(code, null)
                closed(code, "client")
            }
        }
    }

    companion object {
        val shared: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .build()
        }
    }
}
