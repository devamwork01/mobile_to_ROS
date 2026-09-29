package com.sensorstream.codec

import com.sensorstream.core.SensorSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatagramPackerTest {

    private fun sample(seq: Long, n: Int = 3) =
        SensorSample(1, 0, seq, 1_000L + seq, 3, FloatArray(n) { it.toFloat() })

    /** Greedy pack like the sender does: add while it fits, else start a new datagram. */
    private fun pack(samples: List<SensorSample>, stage: Boolean): List<List<SensorSample>> {
        val out = mutableListOf<MutableList<SensorSample>>()
        var cur = mutableListOf<SensorSample>(); var bytes = 0
        for (s in samples) {
            if (!DatagramPacker.fits(cur.size, bytes, s, stage)) { out += cur; cur = mutableListOf(); bytes = 0 }
            cur += s; bytes += DatagramPacker.recordSize(s, stage)
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    @Test
    fun recordSizeMatchesCodec() {
        val s = sample(0, n = 4)
        for (stage in listOf(true, false)) {
            val flags = if (stage) BinaryPacketCodec.FLAG_STAGE_TS else 0
            val encoded = BinaryPacketCodec.encode(7, listOf(s, s), flags).size
            assertEquals(encoded, DatagramPacker.datagramSize(2 * DatagramPacker.recordSize(s, stage)))
        }
    }

    @Test
    fun batchesNeverExceedMaxBytesOrRecordsAndKeepOrder() {
        val samples = (0L until 500L).map { sample(it, n = if (it % 3 == 0L) 5 else 3) }
        val batches = pack(samples, stage = true)
        for (b in batches) {
            val size = BinaryPacketCodec.encode(1, b, BinaryPacketCodec.FLAG_STAGE_TS).size
            assertTrue("datagram $size bytes", size <= DatagramPacker.MAX_DATAGRAM_BYTES)
            assertTrue(b.size <= DatagramPacker.MAX_RECORDS)
        }
        assertEquals(samples.map { it.seq }, batches.flatten().map { it.seq })
        assertTrue("expected real batching, got ${batches.size} datagrams", batches.size < samples.size / 10)
    }

    @Test
    fun firstRecordAlwaysFits() {
        assertTrue(DatagramPacker.fits(0, 0, sample(0, n = 16), stage = true))
    }

    @Test
    fun fullDatagramRejectsNext() {
        val s = sample(0)
        val per = DatagramPacker.recordSize(s, true)
        val full = (DatagramPacker.MAX_DATAGRAM_BYTES - BinaryPacketCodec.HEADER_SIZE) / per
        assertFalse(DatagramPacker.fits(full, full * per, s, stage = true))
    }
}
