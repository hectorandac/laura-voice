package dev.hect.lauravoice.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer

/**
 * Watch <-> phone tunnel over a Data Layer channel: the watch speaks the normal protocol and the phone relays
 * each WebSocket message as [type:u8][length:u32 BE][payload].
 */
object RelayFrames {
    const val PATH = "/laura/ws"
    const val CAPABILITY = "laura_voice_relay"
    const val CONFIG_PATH = "/laura/config"

    const val TEXT = 1
    const val BINARY = 2
    /** payload: close code u16 + UTF-8 reason. */
    const val CLOSE = 8

    private const val MAX = 1 shl 20

    fun write(out: DataOutputStream, type: Int, payload: ByteArray) {
        synchronized(out) {
            out.writeByte(type)
            out.writeInt(payload.size)
            out.write(payload)
            out.flush()
        }
    }

    /** Returns null on a clean end of stream. */
    fun read(input: DataInputStream): Pair<Int, ByteArray>? {
        val type = try {
            input.readUnsignedByte()
        } catch (_: EOFException) {
            return null
        }
        val n = input.readInt()
        require(n in 0..MAX) { "bad relay frame length $n" }
        val b = ByteArray(n)
        input.readFully(b)
        return type to b
    }

    fun closePayload(code: Int, reason: String): ByteArray {
        val r = reason.take(100).toByteArray()
        return ByteBuffer.allocate(2 + r.size).putShort(code.toShort()).put(r).array()
    }

    fun parseClose(b: ByteArray): Pair<Int, String> {
        if (b.size < 2) return Close.ABNORMAL to ""
        val bb = ByteBuffer.wrap(b)
        return (bb.short.toInt() and 0xffff) to String(b, 2, b.size - 2)
    }
}
