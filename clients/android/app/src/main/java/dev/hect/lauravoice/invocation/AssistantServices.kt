package dev.hect.lauravoice.invocation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.RemoteException
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Laura as the phone's digital assistant (ROLE_ASSISTANT). The system binds this while she is the default
 * assistant app; long-pressing the side key (One UI "Digital assistant app"), the corner swipe or the lock
 * screen shortcut open a session, which only starts [InvokeActivity]. No assist data or screenshot is collected.
 */
class LauraInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        showSession(Bundle(), 0)
    }
}

class LauraSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = LauraSession(this)
}

class LauraSession(ctx: Context) : VoiceInteractionSession(ctx) {
    override fun onCreate() {
        super.onCreate()
        // no session window: the overlay (or InvokeActivity's panel) is her UI
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Invocation.attach(this)
        runCatching { startAssistantActivity(Invocation.intent(context, "assistant")) }
            .onFailure {
                Log.w(TAG, "startAssistantActivity", it)
                hide()
            }
    }

    override fun onHide() {
        Invocation.detach(this)
        super.onHide()
    }

    companion object {
        private const val TAG = "LauraSession"
    }
}

/**
 * Required by the assistant role, and the system makes the assistant's recognizer the default one. Laura has no
 * offline speech-to-text of her own, so this passes requests on to the phone's other recognizer (Google's, or
 * Samsung's), and other apps' voice input keeps working.
 */
class LauraRecognitionService : RecognitionService() {
    private var inner: SpeechRecognizer? = null

    private fun delegate(): ComponentName? {
        val found = packageManager.queryIntentServices(Intent(SERVICE_INTERFACE), 0)
            .map { it.serviceInfo }
            .filter { it.packageName != packageName }
        val pick = PREFERRED.firstNotNullOfOrNull { p -> found.firstOrNull { it.packageName == p } } ?: found.firstOrNull()
        return pick?.let { ComponentName(it.packageName, it.name) }
    }

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        inner?.destroy()
        val target = delegate()
        if (target == null) {
            runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        inner = SpeechRecognizer.createSpeechRecognizer(this, target).apply {
            setRecognitionListener(Relay(listener))
            startListening(recognizerIntent)
        }
    }

    override fun onStopListening(listener: Callback) {
        inner?.stopListening()
    }

    override fun onCancel(listener: Callback) {
        inner?.cancel()
    }

    override fun onDestroy() {
        inner?.destroy()
        inner = null
        super.onDestroy()
    }

    private class Relay(private val cb: Callback) : RecognitionListener {
        private fun pass(f: () -> Unit) = try { f() } catch (_: RemoteException) {}
        override fun onReadyForSpeech(params: Bundle?) = pass { cb.readyForSpeech(params ?: Bundle()) }
        override fun onBeginningOfSpeech() = pass { cb.beginningOfSpeech() }
        override fun onRmsChanged(rmsdB: Float) = pass { cb.rmsChanged(rmsdB) }
        override fun onBufferReceived(buffer: ByteArray?) = pass { if (buffer != null) cb.bufferReceived(buffer) }
        override fun onEndOfSpeech() = pass { cb.endOfSpeech() }
        override fun onError(error: Int) = pass { cb.error(error) }
        override fun onResults(results: Bundle?) = pass { cb.results(results ?: Bundle()) }
        override fun onPartialResults(partialResults: Bundle?) = pass { cb.partialResults(partialResults ?: Bundle()) }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    companion object {
        private val PREFERRED = listOf(
            "com.google.android.googlequicksearchbox",
            "com.google.android.tts",
            "com.google.android.as",
            "com.samsung.android.bixby.agent",
        )
    }
}
