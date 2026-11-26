package dev.hect.lauravoice.core

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.LoudnessEnhancer
import kotlin.math.pow
import android.os.Build

const val MIC_RATE = 16000
const val FRAME_SAMPLES = 320

interface Mic {
    /** Blocks until a full 20 ms frame is read; returns samples read or < 0 on error. */
    fun read(buf: ShortArray): Int
    fun close()
}

interface Speaker {
    /** Player thread only; blocks while the device buffer is full, returns early after [halt]. */
    fun write(pcm: ShortArray, rate: Int)
    /** Milliseconds of audio written but not yet heard. */
    fun pendingMs(): Long
    /** Frames written so far, and frames actually played out of the speaker (net of output latency). */
    fun writtenFrames(): Long = 0
    fun playedFrames(): Long = 0
    fun rate(): Int = 0
    /** Any thread: linear volume 0..1. */
    fun setGain(g: Float) {}
    /** Any thread: stop sound now and discard buffered audio; the player thread then [close]s it. */
    fun halt() {}
    fun stop()
    fun close()
}

interface AudioDevices {
    fun outputs(): Set<Int>
    fun openMic(route: Route): Mic
    fun openSpeaker(route: Route): Speaker
    fun enter(route: Route)
    fun leave()
    fun focus(on: Boolean, route: Route)
    /** Called when another app's focus request means the reply should stop (a phone call). */
    fun onInterrupted(cb: () -> Unit) {}
}

/**
 * [mediaUsage]: play replies as USAGE_MEDIA (music stream) instead of USAGE_ASSISTANT; on the watch the music
 * stream is the loud one and the one the side buttons/bezel control. [gainDb]: extra reply gain (negative =
 * attenuate with the track volume, positive = LoudnessEnhancer, which also limits peaks). [carMedia]: also use
 * USAGE_MEDIA while it returns true (Android Auto projects the media usage to the car's media channel).
 * [telecomCall]: a Telecom call of ours is up; Telecom sets the audio mode, the communication device and the call's
 * audio focus, so the CALL route only picks the voice-communication source and usage and leaves those alone (the
 * Core-Telecom docs say not to call setCommunicationDevice/startBluetoothSco alongside it).
 */
class AndroidAudio(
    private val context: Context,
    private val mediaUsage: Boolean = false,
    private val gainDb: () -> Float = { 0f },
    private val carMedia: () -> Boolean = { false },
    private val telecomCall: () -> Boolean = { false },
) : AudioDevices {
    private val playUsage get() = if (mediaUsage || carMedia()) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ASSISTANT
    private val am = context.getSystemService(AudioManager::class.java)
    private var focusRequest: AudioFocusRequest? = null
    @Volatile private var focusGain = 1f
    @Volatile private var live: TrackSpeaker? = null
    @Volatile private var interrupted: (() -> Unit)? = null

    override fun onInterrupted(cb: () -> Unit) { interrupted = cb }

    private fun setFocusGain(g: Float) {
        focusGain = g
        live?.refreshVolume()
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (val a = FocusPolicy.decide(change, FocusPolicy.inCall(am.mode))) {
            is FocusPolicy.Action.Volume -> setFocusGain(a.gain)
            FocusPolicy.Action.Stop -> {
                setFocusGain(1f)
                interrupted?.invoke()
            }
        }
    }

    override fun outputs(): Set<Int> = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }.toSet()

    @SuppressLint("MissingPermission")
    override fun openMic(route: Route): Mic {
        val source = if (route == Route.CALL) MediaRecorder.AudioSource.VOICE_COMMUNICATION
        else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val min = AudioRecord.getMinBufferSize(MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(MIC_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            )
            .setBufferSizeInBytes(maxOf(min, MIC_RATE / 10 * 2 * 4))
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("microphone unavailable")
        }
        val aec = if (route == Route.CALL && AcousticEchoCanceler.isAvailable())
            runCatching { AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        rec.startRecording()
        return object : Mic {
            override fun read(buf: ShortArray): Int {
                var off = 0
                while (off < buf.size) {
                    val n = rec.read(buf, off, buf.size - off)
                    if (n < 0) return n
                    off += n
                }
                return off
            }

            override fun close() {
                runCatching { rec.stop() }
                aec?.release()
                rec.release()
            }
        }
    }

    override fun openSpeaker(route: Route): Speaker = TrackSpeaker(
        { if (route == Route.CALL) AudioAttributes.USAGE_VOICE_COMMUNICATION else playUsage }, gainDb, { focusGain }
    ).also { live = it }

    override fun enter(route: Route) {
        if (telecomCall()) return
        if (route != Route.CALL) {
            leave()
            return
        }
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        val outs = outputs()
        val private = outs.any {
            it == AudioDeviceInfo.TYPE_WIRED_HEADSET || it == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it == AudioDeviceInfo.TYPE_USB_HEADSET || it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val dev = am.availableCommunicationDevices.firstOrNull {
                if (private) it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE && it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                else it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
            if (dev != null) am.setCommunicationDevice(dev)
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = !private
        }
    }

    override fun leave() {
        if (telecomCall() || am.mode != AudioManager.MODE_IN_COMMUNICATION) return
        if (Build.VERSION.SDK_INT >= 31) {
            am.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
        }
        am.mode = AudioManager.MODE_NORMAL
    }

    override fun focus(on: Boolean, route: Route) {
        // In a Telecom call the call holds focus for the whole call (every other request is refused meanwhile).
        if (on && telecomCall()) return
        if (on && focusRequest == null) {
            // Transient: music ducks and spoken apps (podcasts) pause only while she talks, and get it back after.
            // willPauseWhenDucked: the system calls focusListener instead of ducking her itself (it never auto-ducks
            // CONTENT_TYPE_SPEECH anyway), so navigation guidance makes her quieter rather than paused.
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs(if (route == Route.CALL) AudioAttributes.USAGE_VOICE_COMMUNICATION else playUsage))
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            setFocusGain(1f)
            am.requestAudioFocus(req)
            focusRequest = req
        } else if (!on) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
            setFocusGain(1f)
        }
    }

    companion object {
        fun attrs(usage: Int): AudioAttributes =
            AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    }
}

