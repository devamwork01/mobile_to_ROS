package com.sensorstream.ui.viz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OrientationSourceTest {
    private fun euler(rv: FloatArray) = Projection.eulerDeg(Projection.rotationVectorToMatrix(rv))

    @Test fun `euler to rotation round-trips`() {
        for ((r, p, y) in listOf(Triple(0f, 0f, 0f), Triple(10f, -20f, 30f), Triple(-75f, 45f, 300f), Triple(170f, 5f, 359f))) {
            val (r2, p2, y2) = euler(Projection.rotationFromEuler(r, p, y))
            assertEquals(r, r2, 0.01f); assertEquals(p, p2, 0.01f)
            assertEquals(0f, ((y2 - y + 540f) % 360f) - 180f, 0.01f)
        }
    }

    @Test fun `each orientation sensor is drawn from its own values`() {
        // QA 2026-10-02: Game Orientation and Orientation (deprecated) showed the rotation vector.
        val rv = floatArrayOf(0.1f, 0.2f, 0.3f, 0.927f)
        val game = floatArrayOf(0.0f, 0.0f, 0.7f, 0.714f)
        assertTrue(OrientationSource.rotationVector(11, rv)!!.contentEquals(rv))
        assertTrue(OrientationSource.rotationVector(15, game)!!.contentEquals(game))
        assertTrue(OrientationSource.rotationVector(20, game)!!.contentEquals(game))
        assertNull(OrientationSource.rotationVector(1, rv))
        assertNull(OrientationSource.rotationVector(11, null))
    }

    @Test fun `legacy orientation angles match the rotation vector on a real phone`() {
        // Same moment on the S25: TYPE_ORIENTATION [azimuth, pitch, roll] vs TYPE_ROTATION_VECTOR.
        val legacy = floatArrayOf(88.07f, -1.17f, -0.79f)
        val rvq = Projection.quatFromRotationVector(floatArrayOf(0.012f, -0.002f, -0.695f, 0.719f))
        val q = Projection.quatFromRotationVector(OrientationSource.rotationVector(3, legacy)!!)
        val dot = abs(q[0] * rvq[0] + q[1] * rvq[1] + q[2] * rvq[2] + q[3] * rvq[3])
        assertTrue("same attitude (|q.q'| = $dot)", dot > 0.999f)
        val (roll, pitch, yaw) = euler(OrientationSource.rotationVector(3, legacy)!!)
        assertEquals(0.79f, roll, 0.01f); assertEquals(-1.17f, pitch, 0.01f); assertEquals(88.07f, yaw, 0.01f)
    }
}
