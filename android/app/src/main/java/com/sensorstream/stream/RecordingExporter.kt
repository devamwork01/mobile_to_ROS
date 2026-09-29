package com.sensorstream.stream

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Merges the on-phone recording's segment files into ONE `.ssbin` in the laptop's native format
 * (`SSLOG1\n` + frames of `[i64 t][u32 len][datagram]`), so it opens in the laptop's recordings
 * browser / replay tools. Each segment's own header is skipped, frames are copied in time order,
 * and a truncated trailing frame (e.g. from a process kill mid-write) is dropped rather than
 * exported as garbage. Pure java.io, so it is JVM-unit-testable.
 */
object RecordingExporter {

    data class Result(val frames: Long, val bytes: Long, val segments: Int)

    private val SEG_NAME = Regex("""^seg_(\d+)_(\d+)\.ssbin$""")

    /** Segment files in [dir], oldest first (by timestamp, then per-session salt). */
    fun segmentsIn(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray()).mapNotNull { f ->
            SEG_NAME.matchEntire(f.name)?.let { m -> Triple(m.groupValues[1].toLong(), m.groupValues[2].toLong(), f) }
        }.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }

    fun merge(segments: List<File>, out: File): Result {
        val magic = LocalRecorder.MAGIC
        var frames = 0L
        var used = 0
        out.parentFile?.mkdirs()
        BufferedOutputStream(FileOutputStream(out)).use { o ->
            o.write(magic)
            var bytes = magic.size.toLong()
            for (seg in segments) {
                DataInputStream(FileInputStream(seg).buffered()).use { inp ->
                    val head = ByteArray(magic.size)
                    if (!readFully(inp, head) || !head.contentEquals(magic)) return@use // not a segment
                    used++
                    val hdr = ByteArray(12)
                    while (true) {
                        if (!readFully(inp, hdr)) break
                        val len = ByteBuffer.wrap(hdr, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
                        if (len <= 0 || len > 65_535) break // corrupt length: stop this segment
                        val body = ByteArray(len)
                        if (!readFully(inp, body)) break     // truncated tail: drop it
                        o.write(hdr); o.write(body)
                        bytes += 12 + len
                        frames++
                    }
                }
            }
            return Result(frames, bytes, used)
        }
    }

    private fun readFully(inp: DataInputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = inp.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }
}
