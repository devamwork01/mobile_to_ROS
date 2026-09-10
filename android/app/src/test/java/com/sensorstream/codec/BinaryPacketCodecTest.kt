package com.sensorstream.codec

import com.sensorstream.core.SensorSample
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cross-language parity: this must produce the exact bytes the Python encoder
 * commits to `laptop/tests/golden_packet.bin`. If either side changes the wire
 * layout, this test (or its Python twin) fails.
 */
class BinaryPacketCodecTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun goldenVectorMatchesPython() {
        val records = listOf(
            SensorSample(1, 0, 12345L, 123456789012345L, 3, floatArrayOf(0.12f, -9.81f, 0.42f)),
            SensorSample(4, 1, 7L, 123456789099999L, 2, floatArrayOf(0.01f, 0.02f, -0.03f)),
        )
        val bytes = BinaryPacketCodec.encode(0x0A0B0C0D, records, 0)

        val expected =
            "535301000d0c0b0a0200" +               // header: magic,ver,flags,device_id,count=2
                "010000000000" +                   // rec1: type=1, handle=0
                "3930000079df0d86487000000303" +   // seq=12345, t=..., acc=3, n=3
                "8fc2f53dc3f51cc13d0ad73e" +       // [0.12, -9.81, 0.42]
                "040000000100" +                   // rec2: type=4, handle=1
                "07000000df350f86487000000203" +   // seq=7, t=..., acc=2, n=3
                "0ad7233c0ad7a33c8fc2f5bc"         // [0.01, 0.02, -0.03]

        assertEquals(74, bytes.size)
        assertEquals(expected, hex(bytes))
    }

    @Test
    fun encodedSizeMatchesActual() {
        val records = listOf(SensorSample(6, 0, 0L, 0L, 3, floatArrayOf(1013.25f)))
        assertEquals(
            BinaryPacketCodec.encodedSize(records, stage = false),
            BinaryPacketCodec.encode(1, records, 0).size,
        )
    }
}
