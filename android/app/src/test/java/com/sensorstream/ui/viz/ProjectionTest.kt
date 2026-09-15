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
}
