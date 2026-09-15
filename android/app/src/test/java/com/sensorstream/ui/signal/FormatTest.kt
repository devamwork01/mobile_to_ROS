package com.sensorstream.ui.signal

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test fun valueUsesFixedDecimals() {
        assertEquals("9.812", Fmt.value(9.81237f, 3))
    }

    @Test fun magnitudeOfVector() {
        assertEquals("5.000", Fmt.magnitude(floatArrayOf(3f, 4f, 0f)))
    }

    @Test fun hzIsOneDecimal() {
        assertEquals("122.0 Hz", Fmt.hz(122.0f))
    }
}
