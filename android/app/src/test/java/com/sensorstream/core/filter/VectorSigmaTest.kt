package com.sensorstream.core.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorSigmaTest {
    @Test fun combinesPerAxisDeviation() {
        // x alternates +-1 (sd 1), y alternates +-2 (sd 2): combined sqrt(1 + 4)
        val pts = List(1000) { i -> floatArrayOf(if (i % 2 == 0) 1f else -1f, if (i % 2 == 0) 2f else -2f) }
        assertEquals(Math.sqrt(5.0).toFloat(), vectorSigma(pts), 1e-2f)
    }

    @Test fun skipsMissingAndNaNAndNeedsTwoSamples() {
        assertTrue(vectorSigma(listOf(floatArrayOf(1f))).isNaN())
        val pts = listOf(floatArrayOf(1f, Float.NaN), null, floatArrayOf(3f, Float.NaN))
        assertEquals(Math.sqrt(2.0).toFloat(), vectorSigma(pts), 1e-4f)
    }
}
