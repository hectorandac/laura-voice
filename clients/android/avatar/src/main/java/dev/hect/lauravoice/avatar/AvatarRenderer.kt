package dev.hect.lauravoice.avatar

import android.opengl.GLES30.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * GLES 3 port of the WebView avatar's three.js scene: one instanced draw of chamfered cubes with the same voxel
 * shader, the inner lantern sphere, and per theme the warm outline hull (clear), or the halo and floor glow (dark,
 * light). Call [init] and [resize] on the GL thread, then [draw] after [AvatarModel.step].
 */
class AvatarRenderer(private val model: AvatarModel, private val zoom: Float = 1f) {
    private var vox = 0; private var outline = 0; private var innerP = 0; private var quad = 0
    private var voxVao = 0; private var sphereVao = 0; private var quadVao = 0; private var instVbo = 0
    private var sphereCount = 0
    private lateinit var inst: FloatBuffer
    private val view = FloatArray(16); private val proj = FloatArray(16)
    private var camY = 0f; private var camD = 0f
    private var pxW = 1; private var pxH = 1; private var pxPerDp = 1f
    private val u = HashMap<String, Int>()
    /** Background when not transparent (sRGB). */
    var clearRgb = floatArrayOf(0f, 0f, 0f)

    fun init() {
        vox = program(VOX_VS, VOX_FS); outline = program(OUT_VS, OUT_FS); innerP = program(INNER_VS, INNER_FS); quad = program(QUAD_VS, QUAD_FS)
        val vaos = IntArray(3); glGenVertexArrays(3, vaos, 0); voxVao = vaos[0]; sphereVao = vaos[1]; quadVao = vaos[2]
        val bufs = IntArray(5); glGenBuffers(5, bufs, 0); instVbo = bufs[1]
        // chamfered cube: position + normal
        glBindVertexArray(voxVao)
        glBindBuffer(GL_ARRAY_BUFFER, bufs[0]); val cube = floats(CHAMFER)
        glBufferData(GL_ARRAY_BUFFER, CHAMFER.size * 4, cube, GL_STATIC_DRAW)
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, false, 24, 0)
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 3, GL_FLOAT, false, 24, 12)
        glBindBuffer(GL_ARRAY_BUFFER, instVbo)
        glBufferData(GL_ARRAY_BUFFER, model.poolSize * AvatarModel.STRIDE * 4, null, GL_DYNAMIC_DRAW)
        val st = AvatarModel.STRIDE * 4
        // instance matrix (4 columns), aLit, aShd, aFx, aX, aY, aN
        val layout = intArrayOf(4, 4, 4, 4, 3, 3, 4, 4, 4, 3)
        var off = 0
        for ((n, size) in layout.withIndex()) {
            val loc = 2 + n
            glEnableVertexAttribArray(loc); glVertexAttribPointer(loc, size, GL_FLOAT, false, st, off * 4); glVertexAttribDivisor(loc, 1)
            off += size
        }
        // sphere (three.js SphereGeometry(1, 20, 14))
        glBindVertexArray(sphereVao)
        val (sv, si) = sphere(20, 14); sphereCount = si.size
        glBindBuffer(GL_ARRAY_BUFFER, bufs[2]); glBufferData(GL_ARRAY_BUFFER, sv.size * 4, floats(sv), GL_STATIC_DRAW)
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, false, 24, 0)
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 3, GL_FLOAT, false, 24, 12)
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, bufs[3])
        val ib = ByteBuffer.allocateDirect(si.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().put(si).also { it.position(0) }
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, si.size * 2, ib, GL_STATIC_DRAW)
        // unit quad with uv (PlaneGeometry)
        glBindVertexArray(quadVao)
        glBindBuffer(GL_ARRAY_BUFFER, bufs[4])
        val qv = floatArrayOf(-0.5f, -0.5f, 0f, 0f, 0.5f, -0.5f, 1f, 0f, -0.5f, 0.5f, 0f, 1f, 0.5f, 0.5f, 1f, 1f)
        glBufferData(GL_ARRAY_BUFFER, qv.size * 4, floats(qv), GL_STATIC_DRAW)
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 2, GL_FLOAT, false, 16, 0)
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 2, GL_FLOAT, false, 16, 8)
        glBindVertexArray(0)
        inst = ByteBuffer.allocateDirect(model.poolSize * AvatarModel.STRIDE * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        u.clear()
    }

    /** [dpWidth]: the view's width in dp (the WebView's CSS px), for the <= 180 "small" rules and the outline width. */
    fun resize(w: Int, h: Int, dpWidth: Float) {
        pxW = max(1, w); pxH = max(1, h); pxPerDp = pxW / max(1f, dpWidth)
        model.small = minOf(dpWidth, dpWidth * h / w) <= 180f
        val d = model.d; val fb = d.fb; val aspect = pxW.toDouble() / pxH
        val f = tan(Math.toRadians(d.fov / 2)); val m = 1.1
        val cy = (fb.getValue("y0") + fb.getValue("y1")) / 2; val hh = (fb.getValue("y1") - fb.getValue("y0")) / 2 * m
        val hw = max(-fb.getValue("x0"), fb.getValue("x1")) * m
        val z1 = fb.getValue("z1")
        val dist = z1 + max(hh / f, hw / (f * aspect)) / zoom
        camY = cy.toFloat(); camD = dist.toFloat()
        val near = max(1.0, dist - 20); val far = dist + 30
        // three.js PerspectiveCamera projection (column-major)
        val top = near * tan(Math.toRadians(d.fov / 2)); val height = 2 * top; val width = aspect * height
        java.util.Arrays.fill(proj, 0f)
        proj[0] = (2 * near / width).toFloat(); proj[5] = (2 * near / height).toFloat()
        proj[10] = (-(far + near) / (far - near)).toFloat(); proj[11] = -1f; proj[14] = (-2 * far * near / (far - near)).toFloat()
        java.util.Arrays.fill(view, 0f); view[0] = 1f; view[5] = 1f; view[10] = 1f; view[15] = 1f; view[13] = -camY; view[14] = -camD
    }

    private fun loc(p: Int, n: String) = u.getOrPut("$p:$n") { glGetUniformLocation(p, n) }

    fun draw() {
        val m = model; val th = m.th; val clear = th.name == "clear"
        glViewport(0, 0, pxW, pxH)
        if (clear) glClearColor(0f, 0f, 0f, 0f) else glClearColor(clearRgb[0], clearRgb[1], clearRgb[2], 1f)
        glDepthMask(true); glClearDepthf(1f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL); glDisable(GL_BLEND); glEnable(GL_CULL_FACE); glCullFace(GL_BACK)
        // instances
        inst.position(0); inst.put(m.instances, 0, m.count * AvatarModel.STRIDE); inst.position(0)
        glBindBuffer(GL_ARRAY_BUFFER, instVbo)
        glBufferSubData(GL_ARRAY_BUFFER, 0, m.count * AvatarModel.STRIDE * 4, inst)
        // voxels
        glUseProgram(vox)
        glUniformMatrix4fv(loc(vox, "modelViewMatrix"), 1, false, view, 0)
        glUniformMatrix4fv(loc(vox, "projectionMatrix"), 1, false, proj, 0)
        glUniformMatrix3fv(loc(vox, "uBodyInv"), 1, false, m.uBodyInv, 0)
        glUniform4fv(loc(vox, "uHairUp"), 1, m.uHairUp, 0)
        glUniformMatrix4fv(loc(vox, "uHead"), 1, false, m.uHead, 0)
        // the camera in head grid: uHead * camera position (rig space = world, the native rig never rotates)
        val h = m.uHead
        glUniform3f(loc(vox, "uCamH"), h[4] * camY + h[8] * camD + h[12], h[5] * camY + h[9] * camD + h[13], h[6] * camY + h[10] * camD + h[14])
        glUniform3fv(loc(vox, "uLight"), 1, m.uLight, 0); glUniform1f(loc(vox, "uLightI"), m.uLightI)
        glUniform1f(loc(vox, "uDesat"), m.uDesat); glUniform1f(loc(vox, "uScan"), m.uScan); glUniform1f(loc(vox, "uScanY"), m.uScanY)
        glUniform3fv(loc(vox, "uRimCol"), 1, m.uRimCol, 0)
        glUniform1f(loc(vox, "uRimStr"), th.n("rimStr").toFloat()); glUniform1f(loc(vox, "uRimPow"), th.n("rimPow").toFloat()); glUniform1f(loc(vox, "uRimAdd"), th.n("rimAdd").toFloat())
        val uni = m.d.uni
        glUniform3fv(loc(vox, "uSheen"), 1, uni.getValue("uSheen"), 0); glUniform3fv(loc(vox, "uHairRim"), 1, uni.getValue("uHairRim"), 0)
        glUniform1f(loc(vox, "uHairRimK"), th.n("hairRimK").toFloat()); glUniform3fv(loc(vox, "uUnder"), 1, uni.getValue("uUnder"), 0)
        glUniform1f(loc(vox, "uHairK"), m.uHairK); glUniform1f(loc(vox, "uHairOn"), m.uHairOn); glUniform1f(loc(vox, "uPartK"), m.uPartK)
        glUniform1f(loc(vox, "uTrans"), th.n("trans").toFloat()); glUniform1f(loc(vox, "uAccFloor"), m.uAccFloor); glUniform1f(loc(vox, "uKey"), th.n("key").toFloat())
        glUniform1f(loc(vox, "uWake"), m.uWake); glUniform1f(loc(vox, "uWakeW"), 0.25f)
        glUniform3fv(loc(vox, "uSkinFD"), 1, th.uSkinFD, 0); glUniform3fv(loc(vox, "uSkinRim"), 1, th.uSkinRim, 0); glUniform3fv(loc(vox, "uSkinFill"), 1, th.uSkinFill, 0)
        val ramp = uni.getValue("uRamp"); glUniform3f(loc(vox, "uRamp"), ramp[0], ramp[1], th.uRampZ)
        glUniform1f(loc(vox, "uMouthL"), uni.getValue("uMouthL")[0]); glUniform4fv(loc(vox, "uCheek"), 1, th.uCheek, 0); glUniform1f(loc(vox, "uSkinK"), m.uSkinK)
        glUniform3fv(loc(vox, "uApple"), 1, uni.getValue("uApple"), 0); glUniform1f(loc(vox, "uGlint"), m.uGlint); glUniform3fv(loc(vox, "uGlintC"), 1, uni.getValue("uGlintC"), 0)
        glUniform4fv(loc(vox, "uBand"), 1, uni.getValue("uBand"), 0); glUniform3fv(loc(vox, "uBandAx"), 1, uni.getValue("uBandAx"), 0)
        glUniform3fv(loc(vox, "uBandEx"), 1, uni.getValue("uBandEx"), 0); glUniform3fv(loc(vox, "uBandEz"), 1, uni.getValue("uBandEz"), 0)
        glBindVertexArray(voxVao)
        glDrawArraysInstanced(GL_TRIANGLES, 0, CHAMFER.size / 6, m.count)
        // the inner lantern
        glUseProgram(innerP)
        val mv = FloatArray(16); val nm = FloatArray(9)
        val ip = m.inner; val q = doubleArrayOf(ip[3].toDouble(), ip[4].toDouble(), ip[5].toDouble(), ip[6].toDouble())
        Q.compose(ip[0].toDouble(), ip[1].toDouble() - camY, ip[2].toDouble() - camD, q, ip[7].toDouble(), ip[8].toDouble(), ip[9].toDouble(), mv, 0)
        val r = FloatArray(9); Q.mat3(q, r)
        for (c in 0..2) for (rr in 0..2) nm[c * 3 + rr] = r[c * 3 + rr] / ip[7 + c]
        glUniformMatrix4fv(loc(innerP, "modelViewMatrix"), 1, false, mv, 0)
        glUniformMatrix4fv(loc(innerP, "projectionMatrix"), 1, false, proj, 0)
        glUniformMatrix3fv(loc(innerP, "normalMatrix"), 1, false, nm, 0)
        glUniform3fv(loc(innerP, "uLight"), 1, m.uLight, 0); glUniform1f(loc(innerP, "uLightI"), m.uLightI)
        glUniform3fv(loc(innerP, "uFill"), 1, uni.getValue("innerFill"), 0); glUniform3fv(loc(innerP, "uBase"), 1, uni.getValue("innerBase"), 0)
        glBindVertexArray(sphereVao)
        glDrawElements(GL_TRIANGLES, sphereCount, GL_UNSIGNED_SHORT, 0)
        // the warm outline hull (transparent overlay only)
        if (th.n("outline") > 0) {
            glCullFace(GL_FRONT)
            glUseProgram(outline)
            glUniformMatrix4fv(loc(outline, "modelViewMatrix"), 1, false, view, 0)
            glUniformMatrix4fv(loc(outline, "projectionMatrix"), 1, false, proj, 0)
            glUniform3fv(loc(outline, "uColor"), 1, uni.getValue("outline"), 0)
            glUniform1f(loc(outline, "uPx"), 1.5f * pxPerDp)
            glUniform2f(loc(outline, "uRes"), pxW.toFloat(), pxH.toFloat())
            glBindVertexArray(voxVao)
            glDrawArraysInstanced(GL_TRIANGLES, 0, CHAMFER.size / 6, m.count)
            glCullFace(GL_BACK)
        }
        // halo and floor (transparent, after the opaque pass)
        glDepthMask(false); glEnable(GL_BLEND)
        glUseProgram(quad); glBindVertexArray(quadVao)
        glUniformMatrix4fv(loc(quad, "projectionMatrix"), 1, false, proj, 0)
        if (th.n("haloA") > 0 && m.haloAlpha > 0) {
            glBlendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
            quadMv(0f, m.haloY, -7f, 18f, 18f)
            glUniform3fv(loc(quad, "uColor"), 1, m.haloColor, 0); glUniform1f(loc(quad, "uAlpha"), m.haloAlpha); glUniform1f(loc(quad, "uPow"), 2.6f)
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        }
        if (th.n("floorA") > 0) {
            if (th.n("floorAdd") > 0) glBlendFunc(GL_ONE, GL_ONE) else glBlendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
            quadMv(0f, -6.1f, 0f, 7.5f * m.floorScaleX, 1.2f)
            glUniform3fv(loc(quad, "uColor"), 1, m.floorColor, 0); glUniform1f(loc(quad, "uAlpha"), m.floorAlpha); glUniform1f(loc(quad, "uPow"), 1.4f)
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        }
        glDepthMask(true); glDisable(GL_BLEND)
        glBindVertexArray(0)
    }

    private fun quadMv(x: Float, y: Float, z: Float, sx: Float, sy: Float) {
        val mv = FloatArray(16); mv[0] = sx; mv[5] = sy; mv[10] = 1f; mv[15] = 1f; mv[12] = x; mv[13] = y - camY; mv[14] = z - camD
        glUniformMatrix4fv(loc(quad, "modelViewMatrix"), 1, false, mv, 0)
    }

    private fun floats(a: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(a).also { it.position(0) }

    private fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String) = glCreateShader(type).also {
            glShaderSource(it, src); glCompileShader(it)
            val ok = IntArray(1); glGetShaderiv(it, GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { glGetShaderInfoLog(it) }
        }
        return glCreateProgram().also {
            glAttachShader(it, shader(GL_VERTEX_SHADER, vs)); glAttachShader(it, shader(GL_FRAGMENT_SHADER, fs)); glLinkProgram(it)
            val ok = IntArray(1); glGetProgramiv(it, GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { glGetProgramInfoLog(it) }
        }
    }

    companion object {
        /** three.js chamferBox(0.07) from index.html: 44 triangles, flat normals (position, normal). */
        internal val CHAMFER: FloatArray = run {
            val b = 0.07; val h = 0.5 - b; val out = ArrayList<Double>()
            fun tri(a: DoubleArray, c0: DoubleArray, d0: DoubleArray, n: DoubleArray) {
                var c = c0; var d = d0
                val e1 = doubleArrayOf(c[0] - a[0], c[1] - a[1], c[2] - a[2]); val e2 = doubleArrayOf(d[0] - a[0], d[1] - a[1], d[2] - a[2])
                val cr = doubleArrayOf(e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0])
                if (cr[0] * n[0] + cr[1] * n[1] + cr[2] * n[2] < 0) { val t = c; c = d; d = t }
                for (v in arrayOf(a, c, d)) { out += v.toList(); out += n.toList() }
            }
            fun quad(a: DoubleArray, c: DoubleArray, d: DoubleArray, e: DoubleArray, n: DoubleArray) { tri(a, c, d, n); tri(a, d, e, n) }
            fun ax(i: Int, a: Double, b2: Double, c: Double): DoubleArray { val v = DoubleArray(3); v[i] = a; v[(i + 1) % 3] = b2; v[(i + 2) % 3] = c; return v }
            fun norm(v: DoubleArray): DoubleArray { val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); return doubleArrayOf(v[0] / l, v[1] / l, v[2] / l) }
            for (i in 0..2) for (s in intArrayOf(-1, 1)) {
                val n = ax(i, s.toDouble(), 0.0, 0.0)
                quad(ax(i, s * 0.5, -h, -h), ax(i, s * 0.5, h, -h), ax(i, s * 0.5, h, h), ax(i, s * 0.5, -h, h), n)
                for (t in intArrayOf(-1, 1)) {
                    val n2 = norm(ax(i, s.toDouble(), t.toDouble(), 0.0))
                    quad(ax(i, s * 0.5, t * h, -h), ax(i, s * h, t * 0.5, -h), ax(i, s * h, t * 0.5, h), ax(i, s * 0.5, t * h, h), n2)
                }
            }
            for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1))
                tri(doubleArrayOf(sx * 0.5, sy * h, sz * h), doubleArrayOf(sx * h, sy * 0.5, sz * h), doubleArrayOf(sx * h, sy * h, sz * 0.5), norm(doubleArrayOf(sx.toDouble(), sy.toDouble(), sz.toDouble())))
            FloatArray(out.size) { out[it].toFloat() }
        }

        /** three.js SphereGeometry(1, ws, hs): interleaved position + normal, and indices. */
        internal fun sphere(ws: Int, hs: Int): Pair<FloatArray, ShortArray> {
            val v = ArrayList<Float>(); val grid = Array(hs + 1) { IntArray(ws + 1) }; var idx = 0
            for (iy in 0..hs) { val vv = iy.toDouble() / hs
                for (ix in 0..ws) { val uu = ix.toDouble() / ws
                    val x = -cos(uu * 2 * PI) * sin(vv * PI); val y = cos(vv * PI); val z = sin(uu * 2 * PI) * sin(vv * PI)
                    v += x.toFloat(); v += y.toFloat(); v += z.toFloat()
                    val l = sqrt(x * x + y * y + z * z).let { if (it == 0.0) 1.0 else it }
                    v += (x / l).toFloat(); v += (y / l).toFloat(); v += (z / l).toFloat()
                    grid[iy][ix] = idx++ } }
            val ind = ArrayList<Short>()
            for (iy in 0 until hs) for (ix in 0 until ws) {
                val a = grid[iy][ix + 1]; val b = grid[iy][ix]; val c = grid[iy + 1][ix]; val d = grid[iy + 1][ix + 1]
                if (iy != 0) { ind += a.toShort(); ind += b.toShort(); ind += d.toShort() }
                if (iy != hs - 1) { ind += b.toShort(); ind += c.toShort(); ind += d.toShort() }
            }
            return v.toFloatArray() to ind.toShortArray()
        }

        private const val OETF = """
vec4 oetf(vec4 v){ return vec4(mix(pow(v.rgb, vec3(0.41666)) * 1.055 - vec3(0.055), v.rgb * 12.92, vec3(lessThanEqual(v.rgb, vec3(0.0031308)))), v.a); }
"""

        // The voxel shader of index.html, GLSL ES 3.00 (three.js ShaderMaterial + InstancedMesh conventions).
        private const val VOX_VS = """#version 300 es
precision highp float;
layout(location=0) in vec3 position; layout(location=1) in vec3 normal;
layout(location=2) in vec4 iM0; layout(location=3) in vec4 iM1; layout(location=4) in vec4 iM2; layout(location=5) in vec4 iM3;
layout(location=6) in vec3 aLit; layout(location=7) in vec3 aShd; layout(location=8) in vec4 aFx; layout(location=9) in vec4 aX; layout(location=10) in vec4 aY;
layout(location=11) in vec3 aN;
uniform mat4 modelViewMatrix, projectionMatrix;
out vec3 vN, vF, vLit, vShd, vP, vV, vNo, vSN; out vec4 vFx, vX, vY; out float vYw, vIn;
uniform mat3 uBodyInv; uniform vec4 uHairUp; out vec3 vHN;
centroid out vec3 vH; uniform mat4 uHead;
void main(){
  mat4 instanceMatrix = mat4(iM0, iM1, iM2, iM3);
  vNo = normal;
  vH = (uHead * instanceMatrix * vec4(position, 1.0)).xyz;
  vIn = dot(aN, aN) > 0.01 && normalize(mat3(instanceMatrix) * aN).z < -0.5 ? 1.0 : 0.0;
  vSN = normalize(mat3(modelViewMatrix) * (normalize(mat3(instanceMatrix) * aN) * 4.0 + mat3(instanceMatrix) * position));
  mat4 mv = modelViewMatrix * instanceMatrix;
  vec4 p = mv * vec4(position, 1.0);
  vF = normalize(mat3(mv) * normal);
  vN = dot(aN, aN) > 0.01 ? normalize(mat3(modelViewMatrix) * (mat3(instanceMatrix) * aN)) : vF;
  vLit = aLit; vShd = aShd; vFx = aFx; vX = aX; vY = aY; vP = position; vV = -p.xyz; vYw = instanceMatrix[3].y;
  vHN = vec3(0.0);
  if (aFx.w > 0.5 && aY.x > 0.5 && aY.x < 3.5) { vec3 nr = normalize(normalize(mat3(instanceMatrix) * vec3(0.0, 0.0, 1.0)) * 4.6 + mat3(instanceMatrix) * position);
    vHN = uBodyInv * nr; vSN = normalize(mat3(modelViewMatrix) * (nr + uHairUp.xyz) + vec3(0.0, 0.0, uHairUp.w)); }
  gl_Position = projectionMatrix * p;
}"""

        private const val VOX_FS = """#version 300 es
precision highp float;
uniform vec3 uLight, uRimCol, uSheen, uHairRim, uUnder;
uniform float uLightI, uDesat, uScan, uScanY, uRimStr, uRimPow, uRimAdd, uHairRimK, uHairK, uHairOn, uPartK, uTrans, uAccFloor, uKey, uWake, uWakeW;
uniform vec3 uSkinFD, uSkinRim, uSkinFill, uApple, uGlintC, uRamp; uniform vec4 uCheek; uniform float uMouthL, uGlint, uSkinK;
in vec3 vN, vF, vLit, vShd, vP, vV, vNo, vSN; in vec4 vFx, vX, vY; in float vYw, vIn; uniform vec3 uCamH;
uniform vec4 uBand; uniform vec3 uBandAx, uBandEx, uBandEz; in vec3 vHN;
centroid in vec3 vH;
out vec4 fragColor;
$OETF
float bit(float m, float b){ return mod(floor(m / b + 0.01), 2.0); }
void main(){
  float clw = vY.x;
  vec3 vd = normalize(uCamH - vH); float ivz = 1.0 / max(vd.z, 0.05);
  vec2 fq = vH.xy + vd.xy * (3.5 - vH.z) * ivz, fc = vH.xy + vd.xy * (2.2 - vH.z) * ivz;
  bool through = (vIn > 0.5 && dot(normalize(vV), normalize(vN)) < -0.5) || (!(clw > 3.5 && clw < 4.5) && clw < 6.5 && vd.z > 0.3
    && ((vH.z < 2.9 && abs(fq.x) < (fq.y > -4.0 ? 3.4 : 2.8) && fq.y > -4.9 && fq.y < 1.2) || (vH.z < 1.6 && abs(fc.x) < 1.9 && fc.y > -5.3 && fc.y < -4.6)));
  if (through) { fragColor = oetf(vec4(uSkinFD * uSkinK, 1.0)); return; }
  float cl = vY.x;
  bool hair = (cl > 0.5 && cl < 3.5) || (cl > 5.5 && cl < 6.5), pixel = cl > 6.5, face = cl > 3.5 && cl < 4.5, skin = cl > 3.5 && cl < 5.5;
  bool sideHair = cl > 1.5 && cl < 3.5;
  vec3 N = normalize(vN), F = normalize(vF), V = normalize(vV);
  if (face) N = normalize(mix(F, normalize(vSN), 0.92));
  bool gloss = hair && vFx.w > 0.5 && dot(vHN, vHN) > 0.01;
  if (gloss) N = normalize(vSN);
  if (cl > 5.5 && cl < 6.5) { if (dot(N, V) < 0.0) N = reflect(N, V); N = normalize(mix(N, V, 0.65)); }
  float yl = vX.x + vP.y * 1.17, xl = vY.w * 4.5 + vP.x * 1.17;
  float aboveMouth = smoothstep(uMouthL + 0.3, uMouthL + 1.0, yl);
  vec3 Ld = normalize(vec3(-0.45, 0.75, 0.55));
  float lam = clamp(dot(N, Ld) * 0.85 + 0.2, 0.0, 1.0); lam = lam * lam * (3.0 - 2.0 * lam);
  if (hair) lam = max(lam, cl > 2.5 && cl < 3.5 ? 0.45 : 0.32);
  if (cl > 5.5 && cl < 6.5) lam = 0.5 + 0.1 * lam;
  vec3 shd = skin ? mix(uSkinFD, vShd, smoothstep(0.3, 0.8, abs(N.x)) * smoothstep(uMouthL + 1.0, uMouthL + 2.5, yl)) : vShd;
  vec3 col = mix(shd, vLit, lam);
  if (face) {
    float t = clamp((uRamp.x - yl) / (uRamp.x - uRamp.y), 0.0, 1.0);
    vec3 sn = normalize(vSN);
    float sd = smoothstep(0.04, 0.72, abs(sn.x)) * (sn.x > 0.0 ? 1.0 : 0.8);
    col = mix(mix(vLit, uSkinFD, clamp(uRamp.z * t, 0.0, 1.0)), uCheek.rgb, uCheek.a * sd);
    float ap = exp(-pow(length(vec2(abs(xl) - uApple.x, yl - uApple.y)) / uApple.z, 2.0)) * aboveMouth;
    col *= 1.0 + ap * vec3(0.03, 0.012, 0.0);
    float wall = 1.0 - smoothstep(0.25, 0.75, vNo.z);
    float jw = vNo.y < -0.5 ? step(yl, uMouthL - 1.3) : step(2.6, abs(xl)) * step(0.0, vNo.x * xl);
    col = mix(col, vNo.y < -0.5 ? uUnder : uSkinFD, wall * 0.3 * jw * step(yl, uMouthL + 0.5));
  }
  float facet = 0.08 * min(1.0, vX.z * 4.0) * (clamp(dot(F, Ld), -0.5, 1.0) - clamp(dot(N, Ld), -0.5, 1.0));
  if (hair) facet *= 0.4 + 0.6 * lam;
  col *= 1.0 + facet;
  vec3 a = abs(vP) * 2.0;
  float mx = max(a.x, max(a.y, a.z)), mn = min(a.x, min(a.y, a.z)), md = a.x + a.y + a.z - mx - mn;
  float e = smoothstep(0.66, 0.99, md);
  col *= vFx.x * (1.0 - vX.z * e);
  if (skin) col *= 1.0 + (1.0 - vFx.x) * vec3(0.0, -0.04, -0.07);
  if (skin && !face) col = mix(col, uUnder, 0.35 * max(-N.y, 0.0));
  col *= skin ? min(uKey, 1.0) : uKey;
  col = mix(col, vec3(dot(col, vec3(0.299, 0.587, 0.114))), uDesat);
  if (skin) col *= uSkinK;
  if (hair && vFx.w > 0.0) {
    if (gloss) col *= 1.0 - 0.09 * smoothstep(0.3, 0.5, abs(vP.x));
    float bd = vFx.w * 0.5;
    if (gloss) { vec3 hn = normalize(vHN); float al = acos(clamp(dot(hn, uBandAx), -1.0, 1.0)), th = atan(dot(hn, uBandEx), dot(hn, uBandEz));
      float dm = smoothstep(0.12, 0.7, sin(3.14159 * fract((th - uBand.w) / uBand.z)));
      bd = dm * exp(-pow((al - uBand.x) / (uBand.y * (0.4 + 0.6 * dm)), 2.0)) * smoothstep(1.55, 1.1, abs(th)); }
    bd *= min(uKey, 1.0) * (0.55 + 0.45 * lam);
    col = col * (1.0 + 0.25 * bd) + (gloss ? uSheen * 0.18 * bd : vec3(0.0)) + uGlintC * 0.15 * exp(-pow((vY.w - uGlint) * 2.5, 2.0)) * bd;
  }
  vec3 Lc = uLight * uLightI;
  float bnc = vFx.z * (0.15 * e + 0.035);
  if (hair) bnc *= smoothstep(0.15, 0.6, lam);
  if (sideHair) col += vec3(0.07, 0.06, 0.08) * e * 0.8;
  else if (face) col += vec3(0.03, 0.022, 0.014) * e;
  else col += Lc * bnc;
  if (skin) col += uSkinFill * 0.03;
  else col += uLight * uLightI * vX.x * uTrans * 0.22;
  float m = vY.y;
  if (m > 0.5) {
    float hl = 0.0;
    float h0 = skin ? 0.37 : 0.22;
    if (bit(m, 1.0) > 0.5) hl = max(hl, smoothstep(h0, 0.5, vP.y));
    if (bit(m, 2.0) > 0.5) hl = max(hl, smoothstep(h0, 0.5, -vP.x));
    if (bit(m, 4.0) > 0.5) hl = max(hl, smoothstep(h0, 0.5, vP.x));
    if (bit(m, 8.0) > 0.5) hl = max(hl, smoothstep(-0.2, -0.5, vP.y));
    float w = smoothstep(uWake + uWakeW, uWake - uWakeW, vY.w) * smoothstep(0.2, 0.7, vNo.z);
    if (bit(m, 16.0) > 0.5) col = mix(col, uLight * uPartK, smoothstep(0.3, 0.5, abs(vP.x)) * vY.z);
    else col = mix(col, uLight * uHairK, hl * vY.z * uHairOn * w);
  }
  col += uLight * uScan * vFx.z * (0.25 + e) * 0.35 * exp(-pow((vYw - uScanY) * 1.4, 2.0));
  vec3 em = vX.w < -0.5 ? vLit : vLit * uLight * (uAccFloor + 0.95 * max(uLightI, vX.w));
  col = mix(col, em, vFx.y);
  vec3 Ns = face || gloss ? N : normalize(mix(F, N, 0.5));
  if (skin) col += vec3(0.10, 0.08, 0.06) * pow(max(dot(Ns, normalize(Ld + V)), 0.0), 24.0) * aboveMouth;
  else col += vec3(0.18) * pow(max(dot(Ns, normalize(Ld + V)), 0.0), 48.0) * (1.0 - vFx.y) * (hair ? 0.45 : 1.0);
  float f0 = 1.0 - clamp(dot(N, V), 0.0, 1.0);
  float fr = pow(f0, 4.0) * (1.0 - vFx.y);
  if (hair) col += uHairRim * fr * 0.35 * uHairRimK;
  else if (skin) col += uSkinFill * fr * uRimAdd * 0.5;
  else if (!pixel) col += uLight * fr * uRimAdd * (0.3 + uLightI);
  col = mix(col, skin ? uSkinRim : uRimCol, pow(f0, skin ? max(uRimPow, 6.0) : uRimPow) * uRimStr * (skin ? 0.6 : 1.0) * (1.0 - vFx.y));
  fragColor = oetf(vec4(col, 1.0));
}"""

        private const val OUT_VS = """#version 300 es
precision highp float;
layout(location=0) in vec3 position;
layout(location=2) in vec4 iM0; layout(location=3) in vec4 iM1; layout(location=4) in vec4 iM2; layout(location=5) in vec4 iM3;
uniform mat4 modelViewMatrix, projectionMatrix; uniform float uPx; uniform vec2 uRes;
void main(){
  mat4 mv = modelViewMatrix * mat4(iM0, iM1, iM2, iM3);
  vec4 p = mv * vec4(position, 1.0);
  vec4 c = projectionMatrix * p;
  vec2 sd = (projectionMatrix * vec4(normalize(mat3(mv) * position), 0.0)).xy;
  float l = length(sd); sd = l > 1e-5 ? sd / l : vec2(0.0);
  c.xy += sd * uPx * 2.0 / uRes * c.w;
  vec4 cz = projectionMatrix * vec4(p.xy, p.z - 1.6, 1.0);
  gl_Position = vec4(c.xy, cz.z / cz.w * c.w, c.w);
}"""
        private const val OUT_FS = """#version 300 es
precision highp float;
uniform vec3 uColor; out vec4 fragColor;
$OETF
void main(){ fragColor = oetf(vec4(uColor, 1.0)); }"""

        private const val INNER_VS = """#version 300 es
precision highp float;
layout(location=0) in vec3 position; layout(location=1) in vec3 normal;
uniform mat4 modelViewMatrix, projectionMatrix; uniform mat3 normalMatrix;
out vec3 vN, vO;
void main(){ vec3 p = position; float t = clamp((-p.y - 0.15) / 0.85, 0.0, 1.0); p.xz *= max(1.0 - 0.3 * pow(t, 1.6) - 0.6 * t, 0.2);
  vO = normal; vN = normalize(normalMatrix * normal); gl_Position = projectionMatrix * modelViewMatrix * vec4(p, 1.0); }"""
        private const val INNER_FS = """#version 300 es
precision highp float;
uniform vec3 uLight, uFill, uBase; uniform float uLightI; in vec3 vN, vO; out vec4 fragColor;
$OETF
void main(){ float c = pow(clamp(vN.z, 0.0, 1.0), 1.4);
  float front = smoothstep(0.2, 0.6, vO.z), faceM = smoothstep(-0.3, 0.2, vO.z) * (1.0 - smoothstep(0.15, 0.4, vO.y));
  float band = smoothstep(0.0, 0.15, vO.y) * (1.0 - smoothstep(0.36, 0.5, vO.y));
  vec3 L = uBase + uLight * uLightI * (0.08 + 0.75 * c * c) * front * band;
  fragColor = oetf(vec4(mix(L, uFill, faceM), 1.0)); }"""

        private const val QUAD_VS = """#version 300 es
precision highp float;
layout(location=0) in vec2 position; layout(location=1) in vec2 uv;
uniform mat4 modelViewMatrix, projectionMatrix; out vec2 vUv;
void main(){ vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 0.0, 1.0); }"""
        private const val QUAD_FS = """#version 300 es
precision highp float;
uniform vec3 uColor; uniform float uAlpha, uPow; in vec2 vUv; out vec4 fragColor;
$OETF
void main(){ float d = length(vUv - 0.5) * 2.0; float a = pow(max(1.0 - d, 0.0), uPow); fragColor = oetf(vec4(uColor * a * uAlpha, a * uAlpha)); }"""
    }
}
