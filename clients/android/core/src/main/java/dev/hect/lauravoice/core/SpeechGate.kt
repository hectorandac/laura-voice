package dev.hect.lauravoice.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Hands-free only sends while someone is talking: an adaptive noise floor opens the gate, [preMs] of lead-in
 * goes out first, and it stays open for [hangMs] after the last loud frame so the server hears the pause
 * that ends the turn. Same rules as the web client.
 */
class SpeechGate(private val minRms: Double = 0.002, private val hangMs: Long = 1200, private val preMs: Int = 300) {
    var open = false
        private set
    private var floor = minRms
    private var lastLoud = Long.MIN_VALUE / 2
    private val pre = ArrayDeque<ShortArray>()

    @Synchronized
    fun push(pcm: ShortArray, rms: Double, nowMs: Long): List<ShortArray> {
        floor = max(0.0005, min(rms, floor * Math.pow(1.01, pcm.size / 1600.0)))
        if (rms > max(minRms, floor * 2.5)) lastLoud = nowMs
        val want = nowMs - lastLoud < hangMs
        if (want && !open) {
            open = true
            val out = pre.toList() + listOf(pcm)
            pre.clear()
            return out
        }
        if (!want && open) open = false
        if (open) return listOf(pcm)
        pre.addLast(pcm)
        while (pre.size > 1 && pre.sumOf { it.size } > preMs * MIC_RATE / 1000) pre.removeFirst()
        return emptyList()
    }

    @Synchronized
    fun reset() {
        open = false
        pre.clear()
    }

    companion object {
        fun rms(pcm: ShortArray, n: Int = pcm.size): Double {
            if (n == 0) return 0.0
            var sum = 0.0
            for (i in 0 until n) {
                val v = pcm[i] / 32768.0
                sum += v * v
            }
            return sqrt(sum / n)
        }
    }
}

object Backoff {
    fun delayMs(attempt: Int, jitter: Double = Math.random()): Long {
        val base = min(10_000.0, 500.0 * (1 shl min(attempt, 10)))
        return (base * (0.75 + jitter * 0.5)).toLong()
    }
}
