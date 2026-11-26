package dev.hect.lauravoice.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class OpusDeviceTest {
    @Test fun platformOpusRoundTrip() {
        assertTrue("no Opus encoder", Codecs.opusEncoder)
        val pcm = ShortArray(MIC_RATE) { (8000 * sin(2 * PI * 440 * it / MIC_RATE)).toInt().toShort() }
        val packets = mutableListOf<ByteArray>()
        OpusEncoder().use { enc -> for (i in 0 until 10) enc.encode(pcm.copyOfRange(i * 1600, (i + 1) * 1600)) { packets += it } }
        assertTrue("got ${packets.size} packets", packets.size >= 40)
        assertEquals(20_000L, OpusHead.packetUs(packets[5]))
        var samples = 0
        var rate = 0
        OpusDecoder(16000).use { dec ->
            for (p in packets) dec.decode(p) { s, r -> samples += s.size; rate = r }
            dec.finish { s, r -> samples += s.size; rate = r }
        }
        assertTrue("decoded $samples samples at $rate Hz", samples >= rate * 8 / 10)
    }
}

/**
 * Full engine against a real gateway, with a WAV file as the microphone and a capturing speaker:
 * adb push q.wav /data/local/tmp/q.wav
 * ./gradlew :core:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.url=wss://... \
 *   -Pandroid.testInstrumentationRunnerArguments.key=... -Pandroid.testInstrumentationRunnerArguments.pin=...
 */
@RunWith(AndroidJUnit4::class)
class LiveGatewayTest {
    private class FakeAudio(wav: ShortArray) : AudioDevices {
        val heard = mutableListOf<Pair<Int, Int>>()
        private val input = ShortArray(MIC_RATE / 2) + wav + ShortArray(MIC_RATE * 30)
        private var pos = 0
        override fun outputs() = emptySet<Int>()
        override fun openMic(route: Route) = object : Mic {
            override fun read(buf: ShortArray): Int {
                Thread.sleep(buf.size * 1000L / MIC_RATE)
                for (i in buf.indices) buf[i] = if (pos < input.size) input[pos++] else 0
                return buf.size
            }
            override fun close() = Unit
        }
        override fun openSpeaker(route: Route) = object : Speaker {
            override fun write(pcm: ShortArray, rate: Int) { synchronized(heard) { heard += pcm.size to rate } }
            override fun pendingMs() = 0L
            override fun stop() = Unit
            override fun close() = Unit
        }
        override fun enter(route: Route) = Unit
        override fun leave() = Unit
        override fun focus(on: Boolean, route: Route) = Unit
    }

    @Test fun oneTurn() {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("url")
        assumeTrue("pass -e url to run", url != null)
        val wav = File(args.getString("wav") ?: "/data/local/tmp/q.wav")
        val cfg = Config(url = url!!, key = args.getString("key") ?: "", pin = args.getString("pin") ?: "")
        val audio = FakeAudio(readWav(wav))
        val engine = VoiceEngine("android", audio, { cfg }, { WsConnector(it) })
        engine.activate()
        val deadline = System.currentTimeMillis() + 60_000
        var done = false
        while (System.currentTimeMillis() < deadline && !done) {
            Thread.sleep(250)
            val s = engine.ui.value
            val seconds = synchronized(audio.heard) { audio.heard.sumOf { it.first.toDouble() / it.second } }
            done = s.lines.any { it.role == "user" } && s.lines.any { it.role == "assistant" } && seconds > 1.0 &&
                s.view != View.SPEAKING
        }
        val s = engine.ui.value
        engine.deactivate()
        val seconds = synchronized(audio.heard) { audio.heard.sumOf { it.first.toDouble() / it.second } }
        android.util.Log.i("LiveGatewayTest", "lines=${s.lines} audio=${"%.1f".format(seconds)} s stats=${s.stats}")
        assertTrue("no full turn: ${s.lines} conn=${s.conn} ${s.error}", done)
    }

    private fun readWav(f: File): ShortArray {
        val b = RandomAccessFile(f, "r").use { r -> ByteArray(r.length().toInt()).also { r.readFully(it) } }
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var p = 12
        while (p + 8 <= b.size) {
            val id = String(b, p, 4)
            val n = bb.getInt(p + 4)
            if (id == "data") {
                val out = ShortArray(n / 2)
                bb.position(p + 8)
                bb.asShortBuffer().get(out)
                return out
            }
            p += 8 + n
        }
        error("no data chunk in $f")
    }
}
