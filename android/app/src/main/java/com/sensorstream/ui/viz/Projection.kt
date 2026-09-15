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

    /** Normalized quaternion `[x,y,z,w]` from a rotation vector (`[x,y,z]` w-derived, or `[x,y,z,w]`). */
    fun quatFromRotationVector(rv: FloatArray): FloatArray {
        val x = rv.getOrElse(0) { 0f }
        val y = rv.getOrElse(1) { 0f }
        val z = rv.getOrElse(2) { 0f }
        val w = if (rv.size >= 4) rv[3] else {
            val t = 1f - (x * x + y * y + z * z)
            if (t > 0f) sqrt(t) else 0f
        }
        val n = sqrt(x * x + y * y + z * z + w * w).let { if (it == 0f) 1f else it }
        return floatArrayOf(x / n, y / n, z / n, w / n)
    }

    /** 3×3 row-major rotation matrix from a quaternion `[x,y,z,w]`. */
    fun quatToMatrix(q: FloatArray): FloatArray {
        val qx = q[0]; val qy = q[1]; val qz = q[2]; val qw = q[3]
        val xx = qx * qx; val yy = qy * qy; val zz = qz * qz
        val xy = qx * qy; val xz = qx * qz; val yz = qy * qz
        val wx = qw * qx; val wy = qw * qy; val wz = qw * qz
        return floatArrayOf(
            1 - 2 * (yy + zz), 2 * (xy - wz), 2 * (xz + wy),
            2 * (xy + wz), 1 - 2 * (xx + zz), 2 * (yz - wx),
            2 * (xz - wy), 2 * (yz + wx), 1 - 2 * (xx + yy),
        )
    }

    /** Raw device→ENU rotation matrix (Android convention). Used for Euler + tests. */
    fun rotationVectorToMatrix(rv: FloatArray): FloatArray = quatToMatrix(quatFromRotationVector(rv))

    /**
     * Map the Android rotation-vector quaternion (device→ENU, Z-up) into the SAME render space the
     * laptop's Three.js viz uses: a fixed Rx(−90°) basis change (ENU Z-up → Y-up), so the phone's
     * on-device 3D orientation matches the laptop exactly. Ported verbatim from
     * `laptop/webapp/src/lib/orient.js` (androidToThree). Returns quaternion `[x,y,z,w]`.
     */
    fun androidToThree(q: FloatArray): FloatArray {
        val s = kotlin.math.sin(-Math.PI / 4).toFloat()
        val c = kotlin.math.cos(-Math.PI / 4).toFloat()
        val bx = s; val by = 0f; val bz = 0f; val bw = c // Rx(-90°)
        val ax = q[0]; val ay = q[1]; val az = q[2]; val aw = q[3]
        return floatArrayOf(
            bw * ax + bx * aw + by * az - bz * ay, // x
            bw * ay - bx * az + by * aw + bz * ax, // y
            bw * az + bx * ay - by * ax + bz * aw, // z
            bw * aw - bx * ax - by * ay - bz * az, // w
        )
    }

    /** Render-space (Three-matching) rotation matrix for the 3D phone. */
    fun threeMatrix(rv: FloatArray): FloatArray = quatToMatrix(androidToThree(quatFromRotationVector(rv)))

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

    /**
     * Roll / pitch / yaw (degrees) from a device→world (Android) rotation matrix, matching the
     * laptop's `eulerFromQuat` convention exactly (`lib/orient.js`) so on-device Euler equals the
     * dashboard's. Yaw is normalized to [0,360).
     */
    fun eulerDeg(r: FloatArray): Triple<Float, Float, Float> {
        val rad = 180f / Math.PI.toFloat()
        val roll = atan2(-r[6], r[8]) * rad
        val pitch = asin((-r[7]).coerceIn(-1f, 1f)) * rad
        val yaw = ((atan2(r[1], r[4]) * rad) % 360f + 360f) % 360f
        return Triple(roll, pitch, yaw)
    }
}
