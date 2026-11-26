package dev.hect.lauravoice.core

import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Collections
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.math.log10

enum class Conn { NOT_CONFIGURED, OFFLINE, CONNECTING, ONLINE, ELSEWHERE, BAD_KEY, CERT }
enum class View { IDLE, LISTENING, THINKING, SPEAKING, OFFLINE }

data class Line(
    val role: String, val text: String, val turnId: String? = null, val cut: Boolean = false, val time: Long = 0L,
    val source: Source? = null,
) {
    /** An utterance the addressee gate ignored that can still be handed to Laura (about a minute). */
    fun recallable(now: Long = SystemClock.elapsedRealtime()) = role == "ignored" && !turnId.isNullOrEmpty() && now - time < RECALL_MS
}

const val RECALL_MS = 60_000L

data class UiState(
    val active: Boolean = false,
    val conn: Conn = Conn.OFFLINE,
    val view: View = View.OFFLINE,
    val partial: String = "",
    val lines: List<Line> = emptyList(),
    val stats: String = "",
    val level: Float = 0f,
    val muted: Boolean = false,
    val holding: Boolean = false,
    val route: String = "",
    val error: String = "",
    val serverMuted: Boolean = false,
    val lauraConnected: Boolean? = null,
    val profile: String = "",
    val enroll: Enroll? = null,
    val approvals: List<ServerMsg.ApprovalRequest> = emptyList(),
    val lastIgnored: Line? = null,
    /** The conversation is on this user's watch (relayed through this phone). */
    val onWatch: Boolean = false,
    /** Listening for an addressed turn after an invocation. */
    val awake: Boolean = false,
    /** Who is answering right now (set while she speaks). */
    val speakingSource: Source? = null,
)

/** One-shot cues for the avatar: kind is "state" (transient), "reaction" or "emotion". */
data class AvatarEvent(val kind: String, val value: String)

data class Enroll(val index: Int, val total: Int, val text: String, val status: String, val done: Boolean = false)

/** Lets the phone's own session step aside while it relays a watch session, then come back. */
object RelayBus {
    @Volatile var active = false
        private set
    @Volatile var onEnd: (() -> Unit)? = null

    fun set(on: Boolean) {
        active = on
        if (!on) onEnd?.invoke()
    }
}

object Codecs {
    private fun has(encoder: Boolean, rate: Int): Boolean = runCatching {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, rate, 1)
        val l = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        (if (encoder) l.findEncoderForFormat(f) else l.findDecoderForFormat(f)) != null
    }.getOrDefault(false)

    val opusEncoder by lazy { has(true, MIC_RATE) }
    val opusDecoder by lazy { has(false, 24000) }
}

/**
 * The client: microphone -> speech gate -> Opus/PCM -> gateway, gateway -> decoder -> speaker, plus barge-in,
 * reconnects and stats. Control runs on one handler thread; capture and playback have their own threads.
 */
