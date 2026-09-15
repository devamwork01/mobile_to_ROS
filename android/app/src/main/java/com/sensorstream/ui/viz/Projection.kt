package com.sensorstream.ui.viz

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

data class Vec3(val x: Float, val y: Float, val z: Float)

/**
 * Pure, display-only projection helpers for the pseudo-3D phone. These consume the EXISTING
 * rotation-vector values the UI already receives and never alter any acquisition/fusion math.
 * Kept free of Android types so they unit-test on the JVM.
 */
object Projection {

    /** Rotation vector (`[x,y,z]` with w derived, or `[x,y,z,w]`) → 3×3 row-major rotation matrix. */
    fun rotationVectorToMatrix(rv: FloatArray): FloatArray {
        val x = rv.getOrElse(0) { 0f }
        val y = rv.getOrElse(1) { 0f }
        val z = rv.getOrElse(2) { 0f }
        val w = if (rv.size >= 4) rv[3] else {
            val t = 1f - (x * x + y * y + z * z)
            if (t > 0f) sqrt(t) else 0f
        }
        // Normalize to guard against accumulated drift.
        val n = sqrt(x * x + y * y + z * z + w * w).let { if (it == 0f) 1f else it }
        val qx = x / n; val qy = y / n; val qz = z / n; val qw = w / n
        val xx = qx * qx; val yy = qy * qy; val zz = qz * qz
        val xy = qx * qy; val xz = qx * qz; val yz = qy * qz
        val wx = qw * qx; val wy = qw * qy; val wz = qw * qz
        return floatArrayOf(
            1 - 2 * (yy + zz), 2 * (xy - wz), 2 * (xz + wy),
            2 * (xy + wz), 1 - 2 * (xx + zz), 2 * (yz - wx),
            2 * (xz - wy), 2 * (yz + wx), 1 - 2 * (xx + yy),
        )
    }

    /** Apply a row-major 3×3 matrix to a vector. */
    fun rotate(r: FloatArray, v: Vec3): Vec3 = Vec3(
        r[0] * v.x + r[1] * v.y + r[2] * v.z,
        r[3] * v.x + r[4] * v.y + r[5] * v.z,
        r[6] * v.x + r[7] * v.y + r[8] * v.z,
    )

    /**
     * Orthographic screen projection with a fixed, slightly-elevated camera so the phone reads as
     * floating: +X → right, +Y → up, +Z → toward the viewer (drawn with a gentle downward tilt).
     * Returns (dxPixels, dyPixels) offsets from a center; caller adds the center + flips Y for screen.
     */
    fun project(v: Vec3, scale: Float): Pair<Float, Float> {
        val tilt = 0.35f // radians of downward camera tilt for depth
        val ct = kotlin.math.cos(tilt); val st = kotlin.math.sin(tilt)
        val sx = v.x
        val sy = v.y * ct - v.z * st
        return Pair(sx * scale, sy * scale)
    }

    /** Roll (X), pitch (Y), yaw (Z) in degrees from a row-major rotation matrix. */
    fun eulerDeg(r: FloatArray): Triple<Float, Float, Float> {
        val rad = 180f / Math.PI.toFloat()
        val pitch = asin(-r[6].coerceIn(-1f, 1f))
        val roll = atan2(r[7], r[8])
        val yaw = atan2(r[3], r[0])
        return Triple(roll * rad, pitch * rad, yaw * rad)
    }
}
