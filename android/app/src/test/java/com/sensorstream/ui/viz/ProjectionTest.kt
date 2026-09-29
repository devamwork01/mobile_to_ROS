package com.sensorstream.ui.viz

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectionTest {
    private fun near(a: Float, b: Float) = assertEquals(b.toDouble(), a.toDouble(), 1e-3)

    @Test fun identityKeepsAxes() {
        val r = Projection.rotationVectorToMatrix(floatArrayOf(0f, 0f, 0f))
        val v = Projection.rotate(r, Vec3(0f, 1f, 0f))
        near(v.x, 0f); near(v.y, 1f); near(v.z, 0f)
    }

    @Test fun ninetyAboutZMapsXtoY() {
        val s = kotlin.math.sin(Math.PI / 4).toFloat() // rot vector = axis * sin(theta/2)
        val r = Projection.rotationVectorToMatrix(floatArrayOf(0f, 0f, s))
        val v = Projection.rotate(r, Vec3(1f, 0f, 0f))
        near(v.x, 0f); near(v.y, 1f); near(v.z, 0f)
    }

    @Test fun eulerOfIdentityIsZero() {
        val r = Projection.rotationVectorToMatrix(floatArrayOf(0f, 0f, 0f))
        val (roll, pitch, yaw) = Projection.eulerDeg(r)
        near(roll, 0f); near(pitch, 0f); near(yaw, 0f)
    }

    @Test fun threeMatrixAppliesRxMinus90BasisChange() {
        // At rest (identity Android quaternion), the laptop maps ENU up (device +Z when flat) to
        // Three +Y (screen up) via Rx(-90°). The phone must do the same to match the dashboard.
        val r = Projection.threeMatrix(floatArrayOf(0f, 0f, 0f))
        val up = Projection.rotate(r, Vec3(0f, 0f, 1f)) // device +Z
        near(up.x, 0f); near(up.y, 1f); near(up.z, 0f)
        // device +Y (ENU north) maps to -Z (into screen)
        val fwd = Projection.rotate(r, Vec3(0f, 1f, 0f))
        near(fwd.x, 0f); near(fwd.y, 0f); near(fwd.z, -1f)
    }

    @Test fun handlesFourElementRotationVector() {
        // Some devices report [x,y,z,w]; w should be used directly when present.
        val s = kotlin.math.sin(Math.PI / 4).toFloat()
        val r = Projection.rotationVectorToMatrix(floatArrayOf(0f, 0f, s, s))
        val v = Projection.rotate(r, Vec3(1f, 0f, 0f))
        near(v.x, 0f); near(v.y, 1f); near(v.z, 0f)
    }

    private fun assertMat(expected: FloatArray, actual: FloatArray) {
        for (k in 0 until 9) org.junit.Assert.assertEquals("m[$k]", expected[k], actual[k], 1e-5f)
    }

    @Test
    fun recenterMakesReferencePoseIdentity() {
        val r = Projection.threeMatrix(floatArrayOf(0.2f, -0.35f, 0.1f))
        assertMat(Projection.IDENTITY, Projection.relativeTo(r, r))
    }

    @Test
    fun recenterWithIdentityReferenceIsNoOp() {
        val r = Projection.threeMatrix(floatArrayOf(0.1f, 0.4f, -0.2f))
        assertMat(r, Projection.relativeTo(Projection.IDENTITY, r))
    }

    @Test
    fun relativeRotationComposes() {
        // Applying the relative matrix == undoing the reference after the current rotation.
        val ref = Projection.threeMatrix(floatArrayOf(0.3f, 0f, 0.1f))
        val r = Projection.threeMatrix(floatArrayOf(-0.1f, 0.25f, 0.05f))
        val v = Vec3(0.3f, -0.7f, 0.2f)
        val a = Projection.rotate(Projection.relativeTo(ref, r), v)
        val b = Projection.rotate(Projection.transpose(ref), Projection.rotate(r, v))
        org.junit.Assert.assertEquals(b.x, a.x, 1e-5f)
        org.junit.Assert.assertEquals(b.y, a.y, 1e-5f)
        org.junit.Assert.assertEquals(b.z, a.z, 1e-5f)
    }
}