class VoiceEngine(
    private val clientName: String,
    private val audio: AudioDevices,
    private val config: () -> Config,
    private val connector: (Config) -> Connector,
    private val watch: Boolean = false,
) {
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private val _avatar = MutableSharedFlow<AvatarEvent>(extraBufferCapacity = 16)
    val avatarEvents: SharedFlow<AvatarEvent> = _avatar

    /** Loudness (0..1) of the reply audio being heard right now, for the avatar's mouth. */
    fun ttsLevel(): Float = player.levelNow()

    private val handler = Handler(HandlerThread("laura-engine").apply { start() }.looper)
    @Volatile private var cfg = config()
    @Volatile private var transport: Transport? = null
    @Volatile private var serial = 0
    @Volatile private var online = false
    @Volatile var active = false
        private set
    @Volatile private var muted = false
    @Volatile private var holding = false
    @Volatile private var serverState = "idle"
    @Volatile private var upCodec = "pcm"
    @Volatile private var decision = RouteDecision(Route.MEDIA, false, "")
    private var retry = 0
    private var waitingForRelay = false

    private val gate = SpeechGate()
    @Volatile private var replySource: Source? = null
    private var preroll: ShortArray? = null
    private var quietUntil = 0L
    /** Invocation (side key, assist gesture, shortcut): listening for an addressed turn until then. */
    @Volatile private var summonUntil = 0L
    /** Send a `wake` with the first audio after an invocation, so that turn counts as addressed. */
    @Volatile private var summonWake = false
    /** Push-to-talk mode: an invocation holds the turn open until the speaker pauses. */
    @Volatile private var summonPtt = false
    @Volatile private var summonSpoke = false
    private var capture: Capture? = null
    private val player = Player().apply { start() }

    private val net = Stats()

    init {
        handler.postDelayed(::tick, 1000)
        audio.onInterrupted { stopReply() }
    }

    // ---- commands (any thread) ----

    fun activate() = handler.post {
        Log.i(TAG, "activate")
        if (active) return@post
        active = true
        muted = false
        cfg = config()
        reroute(force = true)
        connect()
        render()
    }

    fun deactivate(reason: String) = handler.post {
        Log.i(TAG, "deactivate: $reason")
        if (!active) return@post
        active = false
        holding = false
        summonUntil = 0
        summonWake = false
        summonPtt = false
        handler.removeCallbacks(reconnectRunnable)
        transport?.close()
        transport = null
        online = false
        stopCapture()
        player.stopAll()
        audio.leave()
        setConn(Conn.OFFLINE)
    }

    fun setMuted(m: Boolean) = handler.post {
        muted = m
        if (m) gate.reset()
        transport?.send(Protocol.bool("mute", m))
        render()
    }

    /** Only ever called from an explicit tap on the approval card. */
    fun approve(id: String, choice: String) = handler.post {
        transport?.send(Protocol.approval(id, choice))
    }

    /** "For Laura": answer an utterance the addressee gate ignored. */
    fun recall(turnId: String) = handler.post {
        transport?.send(Protocol.recall(turnId))
        _ui.update { st ->
            st.copy(
                lines = st.lines.map { if (it.role == "ignored" && it.turnId == turnId) it.copy(role = "recalled") else it },
                lastIgnored = if (st.lastIgnored?.turnId == turnId) null else st.lastIgnored,
            )
        }
    }

    fun recallLast() = _ui.value.lastIgnored?.takeIf { it.recallable() }?.turnId?.let { recall(it) }

    fun cancelTask(id: String) = handler.post { transport?.send(Protocol.taskCancel(id)) }

    fun enroll(action: String) = handler.post {
        if (action == "start") _ui.update { it.copy(enroll = Enroll(0, 0, "", "Starting…")) }
        if (action == "close") _ui.update { it.copy(enroll = null) } else transport?.send(Protocol.simple("enroll_$action"))
    }

    fun reconnect() = handler.post {
        retry = 0
        waitingForRelay = false
        if (active) connect()
    }

    fun networkAvailable() = handler.post {
        if (active && !online && _ui.value.conn == Conn.OFFLINE) {
            handler.removeCallbacks(reconnectRunnable)
            connect()
        }
    }

    fun pttDown() {
        if (!active || holding) return
        holding = true
        if (player.isPlaying()) bargeIn()
        render()
    }

    fun pttUp() {
        summonPtt = false
        if (!holding) return
        holding = false
        transport?.send(Protocol.simple("end_turn"))
        render()
    }

    /**
     * Handles app commands said to Laura ("open settings", see [VoiceCommands]); returns true when it did, and the
     * gateway's spoken answer to that turn is then dropped. Null (the watch) answers them like anything else.
     */
    @Volatile var onCommand: ((String) -> Boolean)? = null

    private fun command(m: ServerMsg.UserTurn) {
        val handler = onCommand ?: return
        val cmd = VoiceCommands.match(m.text) ?: return
        if (!handler(cmd)) return
        Log.i(TAG, "command $cmd: ${m.text}")
        player.fadeStop(m.turnId)
        transport?.send(Protocol.interrupt(m.turnId, 0))
        _avatar.tryEmit(AvatarEvent("reaction", "nod"))
    }

    /** Stops the current reply (tap on the orb, notification action). */
    fun stopReply() {
        if (player.isPlaying() || serverState == "speaking") bargeIn()
    }

    /**
     * Invoked from outside the app (side key, assist gesture, shortcut): turn on if needed and listen for one turn
     * now, addressed to Laura (hands-free: a `wake` goes with the first speech; push-to-talk: held until a pause).
     * What is said while it connects is kept and sent once online.
     */
    fun summon() {
        activate()
        handler.post {
            if (!active) return@post
            val now = SystemClock.elapsedRealtime()
            if (muted) {
                muted = false
                transport?.send(Protocol.bool("mute", false))
            }
            if (_ui.value.conn == Conn.ELSEWHERE) reconnect()
            if (player.isPlaying() || serverState == "speaking") bargeIn()
            summonUntil = now + SUMMON_MS
            if (cfg.mode == "ptt") {
                summonSpoke = false
                summonPtt = true
                holding = true
            } else {
                summonWake = true
            }
            if (!cfg.car) _avatar.tryEmit(AvatarEvent("reaction", "surprised"))
            render()
        }
    }

    fun configChanged() = handler.post {
        val old = cfg
        val c = config()
        cfg = c
        if (!active) return@post
        if (c.url != old.url || c.key != old.key || c.pin != old.pin || c.codec != old.codec || c.transport != old.transport) {
            retry = 0
            connect()
        } else {
            if (c.lang != old.lang) transport?.send(Protocol.value("lang", c.lang))
            if (c.mode != old.mode) {
                holding = false
                transport?.send(Protocol.value("mode", c.mode))
            }
            if (c.car != old.car) transport?.send(Protocol.context(c.context()))
        }
        reroute()
        render()
    }

    fun devicesChanged() = handler.post { if (active) reroute() }

    // ---- connection ----

    private val reconnectRunnable = Runnable { connect() }

    private fun connect() {
        handler.removeCallbacks(reconnectRunnable)
        if (!active) return
        transport?.close()
        transport = null
        online = false
        if (!cfg.configured) {
            setConn(Conn.NOT_CONFIGURED)
            return
        }
        val s = ++serial
        Log.i(TAG, "connecting #$s")
        setConn(Conn.CONNECTING)
        transport = try {
            connector(cfg).connect(Listener(s))
        } catch (e: Exception) {
            Log.w(TAG, "connect failed", e)
            handler.post { onClosed(s, Close.ABNORMAL, e.message ?: "") }
            null
        }
    }

    private inner class Listener(private val s: Int) : TransportListener {
        override fun onOpen() {
            handler.post { if (s == serial) opened() }
        }

        override fun onText(text: String) {
            if (s != serial) return
            net.down(text.length)
            val m = ServerMsg.parse(text) ?: return
            // Audio markers go straight to the player queue so they stay ordered with the binary frames.
            when (m) {
                is ServerMsg.AudioStart -> player.startTurn(m)
                is ServerMsg.AudioEnd -> player.endTurn(m.turnId)
                is ServerMsg.Interrupted -> {
                    val ms = player.fadeStop(m.turnId)
                    handler.post { transport?.send(Protocol.playback(m.turnId, ms, "stopped")) }
                }
                is ServerMsg.Duck -> player.duck(m.turnId, m.on, m.gainDb)
                else -> {}
            }
            handler.post { if (s == serial) onMessage(m) }
        }

        override fun onBinary(bytes: ByteArray) {
            if (s != serial) return
            net.down(bytes.size)
            player.packet(bytes)
        }

        override fun onClosed(code: Int, reason: String) {
            handler.post { onClosed(s, code, reason) }
        }
    }

    private fun opened() {
        Log.i(TAG, "open, sending hello")
        retry = 0
        online = true
        upCodec = if (cfg.codec == "opus" && Codecs.opusEncoder) "opus" else "pcm"
        val down = if (cfg.codec == "opus" && Codecs.opusDecoder) listOf("opus", "pcm") else listOf("pcm")
        net.codecs = "$upCodec/${down[0]}"
        transport?.send(Protocol.hello(clientName, cfg, upCodec, down, cfg.context()))
        gate.reset()
        if (capture == null) startCapture()
        _ui.update { it.copy(approvals = emptyList(), serverMuted = false, onWatch = false) }
        if (muted) transport?.send(Protocol.bool("mute", true))
        setConn(Conn.ONLINE)
    }

    private fun onClosed(s: Int, code: Int, reason: String) {
        if (s != serial) return
        Log.i(TAG, "closed $code $reason")
        transport = null
        online = false
        serverState = "idle"
        player.stopAll()
        if (!active) return setConn(Conn.OFFLINE)
        when (code) {
            Close.TAKEOVER -> {
                // Displaced: release the microphone until the user takes the conversation back.
                stopCapture()
                if (RelayBus.active) {
                    waitingForRelay = true
                    RelayBus.onEnd = { handler.post { if (waitingForRelay && active) reconnect() } }
                }
                _ui.update { it.copy(onWatch = RelayBus.active) }
                setConn(Conn.ELSEWHERE)
            }
            Close.BAD_KEY -> setConn(Conn.BAD_KEY)
            Close.CERT -> {
                _ui.update { it.copy(error = reason) }
                setConn(Conn.CERT)
            }
            else -> {
                setConn(Conn.OFFLINE)
                handler.postDelayed(reconnectRunnable, Backoff.delayMs(retry++))
            }
        }
    }

    private fun setConn(c: Conn) {
        _ui.update { it.copy(conn = c, error = if (c == Conn.CERT) it.error else "") }
        render()
    }

    // ---- server messages (handler thread) ----

    private fun onMessage(m: ServerMsg) {
        when (m) {
            is ServerMsg.State -> {
                serverState = m.value
                if (m.value == "thinking") _ui.update { it.copy(partial = "") }
            }
            is ServerMsg.Partial -> _ui.update { it.copy(partial = m.text) }
            is ServerMsg.UserTurn -> {
                addLine(Line("user", m.text, m.turnId))
                _ui.update { it.copy(partial = "") }
                net.turnStarted()
                command(m)
            }
            is ServerMsg.AssistantDelta -> appendAssistant(m.turnId, m.text, false, m.source)
            is ServerMsg.AssistantDone -> appendAssistant(m.turnId, m.text, true, m.source)
            is ServerMsg.AudioStart -> replySource = m.source
            is ServerMsg.Metrics -> net.server = m
            is ServerMsg.Pong -> net.rtt = SystemClock.elapsedRealtime() - m.t
            is ServerMsg.Error -> addLine(Line("system", m.message))
            is ServerMsg.Avatar -> {
                m.reaction?.let { _avatar.tryEmit(AvatarEvent("reaction", it)) }
                m.emotion?.let { _avatar.tryEmit(AvatarEvent("emotion", it)) }
            }
            is ServerMsg.ReplyCut -> _ui.update { st ->
                st.copy(lines = st.lines.map {
                    if (it.role == "assistant" && it.turnId == m.turnId && m.heard.isNotBlank()) it.copy(text = m.heard + "—", cut = true) else it
                })
            }
            is ServerMsg.Ignored -> if (m.reason == "backchannel" || m.reason == "echo") {
                Log.i(TAG, "not an interruption (${m.reason}): ${m.text}")
            } else if (m.text.isNotBlank() && m.reason != "muted") {
                _avatar.tryEmit(AvatarEvent("state", if (m.reason == "not_owner") "notowner" else "ignored"))
                val l = Line("ignored", m.text, m.turnId, time = SystemClock.elapsedRealtime())
                addLine(l)
                _ui.update { it.copy(partial = "", lastIgnored = l) }
            } else {
                _ui.update { it.copy(partial = "") }
            }
            is ServerMsg.Interrupted -> {
                markCut(m.turnId)
                _avatar.tryEmit(AvatarEvent("state", "interrupted"))
            }
            is ServerMsg.Muted -> _ui.update { it.copy(serverMuted = m.value) }
            is ServerMsg.LauraStatus -> _ui.update { it.copy(lauraConnected = m.connected) }
            is ServerMsg.SpeakerProfile -> _ui.update {
                it.copy(profile = when {
                    !m.available -> "voice check unavailable"
                    m.enrolled -> "voice enrolled (%.0f s)".format(m.seconds)
                    else -> "no voice profile"
                })
            }
            is ServerMsg.EnrollPrompt -> _ui.update { it.copy(enroll = Enroll(m.index, m.total, m.text, "Read this aloud")) }
            is ServerMsg.EnrollProgress -> _ui.update { st ->
                st.copy(enroll = st.enroll?.copy(status = if (m.ok) "Got it (${m.index + 1}/${m.total})" else "Again, please (${m.reason})"))
            }
            is ServerMsg.EnrollDone -> _ui.update { st ->
                st.copy(enroll = st.enroll?.copy(
                    text = "", done = true,
                    status = if (m.ok) "Voice enrolled" else "Enrollment failed: ${m.reason}"))
            }
            is ServerMsg.Task -> upsertTask(m.taskId) { it.copy(text = "${m.title} · ${m.state}", cut = m.state == "cancelled") }
            is ServerMsg.TaskProgress -> upsertTask(m.taskId) { it.copy(text = it.text.substringBefore('\n') + "\n" + m.text) }
            is ServerMsg.LauraMessage -> addLine(Line("laura", m.text, m.taskId, source = m.source))
            is ServerMsg.ApprovalRequest -> _ui.update { st ->
                st.copy(approvals = st.approvals.filter { it.approvalId != m.approvalId } + m)
            }
            is ServerMsg.ApprovalClosed -> _ui.update { st -> st.copy(approvals = st.approvals.filter { it.approvalId != m.approvalId }) }
            else -> {}
        }
        render()
    }

    private fun upsertTask(id: String, f: (Line) -> Line) = _ui.update { st ->
        val i = st.lines.indexOfLast { it.role == "task" && it.turnId == id }
        val lines = if (i < 0) (st.lines + f(Line("task", "", id))).takeLast(60)
        else st.lines.toMutableList().also { it[i] = f(it[i]) }
        st.copy(lines = lines)
    }

    private fun markCut(turn: String) = _ui.update { st ->
        st.copy(lines = st.lines.map { if (it.role == "assistant" && it.turnId == turn) it.copy(cut = true) else it })
    }

    private fun addLine(l: Line) = _ui.update { it.copy(lines = (it.lines + l).takeLast(60)) }

    private fun appendAssistant(turn: String, text: String, final: Boolean, source: Source?) = _ui.update { st ->
        val i = st.lines.indexOfLast { it.role == "assistant" && it.turnId == turn }
        val lines = if (i < 0) (st.lines + Line("assistant", text, turn, source = source)).takeLast(60)
        else st.lines.toMutableList().also {
            it[i] = it[i].copy(text = if (final) text else it[i].text + text, source = source ?: it[i].source)
        }
        st.copy(lines = lines)
    }

    // ---- routing and capture ----

    private fun reroute(force: Boolean = false) {
        val d = Routing.decide(cfg.route, cfg.car, audio.outputs(), watch, cfg.carCall)
        if (!force && d == decision && capture != null) return
        val changed = d.route != decision.route || force
        decision = d
        if (changed || capture == null) {
            stopCapture()
            audio.enter(d.route)
            player.resetSpeaker()
            startCapture()
        }
        _ui.update { it.copy(route = d.why + if (d.fullDuplex) "" else " · half-duplex") }
    }

    private fun startCapture() {
        if (!active) return
        capture = Capture(decision.route).also { it.start() }
    }

    private fun stopCapture() {
        capture?.let {
            it.running = false
            it.interrupt()
        }
        capture = null
        gate.reset()
    }

    private inner class Capture(private val route: Route) : Thread("laura-capture") {
        @Volatile var running = true
        private var enc: OpusEncoder? = null
        private var encSerial = -1
        private var levelFrames = 0
        private var levelPeak = 0.0

        override fun run() {
            val mic = try {
                audio.openMic(route)
            } catch (e: Exception) {
                Log.w(TAG, "mic", e)
                _ui.update { it.copy(error = "Microphone unavailable: ${e.message}") }
                return
            }
            try {
                while (running) {
                    val buf = ShortArray(FRAME_SAMPLES)
                    if (mic.read(buf) <= 0) {
                        Thread.sleep(100)
                        continue
                    }
                    onFrame(buf)
                }
            } catch (_: InterruptedException) {
            } finally {
                mic.close()
                enc?.close()
            }
        }

        private fun onFrame(pcm: ShortArray) {
            val rms = SpeechGate.rms(pcm)
            val now = SystemClock.elapsedRealtime()
            val playing = player.isPlaying()
            levelPeak = maxOf(levelPeak, rms)
            if (++levelFrames >= 5) {
                val db = 20 * log10(levelPeak.coerceAtLeast(1e-6))
                _ui.update { it.copy(level = ((db + 60) / 45).toFloat().coerceIn(0f, 1f)) }
                levelFrames = 0
                levelPeak = 0.0
            }
            val t = transport
            if (muted || !online || t == null) {
                gate.reset()
                if (!muted && (summonWake || summonPtt) && now < summonUntil) hold(pcm)
                return
            }
            held?.let { h ->
                held = null
                for (p in h) route(t, p, SpeechGate.rms(p), now, playing)
            }
            route(t, pcm, rms, now, playing)
        }

        /** Audio from an invocation while still connecting (up to [HOLD_MS]). */
        private var held: ArrayDeque<ShortArray>? = null

        private fun hold(pcm: ShortArray) {
            val h = held ?: ArrayDeque<ShortArray>().also { held = it }
            h.addLast(pcm)
            while (h.size * pcm.size > HOLD_MS * MIC_RATE / 1000) h.removeFirst()
        }

        private fun route(t: Transport, pcm: ShortArray, rms: Double, now: Long, playing: Boolean) {
            if (!decision.fullDuplex && !holding) {
                if (playing || serverState == "speaking") quietUntil = now + 600
                if (now < quietUntil) {
                    gate.reset()
                    return
                }
            }
            if (cfg.mode == "ptt") {
                if (holding) {
                    preroll?.let { send(t, it) }
                    preroll = null
                    send(t, pcm)
                    if (summonPtt) release(pcm, rms, now)
                } else {
                    preroll = pcm
                }
            } else {
                for (f in gate.push(pcm, rms, now)) send(t, f)
            }
            // Voice barge-in is left to the gateway, which hears the echo-cancelled stream and verifies the owner's
            // voice before sending `interrupted`; a local level detector kept firing on residual echo.
        }

        /** Invoked in push-to-talk mode: let go once the speaker pauses, or if nothing is said. */
        private fun release(pcm: ShortArray, rms: Double, now: Long) {
            gate.push(pcm, rms, now)
            if (gate.open) summonSpoke = true
            if ((summonSpoke && !gate.open) || (!summonSpoke && now > summonUntil)) {
                summonPtt = false
                handler.post { pttUp() }
            }
        }

        private fun send(t: Transport, pcm: ShortArray) {
            if (summonWake) {
                summonWake = false
                t.send(Protocol.wake(1f))
            }
            if (upCodec == "opus") {
                if (enc == null || encSerial != serial) {
                    enc?.close()
                    enc = try {
                        OpusEncoder()
                    } catch (e: Exception) {
                        Log.w(TAG, "Opus encoder unavailable, falling back to PCM", e)
                        upCodec = "pcm"
                        cfg = cfg.copy(codec = "pcm")
                        handler.post { connect() }
                        return
                    }
                    encSerial = serial
                }
                enc!!.encode(pcm) { pkt ->
                    net.up(pkt.size)
                    t.send(pkt)
                }
            } else {
                val b = ByteArray(pcm.size * 2)
                for (i in pcm.indices) {
                    b[2 * i] = pcm[i].toByte()
                    b[2 * i + 1] = (pcm[i].toInt() shr 8).toByte()
                }
                net.up(b.size)
                t.send(b)
            }
        }
    }

    private fun bargeIn() {
        net.bargeIns++
        _avatar.tryEmit(AvatarEvent("state", "interrupted"))
        val t = player.currentTurn()
        val ms = t?.let { player.fadeStop(it) }
        t?.let(::markCut)
        transport?.send(Protocol.interrupt(t, ms))
        render()
    }

    // ---- playback ----

    private class Start(val turn: String, val rate: Int, val codec: String)
    private class End(val turn: String)
    private class Pkt(val turn: String?, val data: ByteArray)
    private object Reset

    private inner class Player : Thread("laura-player") {
        private val q = LinkedBlockingDeque<Any>()
        private val dropped = Collections.synchronizedSet(HashSet<String>())
        private val stoppedAt = Collections.synchronizedMap(HashMap<String, Long>())
        @Volatile private var rxTurn: String? = null
        @Volatile private var busyUntil = 0L
        private var turn: String? = null
        private var rate = 16000
        private var codec = "pcm"
        private var decoder: OpusDecoder? = null
        @Volatile private var speaker: Speaker? = null
        private var focused = false

        // Where the turn being played starts in the speaker's frame count, to report how much of it was heard.
        @Volatile private var playTurn: String? = null
        @Volatile private var playSpeaker: Speaker? = null
        @Volatile private var playBase = 0L
        @Volatile private var turnWritten = 0L

        private val fader = Handler(HandlerThread("laura-fade").apply { start() }.looper)
        private val rampToken = Any()
        @Volatile private var gain = 1f

        private val levels = ArrayDeque<Pair<Long, Float>>()

        fun levelNow(): Float = synchronized(levels) {
            val now = SystemClock.elapsedRealtime()
            if (now >= busyUntil) levels.clear()
            while (levels.size > 1 && levels[1].first <= now) levels.removeFirst()
            levels.firstOrNull()?.takeIf { it.first <= now }?.second ?: 0f
        }

        fun isPlaying() = SystemClock.elapsedRealtime() < busyUntil ||
            q.any { it is Pkt && it.turn !in dropped }

        fun currentTurn(): String? = (rxTurn ?: turn)?.takeIf { it !in dropped && isPlaying() }

        fun startTurn(m: ServerMsg.AudioStart) {
            rxTurn = m.turnId
            q.offer(Start(m.turnId, m.sampleRate, m.codec))
        }

        fun endTurn(turn: String) = q.offer(End(turn))

        fun packet(b: ByteArray) {
            val t = rxTurn
            if (t != null && t in dropped) return
            q.offer(Pkt(t, b))
        }

        /** Milliseconds of [t]'s audio that came out of the speaker so far. */
        fun playedMs(t: String): Long {
            stoppedAt[t]?.let { return it }
            val sp = playSpeaker
            if (t != playTurn || sp == null) return 0
            val r = sp.rate().takeIf { it > 0 } ?: return 0
            return (sp.playedFrames() - playBase).coerceIn(0, turnWritten) * 1000 / r
        }

        private fun ramp(target: Float, ms: Long, then: (() -> Unit)? = null) {
            fader.removeCallbacksAndMessages(rampToken)
            val from = gain
            val steps = maxOf(1, (ms / 10).toInt())
            val t0 = SystemClock.uptimeMillis()
            for (i in 1..steps) {
                fader.postAtTime({
                    gain = from + (target - from) * i / steps
                    speaker?.setGain(gain)
                    if (i == steps) then?.invoke()
                }, rampToken, t0 + i * 10L)
            }
        }

        fun duck(t: String, on: Boolean, gainDb: Double) {
            if (t in dropped || (t != rxTurn && t != turn)) return
            Log.i(TAG, "duck $t $on")
            if (on) ramp(Math.pow(10.0, gainDb / 20).toFloat(), DUCK_MS) else ramp(1f, UNDUCK_MS)
        }

        /** Fade [t] out and drop the rest of it; returns how much of it was heard (when the fade started). */
        fun fadeStop(t: String): Long {
            stoppedAt[t]?.let { return it }
            val ms = playedMs(t)
            stoppedAt[t] = ms
            dropped.add(t)
            q.removeIf { it is Pkt && it.turn == t }
            busyUntil = 0
            val sp = speaker
            if (sp != null && (t == playTurn || t == rxTurn || t == turn)) {
                ramp(0f, FADE_MS) {
                    sp.halt()
                    if (speaker === sp) q.offerFirst(Reset)
                }
            }
            Log.i(TAG, "stop $t at $ms ms")
            return ms
        }

        fun stopAll() {
            rxTurn?.let { dropped.add(it) }
            q.clear()
            speaker?.halt()
            q.offerFirst(Reset)
            busyUntil = 0
        }

        fun resetSpeaker() = q.offerFirst(Reset)

        override fun run() {
            while (true) {
                val item = try {
                    q.poll(100, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    return
                }
                try {
                    handle(item)
                } catch (e: Exception) {
                    Log.w(TAG, "playback", e)
                    net.decErrors++
                }
            }
        }

        private fun handle(item: Any?) {
            when (item) {
                null -> if (focused && q.isEmpty() && SystemClock.elapsedRealtime() >= busyUntil) {
                    focused = false
                    audio.focus(false, decision.route)
                    handler.post { render() }
                }
                is Start -> {
                    closeDecoder()
                    turn = item.turn
                    rate = item.rate
                    codec = item.codec
                    fader.removeCallbacksAndMessages(rampToken)
                    gain = 1f
                    speaker?.setGain(1f)
                    if (codec == "opus") decoder = OpusDecoder(rate)
                }
                is Pkt -> {
                    if (item.turn != null && item.turn in dropped) return
                    if (item.data.size < 2) return
                    net.firstAudio()
                    if (codec == "opus") decoder?.decode(item.data, ::play)
                    else {
                        val s = ShortArray(item.data.size / 2)
                        for (i in s.indices) s[i] = ((item.data[2 * i].toInt() and 0xff) or (item.data[2 * i + 1].toInt() shl 8)).toShort()
                        play(s, rate)
                    }
                }
                is End -> {
                    val t = item.turn
                    if (t == turn && t !in dropped) {
                        decoder?.finish(::play)
                        val total = turnWritten
                        val r = rate
                        handler.postDelayed({
                            if (t !in dropped && t == playTurn) {
                                transport?.send(Protocol.playback(t, total * 1000 / maxOf(r, 1), "done"))
                            }
                        }, (speaker?.pendingMs() ?: 0) + 50)
                    }
                    closeDecoder()
                }
                Reset -> {
                    closeDecoder()
                    speaker?.close()
                    speaker = null
                    playSpeaker = null
                    fader.removeCallbacksAndMessages(rampToken)
                    gain = 1f
                    busyUntil = 0
                    handler.post { render() }
                }
            }
        }

        private fun play(pcm: ShortArray, r: Int) {
            val t = turn
            if (t != null && t in dropped) return
            val sp = speaker ?: audio.openSpeaker(decision.route).also { speaker = it; it.setGain(gain) }
            if (!focused) {
                focused = true
                audio.focus(true, decision.route)
                handler.post { render() }
            }
            if (t != playTurn || sp !== playSpeaker) {
                playBase = if (sp.rate() == r) sp.writtenFrames() else 0
                turnWritten = 0
                playSpeaker = sp
                playTurn = t
            }
            val wasPlaying = SystemClock.elapsedRealtime() < busyUntil
            val heardAt = SystemClock.elapsedRealtime() + sp.pendingMs()
            val db = 20 * log10(SpeechGate.rms(pcm).coerceAtLeast(1e-6))
            synchronized(levels) {
                levels.addLast(heardAt to ((db + 50) / 40).toFloat().coerceIn(0f, 1f))
                while (levels.size > 400) levels.removeFirst()
            }
            sp.write(pcm, r)
            turnWritten += pcm.size
            net.playedS += pcm.size.toDouble() / r
            busyUntil = SystemClock.elapsedRealtime() + sp.pendingMs() + 150
            if (!wasPlaying) handler.post { render() }
        }

        private fun closeDecoder() {
            decoder?.close()
            decoder = null
        }
    }

    // ---- view and stats ----

    private fun render() {
        val conn = _ui.value.conn
        val view = when {
            player.isPlaying() -> View.SPEAKING
            holding -> View.LISTENING
            !online -> View.OFFLINE
            serverState == "thinking" -> View.THINKING
            serverState == "speaking" -> View.SPEAKING
            active && !muted && cfg.mode != "ptt" -> View.LISTENING
            else -> View.IDLE
        }
        val now = SystemClock.elapsedRealtime()
        val awake = now < summonUntil
        val src = if (view == View.SPEAKING) replySource else null
        _ui.update {
            it.copy(active = active, view = view, muted = muted, holding = holding, conn = conn, awake = awake,
                speakingSource = src)
        }
    }

    private inner class Stats {
        private var upBytes = 0L
        private var downBytes = 0L
        var upTotal = 0L
        var downTotal = 0L
        var upKbps = 0L
        var downKbps = 0L
        @Volatile var rtt: Long? = null
        @Volatile var turnMs: Long? = null
        @Volatile private var turnT0: Long? = null
        @Volatile var server: ServerMsg.Metrics? = null
        @Volatile var codecs = ""
        @Volatile var bargeIns = 0
        @Volatile var decErrors = 0
        @Volatile var playedS = 0.0
        var tick = 0

        @Synchronized fun up(n: Int) { upBytes += n; upTotal += n }
        @Synchronized fun down(n: Int) { downBytes += n; downTotal += n }
        fun turnStarted() { turnT0 = SystemClock.elapsedRealtime() }
        fun firstAudio() {
            val t0 = turnT0 ?: return
            turnMs = SystemClock.elapsedRealtime() - t0
            turnT0 = null
        }

        @Synchronized fun second() {
            upKbps = upBytes * 8 / 1000
            downKbps = downBytes * 8 / 1000
            upBytes = 0
            downBytes = 0
            tick++
        }

        fun text(): String {
            val s = server
            return buildString {
                append("RTT ${rtt ?: "–"} ms · ↑ $upKbps kbps · ↓ $downKbps kbps · ")
                append("%.1f MB · reply %s".format((upTotal + downTotal) / 1e6, turnMs?.let { "%.2f s".format(it / 1000.0) } ?: "–"))
                if (codecs.isNotEmpty()) append(" · $codecs")
                if (s != null) append("\nserver: asr ${s.asrMs} · llm ${s.llmFirstMs} · tts ${s.ttsFirstMs} ms")
            }
        }
    }

    private fun tick() {
        handler.postDelayed(::tick, 1000)
        net.second()
        val t = transport
        if (online && t != null) {
            if (net.tick % 2 == 0) t.send(Protocol.ping(SystemClock.elapsedRealtime()))
            if (net.tick % 5 == 0) t.send(
                Protocol.stats(
                    mapOf(
                        "rtt_ms" to net.rtt, "up_kbps" to net.upKbps, "down_kbps" to net.downKbps,
                        "up_mb" to round2(net.upTotal / 1e6), "down_mb" to round2(net.downTotal / 1e6),
                        "turn_ms" to net.turnMs, "codecs" to net.codecs, "gate_open" to gate.open,
                        "played_s" to round2(net.playedS), "barge_ins" to net.bargeIns,
                        "dec_errors" to net.decErrors, "car" to cfg.car, "route" to decision.why,
                        "full_duplex" to decision.fullDuplex,
                    )
                )
            )
        }
        _ui.update { it.copy(stats = net.text()) }
        if (summonUntil != 0L && SystemClock.elapsedRealtime() >= summonUntil) {
            summonUntil = 0
            summonWake = false
            render()
        }
        if (_ui.value.view == View.SPEAKING && !player.isPlaying()) render()
    }

    private fun round2(x: Double) = Math.round(x * 100) / 100.0

    companion object {
        private const val TAG = "LauraEngine"
        private const val DUCK_MS = 30L
        private const val UNDUCK_MS = 150L
        private const val FADE_MS = 80L
        const val SUMMON_MS = 10_000L
        private const val HOLD_MS = 5_000L
    }
}
