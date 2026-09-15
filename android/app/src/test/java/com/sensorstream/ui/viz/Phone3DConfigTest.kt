package com.sensorstream.ui.viz

import org.junit.Assert.assertTrue
import org.junit.Test

class Phone3DConfigTest {
    @Test fun identityPlacesAxesInScreenConvention() {
        val r = Projection.rotationVectorToMatrix(floatArrayOf(0f, 0f, 0f))
        val ep = Phone3DConfig.axisEndpoints(r, cx = 100f, cy = 100f, len = 50f)
        val x = ep["X"]!!
        val y = ep["Y"]!!
        // +X projects to the right of center; +Y projects above center (smaller screen-y).
        assertTrue("X to the right: ${x.first}", x.first > 100f)
        assertTrue("Y above center: ${y.second}", y.second < 100f)
    }
}
