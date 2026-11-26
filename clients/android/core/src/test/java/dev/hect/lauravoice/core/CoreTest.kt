package dev.hect.lauravoice.core

import android.media.AudioDeviceInfo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class ConfigTest {
    @Test fun parsesScannedUrl() {
        val c = Config.parse("wss://10.0.0.5:8443/ws?k=s3cr%2Bt&pin=SHA256/AB:CD")!!
        assertEquals("wss://10.0.0.5:8443/ws", c.url)
        assertEquals("s3cr+t", c.key)
        assertEquals("abcd", c.pin)
    }

    @Test fun wsUrlAddsPathAndKeyButNotPin() {
        val c = Config(url = "https://host:8443", key = "a b", pin = "ff")
        assertEquals("wss://host:8443/ws?k=a+b", c.wsUrl())
        assertEquals("ws://h/ws", Config(url = "ws://h/").wsUrl())
    }

    @Test fun rejectsGarbage() {
        assertNull(Config.parse("hello world"))
        assertNull(Config.parse("ftp://x/y"))
    }

    @Test fun carContext() {
        assertEquals(mapOf("car" to true), Config(car = true).context())
        assertTrue(Config().context().isEmpty())
    }
}

class ProtocolTest {
    @Test fun hello() {
        val o = JSONObject(Protocol.hello("android", Config(lang = "ko", mode = "ptt"), "opus", listOf("opus", "pcm"), mapOf("car" to true)))
        assertEquals("hello", o.getString("type"))
        assertEquals(1, o.getInt("version"))
        assertEquals("android", o.getString("client"))
        assertEquals("ko", o.getString("lang"))
        assertEquals("ptt", o.getString("mode"))
        assertTrue(o.getJSONObject("context").getBoolean("car"))
        assertEquals("opus", o.getJSONObject("audio").getString("up"))
        assertEquals("pcm", o.getJSONObject("audio").getJSONArray("down").getString(1))
    }

    @Test fun parsesServerMessages() {
        assertEquals(ServerMsg.State("thinking"), ServerMsg.parse("""{"type":"state","value":"thinking"}"""))
        assertEquals(
            ServerMsg.AudioStart("t1", 24000, "opus"),
            ServerMsg.parse("""{"type":"audio_start","turn_id":"t1","sample_rate":24000,"codec":"opus"}""")
        )
        assertEquals(ServerMsg.AudioStart("t1", 16000, "pcm"), ServerMsg.parse("""{"type":"audio_start","turn_id":"t1","sample_rate":16000}"""))
        assertEquals(ServerMsg.UserTurn("t", "안녕", "ko"), ServerMsg.parse("""{"type":"user_turn","turn_id":"t","text":"안녕","lang":"ko"}"""))
        val m = ServerMsg.parse("""{"type":"metrics","turn_id":"t","asr_ms":40,"llm_first_ms":500,"tts_first_ms":null}""") as ServerMsg.Metrics
        assertEquals(40L, m.asrMs)
        assertNull(m.ttsFirstMs)
        assertTrue(ServerMsg.parse("""{"type":"something_new","id":1}""") is ServerMsg.Unknown)
        assertNull(ServerMsg.parse("not json"))
    }

    @Test fun parsesGateAndTaskMessages() {
        assertEquals(ServerMsg.Interrupted("t9"), ServerMsg.parse("""{"type":"interrupted","turn_id":"t9","reason":"barge_in"}"""))
        assertEquals(ServerMsg.Ignored("t", "not_addressed", "hi"), ServerMsg.parse("""{"type":"ignored","turn_id":"t","reason":"not_addressed","text":"hi"}"""))
        assertEquals(ServerMsg.LauraMessage(null, "x"), ServerMsg.parse("""{"type":"laura_message","task_id":null,"text":"x","final":true}"""))
        val a = ServerMsg.parse(
            """{"type":"approval_request","approval_id":"a1","task_id":"k","text":"t","command":"rm x","description":"d","choices":["once","deny"]}"""
        ) as ServerMsg.ApprovalRequest
        assertEquals(listOf("once", "deny"), a.choices)
        val r = JSONObject(Protocol.approval("a1", "once"))
        assertEquals("approval_response", r.getString("type"))
        assertEquals("a1", r.getString("approval_id"))
        assertEquals(true, JSONObject(Protocol.bool("mute", true)).getBoolean("value"))
        val rc = JSONObject(Protocol.recall("t7"))
        assertEquals("recall", rc.getString("type"))
        assertEquals("t7", rc.getString("turn_id"))
    }

