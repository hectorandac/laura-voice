package dev.hect.lauravoice.avatar

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.view.Choreographer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10

/**
 * GLES 3 view of [AvatarModel] drawn by [AvatarRenderer]: the same look as the phone's WebView avatar. Renders only
 * between [resume] and [pause], at the model's frame-rate tier capped at [fps], at [renderScale] of the view's size.
 * [transparent]: only her cubes on a translucent surface (the WebView's clear theme: no halo or floor, warm outline),
 * for floating over other apps. Otherwise the dark theme on black (the watch). [zoom] 1 = the WebView's framing.
 */
class AvatarGlView(context: Context, val model: AvatarModel = AvatarModel(), private val fps: Int = 30,
                   private val renderScale: Float = 1f, transparent: Boolean = false, zoom: Float = 1f,
                   msaa: Int = 4) : GLSurfaceView(context) {
    private val renderer = R(model, zoom, context.resources.displayMetrics.density)
    private var running = false
    private var lastFrameNs = 0L
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val cap = minOf(fps, model.autoFps())
            if (frameTimeNanos - lastFrameNs >= 1_000_000_000L / cap - 2_000_000L) {
                lastFrameNs = frameTimeNanos
                requestRender()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        model.theme = if (transparent) "clear" else "dark"
        setEGLContextClientVersion(3)
        setEGLConfigChooser(Chooser(msaa))
        if (transparent) holder.setFormat(PixelFormat.TRANSLUCENT)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (renderScale < 1f && w > 0) holder.setFixedSize((w * renderScale).toInt(), (h * renderScale).toInt())
    }

    fun resume() {
        onResume()
        if (running) return
        running = true
        renderer.lastNs = 0
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun pause() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        onPause()
    }

    /** Frames rendered so far (for measuring). */
    val frames get() = renderer.frames

    private inner class R(val model: AvatarModel, zoom: Float, val density: Float) : Renderer {
        var lastNs = 0L
        @Volatile var frames = 0L
        private val r = AvatarRenderer(model, zoom)

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) = r.init()

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            // the surface may be scaled (renderScale): the dp width is the view's, not the buffer's
            r.resize(width, height, this@AvatarGlView.width.coerceAtLeast(1) / density)
        }

        override fun onDrawFrame(gl: GL10?) {
            val now = SystemClock.elapsedRealtimeNanos()
            val dt = if (lastNs == 0L) 1f / 30 else (now - lastNs) / 1e9f
            lastNs = now
            model.step(dt)
            r.draw()
            frames++
        }
    }

    /** RGBA8888 + depth, with [samples]x MSAA when the device offers it (the WebView renders antialiased). */
    private class Chooser(private val samples: Int) : EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            fun pick(s: Int): EGLConfig? {
                val attrs = intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT */,
                    EGL10.EGL_SAMPLE_BUFFERS, if (s > 0) 1 else 0, EGL10.EGL_SAMPLES, s, EGL10.EGL_NONE)
                val n = IntArray(1); val cfg = arrayOfNulls<EGLConfig>(1)
                return if (egl.eglChooseConfig(display, attrs, cfg, 1, n) && n[0] > 0) cfg[0] else null
            }
            return (if (samples > 0) pick(samples) else null) ?: pick(0) ?: error("no RGBA8888 GLES 3 config")
        }
    }
}