class TrackSpeaker(
    private val usage: () -> Int,
    private val gainDb: () -> Float = { 0f },
    private val focusGain: () -> Float = { 1f },
) : Speaker {
    @Volatile private var track: AudioTrack? = null
    private var trackUsage = 0
    @Volatile private var rate = 0
    @Volatile private var written = 0L
    @Volatile private var gain = 1f
    @Volatile private var base = 1f
    private var enhancer: LoudnessEnhancer? = null
    private var appliedDb = Float.NaN
    private val ts = AudioTimestamp()

    /** The user's volume setting: attenuation via the track volume, boost via a LoudnessEnhancer. */
    private fun applyGain(t: AudioTrack) {
        val db = gainDb()
        if (db == appliedDb) return
        appliedDb = db
        base = if (db < 0) 10f.pow(db / 20) else 1f
        t.setVolume(base * gain * focusGain())
        if (db > 0) {
            val e = enhancer ?: runCatching { LoudnessEnhancer(t.audioSessionId) }.getOrNull()?.also { enhancer = it }
            e?.setTargetGain((db * 100).toInt())
            e?.enabled = true
        } else {
            enhancer?.enabled = false
        }
    }

    override fun write(pcm: ShortArray, rate: Int) {
        var t = track
        val u = usage()
        if (t == null || rate != this.rate || (u != trackUsage && pendingMs() == 0L)) {
            t?.release()
            enhancer?.release()
            enhancer = null
            appliedDb = Float.NaN
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            t = AudioTrack.Builder()
                .setAudioAttributes(AndroidAudio.attrs(u))
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                )
                .setBufferSizeInBytes(maxOf(min, rate / 5 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (Build.VERSION.SDK_INT >= 31) runCatching { t.setStartThresholdInFrames(rate / 10) }
            t.setVolume(base * gain * focusGain())
            t.play()
            this.rate = rate
            trackUsage = u
            written = 0
            track = t
        }
        applyGain(t)
        val n = t.write(pcm, 0, pcm.size)
        if (n > 0) written += n
    }

    override fun writtenFrames() = written
    override fun rate() = rate

    override fun playedFrames(): Long {
        val t = track ?: return 0
        val head = t.playbackHeadPosition.toLong() and 0xffffffffL
        val r = rate.takeIf { it > 0 } ?: return head
        val fromTs = synchronized(ts) {
            if (runCatching { t.getTimestamp(ts) }.getOrDefault(false))
                ts.framePosition + (System.nanoTime() - ts.nanoTime) * r / 1_000_000_000L else null
        }
        // The timestamp extrapolates; the head stops during underruns (gaps between sentences).
        return minOf(fromTs ?: head, head, written).coerceAtLeast(0)
    }

    override fun pendingMs(): Long {
        val r = rate.takeIf { it > 0 } ?: return 0
        return (written - playedFrames()).coerceAtLeast(0) * 1000 / r
    }

    override fun setGain(g: Float) {
        gain = g
        refreshVolume()
    }

    fun refreshVolume() {
        runCatching { track?.setVolume(base * gain * focusGain()) }
    }

    override fun halt() {
        runCatching { track?.let { it.pause(); it.flush() } }
    }

    override fun stop() {
        val t = track ?: return
        track = null
        runCatching { t.pause(); t.flush() }
        t.release()
        enhancer?.release()
        enhancer = null
        gain = 1f
    }

    override fun close() = stop()
}
