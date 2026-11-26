package dev.hect.lauravoice.car

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Process
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.media.MediaBrowserServiceCompat
import dev.hect.lauravoice.App
import dev.hect.lauravoice.core.CarState
import dev.hect.lauravoice.R
import dev.hect.lauravoice.VoiceService
import dev.hect.lauravoice.core.Conn
import dev.hect.lauravoice.core.UiState
import dev.hect.lauravoice.core.View
import dev.hect.lauravoice.core.VoiceServiceBase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Laura as a media source in Android Auto (and anything else that browses media apps). Android Auto draws the
 * screen from this session: her icon as artwork, what she's doing as the subtitle, her last reply as the
 * description. Play = talk (turns her on or unmutes), Pause/Stop = stop the reply; custom actions mute and turn
 * off. The session reports PLAYING only while she speaks, so switching Android Auto to another media app while
 * she's quiet doesn't mute her, and the steering-wheel pause stops a reply.
 */
class LauraMediaService : MediaBrowserServiceCompat() {
    private lateinit var session: MediaSessionCompat
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var error: String? = null
    private val art: Bitmap? by lazy {
        runCatching { packageManager.getApplicationIcon(packageName).toBitmap(320, 320) }.getOrNull()
    }

    override fun onCreate() {
        super.onCreate()
        session = MediaSessionCompat(this, "Laura").apply {
            setCallback(callback)
            setPlaybackToLocal(android.media.AudioManager.STREAM_MUSIC)
        }
        sessionToken = session.sessionToken
        scope.launch {
            // The UI state ticks with the mic level; only what's shown here matters.
            combine(App.engine.ui, CarState.active) { ui, car ->
                Triple(car, status(ui), listOf(ui.active, ui.view, ui.muted, ui.lines.lastOrNull { it.role == "assistant" }?.text))
            }.distinctUntilChanged().collect { (car, _, _) ->
                if (session.isActive != car) session.isActive = car
                publish(App.engine.ui.value)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        session.release()
        super.onDestroy()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        val trusted = clientUid == Process.myUid() || clientUid == Process.SYSTEM_UID || clientPackageName in TRUSTED
        if (!trusted) Log.i(TAG, "refused browser $clientPackageName")
        return if (trusted) BrowserRoot(ROOT, null) else null
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        if (parentId != ROOT) return result.sendResult(mutableListOf())
        val ui = App.engine.ui.value
        val talk = MediaDescriptionCompat.Builder()
            .setMediaId(TALK)
            .setTitle("Talk to Laura")
            .setSubtitle(status(ui))
            .setIconBitmap(art)
            .build()
        result.sendResult(mutableListOf(MediaItem(talk, MediaItem.FLAG_PLAYABLE)))
    }

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = talk()
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) = talk()
        override fun onPlayFromSearch(query: String?, extras: Bundle?) = talk()
        override fun onPrepare() {}
        override fun onPause() = App.engine.stopReply()
        override fun onStop() = App.engine.stopReply()

        override fun onCustomAction(action: String?, extras: Bundle?) {
            when (action) {
                ACTION_MUTE -> App.engine.setMuted(!App.engine.ui.value.muted)
                ACTION_OFF -> VoiceServiceBase.stop(this@LauraMediaService, VoiceService::class.java, "android auto")
            }
        }
    }

    private fun talk() {
        error = null
        val engine = App.engine
        when {
            !App.store.load().configured -> error = "Set up Laura on your phone first"
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                error = "Open Laura on your phone and allow the microphone"
            !engine.active -> runCatching { VoiceServiceBase.start(this, VoiceService::class.java) }.onFailure {
                // Android 12+ may refuse a microphone service started from the background.
                Log.w(TAG, "can't start from Android Auto", it)
                error = "Open Laura on your phone once to turn her on"
            }
            engine.ui.value.muted -> engine.setMuted(false)
        }
        if (error == null) onTalk?.invoke()
        publish(engine.ui.value)
    }

    private fun status(ui: UiState): String = when {
        !ui.active -> "Off · press play to talk"
        ui.conn == Conn.ELSEWHERE -> if (ui.onWatch) "On your watch" else "In use on another device"
        ui.conn != Conn.ONLINE -> "Connecting…"
        ui.muted -> "Muted"
        ui.view == View.SPEAKING -> "Speaking"
        ui.view == View.THINKING -> "Thinking…"
        ui.awake -> "Listening"
        else -> "Say \"Laura\""
    }

    private fun publish(ui: UiState) {
        val last = ui.lines.lastOrNull { it.role == "assistant" }?.text.orEmpty()
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, TALK)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Laura")
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, "Laura")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, status(ui))
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, status(ui))
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, last.take(200))
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, art)
                .build()
        )
        val state = when {
            !ui.active -> PlaybackStateCompat.STATE_PAUSED
            ui.view == View.SPEAKING -> PlaybackStateCompat.STATE_PLAYING
            ui.view == View.THINKING -> PlaybackStateCompat.STATE_BUFFERING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        val b = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                    PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
            )
        error?.let { b.setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, it) }
        b.setState(if (error != null) PlaybackStateCompat.STATE_ERROR else state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
        if (ui.active) {
            b.addCustomAction(ACTION_MUTE, if (ui.muted) "Unmute" else "Mute", MIC_ICON)
            b.addCustomAction(ACTION_OFF, "Turn off", OFF_ICON)
        }
        session.setPlaybackState(b.build())
        notifyChildrenChanged(ROOT)
    }

    companion object {
        private const val TAG = "LauraMedia"
        private const val ROOT = "laura_root"
        private const val TALK = "talk"
        private const val ACTION_MUTE = "dev.hect.lauravoice.car.MUTE"
        private const val ACTION_OFF = "dev.hect.lauravoice.car.OFF"
        private val MIC_ICON = R.drawable.ic_car_mic
        private val OFF_ICON = R.drawable.ic_car_off
        private val TRUSTED = setOf(
            "com.google.android.projection.gearhead",   // Android Auto
            "com.google.android.carassistant",
            "com.google.android.googlequicksearchbox",  // Assistant / Gemini voice actions
            "com.google.android.autosimulator",
            "com.android.systemui",
            "com.android.bluetooth",
        )

        /** Cross-lane hook: what "talk" does once she's on and unmuted (e.g. open the mic without the wake word). */
        @Volatile var onTalk: (() -> Unit)? = { App.engine.summon() }
    }
}