    @Test fun statsNulls() {
        val o = JSONObject(Protocol.stats(mapOf("rtt_ms" to null, "up_kbps" to 23L)))
        assertTrue(o.isNull("rtt_ms"))
        assertEquals(23, o.getInt("up_kbps"))
    }
}

class GateTest {
    private fun frame(amp: Double) = ShortArray(FRAME_SAMPLES) { (amp * 32767 * Math.sin(it / 5.0)).toInt().toShort() }

    @Test fun leadInAndHang() {
        val g = SpeechGate()
        val quiet = frame(0.0005)
        val loud = frame(0.1)
        val ms = FRAME_SAMPLES * 1000L / MIC_RATE
        var t = 0L
        repeat(50) {
            assertTrue(g.push(quiet, SpeechGate.rms(quiet), t).isEmpty())
            t += ms
        }
        assertEquals((300 / ms + 1).toInt(), g.push(loud, SpeechGate.rms(loud), t).size)
        var sent = 0
        repeat(100) {
            t += ms
            sent += g.push(quiet, SpeechGate.rms(quiet), t).size
        }
        assertTrue("sent $sent", sent.toLong() in (1200 / ms - 1)..(1200 / ms + 1))
        assertFalse(g.open)
    }

    @Test fun backoffCaps() {
        assertEquals(500L, Backoff.delayMs(0, 0.5))
        assertEquals(10_000L, Backoff.delayMs(20, 0.5))
    }
}

class RoutingTest {
    @Test fun autoRules() {
        val speaker = setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        assertEquals(RouteDecision(Route.CALL, true, "loudspeaker"), Routing.decide("auto", false, speaker))
        assertEquals(Route.MEDIA, Routing.decide("auto", true, speaker).route)
        val bt = Routing.decide("auto", false, speaker + AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        assertEquals(Route.MEDIA, bt.route)
        assertFalse(bt.fullDuplex)
        assertTrue(Routing.decide("auto", false, speaker + AudioDeviceInfo.TYPE_WIRED_HEADSET).fullDuplex)
        assertEquals(Route.CALL, Routing.decide("call", true, speaker).route)
        assertFalse(Routing.decide("call", false, speaker, watch = true).fullDuplex)
    }
}

class RelayFramesTest {
    @Test fun roundTrip() {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        RelayFrames.write(out, RelayFrames.TEXT, "{\"type\":\"hello\"}".toByteArray())
        RelayFrames.write(out, RelayFrames.BINARY, byteArrayOf(1, 2, 3))
        RelayFrames.write(out, RelayFrames.CLOSE, RelayFrames.closePayload(4000, "taken"))
        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(RelayFrames.TEXT, RelayFrames.read(input)!!.first)
        assertEquals(3, RelayFrames.read(input)!!.second.size)
        val (type, payload) = RelayFrames.read(input)!!
        assertEquals(RelayFrames.CLOSE, type)
        assertEquals(4000 to "taken", RelayFrames.parseClose(payload))
        assertNull(RelayFrames.read(input))
    }
}

class OpusHeadTest {
    @Test fun header() {
        val h = OpusHead.build(1, 312, 24000)
        assertEquals("OpusHead", String(h, 0, 8))
        assertEquals(19, h.size)
        assertEquals(1, h[9].toInt())
        assertEquals(312, (h[10].toInt() and 0xff) or (h[11].toInt() shl 8))
    }

    @Test fun packetDuration() {
        // CELT FB 20 ms, one frame (config 31, code 0) and SILK WB 20 ms two frames (config 9, code 1).
        assertEquals(20_000L, OpusHead.packetUs(byteArrayOf((31 shl 3).toByte())))
        assertEquals(40_000L, OpusHead.packetUs(byteArrayOf(((9 shl 3) or 1).toByte())))
    }
}

class SourceTest {
    @Test fun sourceLabels() {
        assertEquals("Local · Bonsai 9B", Source("local", "bonsai-9b").label)
        assertEquals("Claude Sonnet 5.5 · via Hermes", Source("hermes", "claude-sonnet-5-5", "task").label)
        val m = ServerMsg.parse("""{"type":"assistant_delta","turn_id":"t","text":"hi","source":{"engine":"hermes","model":"","lane":"task"}}""")
        assertEquals(ServerMsg.AssistantDelta("t", "hi", Source("hermes", "", "task")), m)
        val w = JSONObject(Protocol.wake(0.87312f))
        assertEquals("wake", w.getString("type"))
        assertEquals(0.873, w.getDouble("score"), 1e-9)
    }
}
