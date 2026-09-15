package com.sensorstream.ui.signal

import android.hardware.Sensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalCatalogTest {
    @Test fun mapsMotionAndOrientation() {
        assertEquals("Acceleration", SignalCatalog.of(Sensor.TYPE_ACCELEROMETER).humanName)
        assertEquals(SensorCategory.MOTION, SignalCatalog.of(Sensor.TYPE_ACCELEROMETER).category)
        assertEquals("Angular Velocity", SignalCatalog.of(Sensor.TYPE_GYROSCOPE).humanName)
        assertEquals("Orientation", SignalCatalog.of(Sensor.TYPE_ROTATION_VECTOR).humanName)
        assertEquals(SensorCategory.ORIENTATION, SignalCatalog.of(Sensor.TYPE_ROTATION_VECTOR).category)
        assertEquals(SensorCategory.MAGNETIC, SignalCatalog.of(Sensor.TYPE_MAGNETIC_FIELD).category)
        assertEquals(SensorCategory.ENVIRONMENT, SignalCatalog.of(Sensor.TYPE_PRESSURE).category)
        assertEquals(SensorCategory.PROXIMITY, SignalCatalog.of(Sensor.TYPE_PROXIMITY).category)
    }

    @Test fun hasUnitsAndDescription() {
        val a = SignalCatalog.of(Sensor.TYPE_ACCELEROMETER)
        assertEquals("m/s²", a.unit)
        assertTrue("description non-blank", a.description.isNotBlank())
        assertTrue("component labels present", a.componentLabels.isNotEmpty())
    }

    @Test fun unknownFallsBackToOther() {
        val info = SignalCatalog.of(99999)
        assertEquals(SensorCategory.OTHER, info.category)
        assertTrue(info.humanName.isNotBlank())
    }
}
