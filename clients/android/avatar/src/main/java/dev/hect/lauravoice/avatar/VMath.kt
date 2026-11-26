package dev.hect.lauravoice.avatar

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

// three.js conventions: quaternions (x, y, z, w), column-major 4x4 matrices.
internal const val TAU = Math.PI * 2

internal fun clamp(v: Double, a: Double, b: Double) = if (v < a) a else if (v > b) b else v
internal fun smooth(a: Double, b: Double, x: Double): Double { val u = clamp((x - a) / (b - a), 0.0, 1.0); return u * u * (3 - 2 * u) }
internal fun damp(k: Double, dt: Double) = 1 - exp(-k * dt)

internal class V3(var x: Double = 0.0, var y: Double = 0.0, var z: Double = 0.0) {
    fun set(a: Double, b: Double, c: Double) = apply { x = a; y = b; z = c }
    fun copy(v: V3) = set(v.x, v.y, v.z)
    fun add(a: Double, b: Double, c: Double) = set(x + a, y + b, z + c)
    fun addScaled(v: V3, k: Double) = set(x + v.x * k, y + v.y * k, z + v.z * k)
    fun len() = sqrt(x * x + y * y + z * z)
    fun lenSq() = x * x + y * y + z * z
    fun normalize(): V3 { val l = len(); return if (l > 0) set(x / l, y / l, z / l) else this }
    fun sub(a: V3, b: V3) = set(a.x - b.x, a.y - b.y, a.z - b.z)
    fun cross(a: V3, b: V3) = set(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)
    fun lerp(v: V3, k: Double) = set(x + (v.x - x) * k, y + (v.y - y) * k, z + (v.z - z) * k)
    fun dist(v: V3): Double { val a = x - v.x; val b = y - v.y; val c = z - v.z; return sqrt(a * a + b * b + c * c) }
    fun rot(q: DoubleArray): V3 {
        val qx = q[0]; val qy = q[1]; val qz = q[2]; val qw = q[3]
        val tx = 2 * (qy * z - qz * y); val ty = 2 * (qz * x - qx * z); val tz = 2 * (qx * y - qy * x)
        return set(x + qw * tx + qy * tz - qz * ty, y + qw * ty + qz * tx - qx * tz, z + qw * tz + qx * ty - qy * tx)
    }
}

internal object Q {
    fun eulerYXZ(x: Double, y: Double, z: Double, o: DoubleArray): DoubleArray {
        val c1 = cos(x / 2); val c2 = cos(y / 2); val c3 = cos(z / 2)
        val s1 = sin(x / 2); val s2 = sin(y / 2); val s3 = sin(z / 2)
        o[0] = s1 * c2 * c3 + c1 * s2 * s3; o[1] = c1 * s2 * c3 - s1 * c2 * s3
        o[2] = c1 * c2 * s3 - s1 * s2 * c3; o[3] = c1 * c2 * c3 + s1 * s2 * s3
        return o
    }

    fun mul(a: DoubleArray, b: DoubleArray, o: DoubleArray): DoubleArray {
        val ax = a[0]; val ay = a[1]; val az = a[2]; val aw = a[3]; val bx = b[0]; val by = b[1]; val bz = b[2]; val bw = b[3]
        o[0] = ax * bw + aw * bx + ay * bz - az * by; o[1] = ay * bw + aw * by + az * bx - ax * bz
        o[2] = az * bw + aw * bz + ax * by - ay * bx; o[3] = aw * bw - ax * bx - ay * by - az * bz
        return o
    }

    fun inv(a: DoubleArray, o: DoubleArray): DoubleArray { o[0] = -a[0]; o[1] = -a[1]; o[2] = -a[2]; o[3] = a[3]; return o }

    fun axisAngle(ax: Double, ay: Double, az: Double, angle: Double, o: DoubleArray): DoubleArray {
        val s = sin(angle / 2); o[0] = ax * s; o[1] = ay * s; o[2] = az * s; o[3] = cos(angle / 2); return o
    }

    /** Rotation from the basis columns (e1, e2, e3), as Matrix4.makeBasis + Quaternion.setFromRotationMatrix. */
    fun basis(e1: V3, e2: V3, e3: V3, o: DoubleArray): DoubleArray {
        val m11 = e1.x; val m12 = e2.x; val m13 = e3.x
        val m21 = e1.y; val m22 = e2.y; val m23 = e3.y
        val m31 = e1.z; val m32 = e2.z; val m33 = e3.z
        val tr = m11 + m22 + m33
        if (tr > 0) { val s = 0.5 / sqrt(tr + 1.0); o[3] = 0.25 / s; o[0] = (m32 - m23) * s; o[1] = (m13 - m31) * s; o[2] = (m21 - m12) * s }
        else if (m11 > m22 && m11 > m33) { val s = 2.0 * sqrt(1.0 + m11 - m22 - m33); o[3] = (m32 - m23) / s; o[0] = 0.25 * s; o[1] = (m12 + m21) / s; o[2] = (m13 + m31) / s }
        else if (m22 > m33) { val s = 2.0 * sqrt(1.0 + m22 - m11 - m33); o[3] = (m13 - m31) / s; o[0] = (m12 + m21) / s; o[1] = 0.25 * s; o[2] = (m23 + m32) / s }
        else { val s = 2.0 * sqrt(1.0 + m33 - m11 - m22); o[3] = (m21 - m12) / s; o[0] = (m13 + m31) / s; o[1] = (m23 + m32) / s; o[2] = 0.25 * s }
        return o
    }

    /** Matrix4.compose into [o] at [off] (floats, column-major). */
    fun compose(px: Double, py: Double, pz: Double, q: DoubleArray, sx: Double, sy: Double, sz: Double, o: FloatArray, off: Int) {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        val x2 = x + x; val y2 = y + y; val z2 = z + z
        val xx = x * x2; val xy = x * y2; val xz = x * z2; val yy = y * y2; val yz = y * z2; val zz = z * z2
        val wx = w * x2; val wy = w * y2; val wz = w * z2
        o[off] = ((1 - (yy + zz)) * sx).toFloat(); o[off + 1] = ((xy + wz) * sx).toFloat(); o[off + 2] = ((xz - wy) * sx).toFloat(); o[off + 3] = 0f
        o[off + 4] = ((xy - wz) * sy).toFloat(); o[off + 5] = ((1 - (xx + zz)) * sy).toFloat(); o[off + 6] = ((yz + wx) * sy).toFloat(); o[off + 7] = 0f
        o[off + 8] = ((xz + wy) * sz).toFloat(); o[off + 9] = ((yz - wx) * sz).toFloat(); o[off + 10] = ((1 - (xx + yy)) * sz).toFloat(); o[off + 11] = 0f
        o[off + 12] = px.toFloat(); o[off + 13] = py.toFloat(); o[off + 14] = pz.toFloat(); o[off + 15] = 1f
    }

    /** 3x3 rotation (column-major) of a unit quaternion. */
    fun mat3(q: DoubleArray, o: FloatArray) {
        val m = FloatArray(16); compose(0.0, 0.0, 0.0, q, 1.0, 1.0, 1.0, m, 0)
        o[0] = m[0]; o[1] = m[1]; o[2] = m[2]; o[3] = m[4]; o[4] = m[5]; o[5] = m[6]; o[6] = m[8]; o[7] = m[9]; o[8] = m[10]
    }
}
