package dev.hect.lauravoice.car

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import dev.hect.lauravoice.App
import dev.hect.lauravoice.core.CarBrowse
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
 * Laura as a media source in Android Auto (and anything else that browses media apps), the fallback when car call
 * mode is off. Android Auto draws the screen from this session: one static picture of her as artwork, what she's
 * doing as the subtitle, her last reply as the description. Play = talk (turns her on or unmutes), Pause/Stop = stop the reply; custom actions mute and turn
 * off. The session reports PLAYING only while she speaks, so switching Android Auto to another media app while
 * she's quiet doesn't mute her, and the steering-wheel pause stops a reply.
 */
class LauraMediaService : MediaBrowserServiceCompat() {
    private lateinit var session: MediaSessionCompat
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var error: String? = null
    private val uris = HashMap<Int, Uri>()

    override fun onCreate() {
        super.onCreate()
        session = MediaSessionCompat(this, "Laura").apply {
            setCallback(callback)
            setPlaybackToLocal(android.media.AudioManager.STREAM_MUSIC)
        }
        sessionToken = session.sessionToken
        scope.launch {
            // The UI state ticks with the mic level; only what's shown here matters.
            // During a car call Android Auto shows the call instead, so the media session steps aside.
            combine(App.engine.ui, CarState.active, CarState.call) { ui, car, call ->
                Triple(car && !call, status(ui), listOf(ui.active, ui.view, ui.muted, ui.lines.lastOrNull { it.role == "assistant" }?.text))
            }.distinctUntilChanged().collect { (on, _, _) ->
                if (session.isActive != on) session.isActive = on
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
        val app = runCatching { packageManager.getApplicationInfo(clientPackageName, 0) }.getOrNull()
        val ok = CarBrowse.allowed(
            clientPackageName, clientUid, Process.myUid(), Process.SYSTEM_UID,
            systemApp = app != null && (app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0,
            mediaControl = packageManager.checkPermission(MEDIA_CONTENT_CONTROL, clientPackageName) == PackageManager.PERMISSION_GRANTED,
        )
        Log.i(TAG, "${if (ok) "root for" else "refused"} browser $clientPackageName")
        return if (ok) BrowserRoot(CarBrowse.ROOT, null) else null
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        val ui = App.engine.ui.value
        result.sendResult(CarBrowse.children(parentId).mapTo(mutableListOf()) { n ->
            val d = MediaDescriptionCompat.Builder().setMediaId(n.id)
            if (n.browsable) d.setTitle("Laura").setIconUri(uri(R.drawable.ic_car_laura))
            else d.setTitle("Talk to Laura").setSubtitle(status(ui)).setIconUri(uri(R.drawable.car_icon))
            MediaItem(d.build(), if (n.browsable) MediaItem.FLAG_BROWSABLE else MediaItem.FLAG_PLAYABLE)
        })
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

    /** Artwork goes out as android.resource URIs (what the Android Auto docs ask for), never as bitmaps over Binder. */
    private fun uri(id: Int): Uri = uris.getOrPut(id) {
        Uri.Builder().scheme(ContentResolver.SCHEME_ANDROID_RESOURCE).authority(resources.getResourcePackageName(id))
            .appendPath(resources.getResourceTypeName(id)).appendPath(resources.getResourceEntryName(id)).build()
    }

    private fun setMetadata(ui: UiState) {
        val art = uri(R.drawable.car_avatar).toString()
        val last = ui.lines.lastOrNull { it.role == "assistant" }?.text.orEmpty()
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, CarBrowse.TALK)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Laura")
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, "Laura")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, status(ui))
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, status(ui))
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, last.take(200))
                .putString(MediaMetadataCompat.METADATA_KEY_ART_URI, art)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, art)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, art)
                .build()
        )
    }

    private fun publish(ui: UiState) {
        setMetadata(ui)
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
        notifyChildrenChanged(CarBrowse.HOME)
    }

    companion object {
        private const val TAG = "LauraMedia"
        private const val MEDIA_CONTENT_CONTROL = "android.permission.MEDIA_CONTENT_CONTROL"
        private const val ACTION_MUTE = "dev.hect.lauravoice.car.MUTE"
        private const val ACTION_OFF = "dev.hect.lauravoice.car.OFF"
        private val MIC_ICON = R.drawable.ic_car_mic
        private val OFF_ICON = R.drawable.ic_car_off

        /** Cross-lane hook: what "talk" does once she's on and unmuted (e.g. open the mic without the wake word). */
        @Volatile var onTalk: (() -> Unit)? = { App.engine.summon() }
    }
}
