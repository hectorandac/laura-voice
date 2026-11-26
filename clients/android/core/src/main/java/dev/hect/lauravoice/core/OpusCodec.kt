package dev.hect.lauravoice.core

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Raw Opus packets (no Ogg) through the platform codecs: c2.android.opus.encoder ships with Android 10+
 * and the decoder with 5+, so no native library is needed. Callers fall back to PCM16 if creation fails.
 */
object OpusHead {
    /** RFC 7845 identification header, passed to the decoder as csd-0. */
    fun build(channels: Int = 1, preSkip: Int = 0, inputRate: Int = 48000): ByteArray =
        ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusHead".toByteArray(Charsets.US_ASCII))
            .put(1).put(channels.toByte()).putShort(preSkip.toShort()).putInt(inputRate).putShort(0).put(0)
            .array()

    fun nanos(ns: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(ns).array()

    /** Packet duration in microseconds from the TOC byte (RFC 6716 §3.1). */
    fun packetUs(pkt: ByteArray): Long {
        if (pkt.isEmpty()) return 0
        val toc = pkt[0].toInt() and 0xff
        val config = toc shr 3
        val frameUs = when {
            config < 12 -> longArrayOf(10_000, 20_000, 40_000, 60_000)[config and 3]
            config < 16 -> longArrayOf(10_000, 20_000)[config and 1]
            else -> longArrayOf(2_500, 5_000, 10_000, 20_000)[config and 3]
        }
        val frames = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> if (pkt.size > 1) pkt[1].toInt() and 0x3f else 1
        }
        return frameUs * frames
    }
}

class OpusEncoder(private val rate: Int = 16000, bitrate: Int = 24000) : AutoCloseable {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var ptsUs = 0L

    init {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, rate, 1)
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16000)
        try {
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    fun encode(pcm: ShortArray, n: Int = pcm.size, emit: (ByteArray) -> Unit) {
        var off = 0
        while (off < n) {
            val i = codec.dequeueInputBuffer(20_000)
            if (i < 0) {
                drain(emit)
                continue
            }
            val buf = codec.getInputBuffer(i)!!
            buf.clear()
            val count = minOf(n - off, buf.capacity() / 2)
            buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, off, count)
            codec.queueInputBuffer(i, 0, count * 2, ptsUs, 0)
            ptsUs += count * 1_000_000L / rate
            off += count
            drain(emit)
        }
        drain(emit)
    }

    private fun drain(emit: (ByteArray) -> Unit) {
        while (true) {
            val o = codec.dequeueOutputBuffer(info, 0)
            if (o < 0) {
                if (o == MediaCodec.INFO_TRY_AGAIN_LATER) return
                continue
            }
            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                val b = codec.getOutputBuffer(o)!!
                b.position(info.offset).limit(info.offset + info.size)
                val pkt = ByteArray(info.size).also { b.get(it) }
                if (!isHeader(pkt)) emit(pkt)
            }
            codec.releaseOutputBuffer(o, false)
        }
    }

    private fun isHeader(p: ByteArray) =
        p.size >= 8 && (String(p, 0, 8, Charsets.US_ASCII) == "OpusHead" || String(p, 0, 8, Charsets.US_ASCII) == "AOPUSHDR")

    override fun close() {
        runCatching { codec.stop() }
        codec.release()
    }
}

class OpusDecoder(rate: Int) : AutoCloseable {
    private val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var ptsUs = 0L
    var outputRate = 48000
        private set

    init {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, rate, 1)
        f.setByteBuffer("csd-0", ByteBuffer.wrap(OpusHead.build(1, 0, rate)))
        f.setByteBuffer("csd-1", ByteBuffer.wrap(OpusHead.nanos(0)))
        f.setByteBuffer("csd-2", ByteBuffer.wrap(OpusHead.nanos(80_000_000)))
        try {
            codec.configure(f, null, null, 0)
            codec.start()
            outputRate = runCatching { codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(48000)
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    fun decode(pkt: ByteArray, emit: (ShortArray, Int) -> Unit) {
        var i = codec.dequeueInputBuffer(20_000)
        while (i < 0) {
            drain(emit, 0)
            i = codec.dequeueInputBuffer(20_000)
        }
        val buf = codec.getInputBuffer(i)!!
        buf.clear()
        buf.put(pkt)
        codec.queueInputBuffer(i, 0, pkt.size, ptsUs, 0)
        ptsUs += OpusHead.packetUs(pkt).coerceAtLeast(2_500)
        drain(emit, 0)
    }

    /** Pushes end-of-stream and returns whatever audio the codec still holds. */
    fun finish(emit: (ShortArray, Int) -> Unit) {
        val i = codec.dequeueInputBuffer(20_000)
        if (i >= 0) codec.queueInputBuffer(i, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(emit, 20_000)
    }

    private fun drain(emit: (ShortArray, Int) -> Unit, timeoutUs: Long) {
        while (true) {
            val o = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    outputRate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                o >= 0 -> {
                    if (info.size > 0) {
                        val b = codec.getOutputBuffer(o)!!
                        b.position(info.offset).limit(info.offset + info.size)
                        val s = ShortArray(info.size / 2)
                        b.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s)
                        emit(s, outputRate)
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(o, false)
                    if (eos) return
                }
            }
        }
    }

    override fun close() {
        runCatching { codec.stop() }
        codec.release()
    }
}
