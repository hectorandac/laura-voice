package dev.hect.lauravoice.avatar

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES30.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Off-screen render check, run on a device or emulator without an app:
 *   adb shell CLASSPATH=<apk> app_process / dev.hect.lauravoice.avatar.AvatarSnapshot shots <cases.json> <outDir>
 *   adb shell CLASSPATH=<apk> app_process / dev.hect.lauravoice.avatar.AvatarSnapshot perf <outFile>
 * shots: renders each export.sh case (tools/avatar-export/cases.py) to <outDir>/<name>.png, stepped exactly as the
 * WebView reference stills. perf: per-frame CPU time of step() and of step + draw + glFinish.
 */
object AvatarSnapshot {
    @JvmStatic fun main(args: Array<String>) {
        when (args[0]) {
            "shots" -> shots(File(args[1]).readText(), File(args[2]))
            "perf" -> File(args[1]).writeText(perf())
        }
    }

    private class Ctx(w: Int, h: Int, samples: Int) {
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ctx: android.opengl.EGLContext
        val surf: android.opengl.EGLSurface
        init {
            val v = IntArray(2); EGL14.eglInitialize(dpy, v, 0, v, 1)
            fun pick(s: Int): EGLConfig? {
                val attrs = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_DEPTH_SIZE, 16,
                    EGL14.EGL_RENDERABLE_TYPE, 0x40, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_SAMPLE_BUFFERS, if (s > 0) 1 else 0, EGL14.EGL_SAMPLES, s, EGL14.EGL_NONE)
                val c = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
                return if (EGL14.eglChooseConfig(dpy, attrs, 0, c, 0, 1, n, 0) && n[0] > 0) c[0] else null
            }
            val cfg = pick(samples) ?: pick(0)!!
            ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            surf = EGL14.eglCreatePbufferSurface(dpy, cfg, intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) { "eglMakeCurrent" }
        }
        fun close() { EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT); EGL14.eglDestroySurface(dpy, surf); EGL14.eglDestroyContext(dpy, ctx) }
    }

    private fun shots(casesJson: String, out: File) {
        out.mkdirs()
        for (c in Json.parse(casesJson).arr().map { it.obj() }) {
            val px = c["px"].i(); val ratio = (c["pxRatio"] as? Number)?.toFloat() ?: 1f
            val m = AvatarModel(watch = (c["watch"] as? Number)?.toInt() == 1, lodPx = (c["lod"] as? Number)?.toInt() ?: AvatarModel.WATCH_MIN_PX)
            m.theme = c["theme"] as String
            m.startCase(c["state"] as String, (c["emotion"] as? String) ?: "none")
            m.micInput = ((c["mic"] as? Number) ?: 0).toFloat(); m.ttsInput = ((c["tts"] as? Number) ?: 0).toFloat()
            val g = Ctx(px, px, 4)
            val r = AvatarRenderer(m, (c["zoom"] as? Number)?.toFloat() ?: 1f)
            r.init()
            val hex = (c["clearHex"] as? String) ?: "#0F0D17"
            r.clearRgb = floatArrayOf(hex.substring(1, 3).toInt(16) / 255f, hex.substring(3, 5).toInt(16) / 255f, hex.substring(5, 7).toInt(16) / 255f)
            r.resize(px, px, if ((c["small"] as? Number)?.toInt() == 1) px / ratio else maxOf(px / ratio, 181f))
            val frames = c["frames"].i(); val reactAt = (c["reactAt"] as? Number)?.toInt() ?: -1
            for (f in 1..frames) m.stepCase(if (f == reactAt) c["reaction"] as String else null)
            val fbo = Msaa(px, 4)
            r.draw()
            fbo.resolve()
            save(px, File(out, (c["name"] as String) + ".png"))
            g.close()
        }
    }

    /** A 4x MSAA framebuffer resolved into the pbuffer (pbuffer configs with samples are rare on emulators; devices get them from EGL). */
    private class Msaa(val px: Int, samples: Int) {
        val fb = IntArray(1); val rb = IntArray(2)
        init {
            glGenFramebuffers(1, fb, 0); glGenRenderbuffers(2, rb, 0)
            glBindRenderbuffer(GL_RENDERBUFFER, rb[0]); glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_RGBA8, px, px)
            glBindRenderbuffer(GL_RENDERBUFFER, rb[1]); glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_DEPTH_COMPONENT16, px, px)
            glBindFramebuffer(GL_FRAMEBUFFER, fb[0])
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rb[0])
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, rb[1])
            check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) { "msaa fbo" }
        }
        fun resolve() {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, fb[0]); glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
            glBlitFramebuffer(0, 0, px, px, 0, 0, px, px, GL_COLOR_BUFFER_BIT, GL_NEAREST)
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
            glDeleteFramebuffers(1, fb, 0); glDeleteRenderbuffers(2, rb, 0)
        }
    }

    private fun save(px: Int, f: File) {
        val buf = ByteBuffer.allocateDirect(px * px * 4).order(ByteOrder.nativeOrder())
        glReadPixels(0, 0, px, px, GL_RGBA, GL_UNSIGNED_BYTE, buf)
        val row = px * 4; val flip = ByteBuffer.allocateDirect(px * px * 4)
        for (y in 0 until px) { buf.position((px - 1 - y) * row); buf.limit((px - y) * row); flip.put(buf); buf.limit(buf.capacity()) }
        flip.position(0)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(flip)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun perf(): String {
        val sb = StringBuilder()
        for ((label, watch, theme, px, dp) in listOf(
            listOf("watch 480 px", true, "dark", 480, 240f), listOf("bubble 104 dp (273 px)", false, "clear", 273, 104f), listOf("phone full model 480 px", false, "dark", 480, 240f))) {
            for ((st, tts) in listOf("idle" to 0f, "speaking" to 0.7f, "listening" to 0f)) {
                val m = AvatarModel(watch = watch as Boolean); m.theme = theme as String
                m.startCase(st); m.ttsInput = tts; m.micInput = if (st == "listening") 0.5f else 0f
                val g = Ctx(px as Int, px, 4); val r = AvatarRenderer(m); r.init(); r.resize(px, px, dp as Float); val fbo = Msaa(px, 4)
                repeat(60) { m.stepCase(); r.draw() }; glFinish()
                val n = 300; val stepNs = LongArray(n); val totNs = LongArray(n)
                for (i in 0 until n) {
                    val t0 = System.nanoTime(); m.stepCase(); val t1 = System.nanoTime(); r.draw(); glFinish(); val t2 = System.nanoTime()
                    stepNs[i] = t1 - t0; totNs[i] = t2 - t0
                }
                fun pct(a: LongArray, q: Double) = a.sorted()[(q * (a.size - 1)).toInt()] / 1e6
                sb.append("%-26s %-10s instances %3d  step p50 %.2f ms p95 %.2f ms  step+draw+finish p50 %.2f ms p95 %.2f ms\n".format(
                    label, st, m.count, pct(stepNs, 0.5), pct(stepNs, 0.95), pct(totNs, 0.5), pct(totNs, 0.95)))
                fbo.resolve(); g.close()
            }
        }
        return sb.toString()
    }
}
