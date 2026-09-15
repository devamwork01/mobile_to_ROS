package com.sensorstream.stream

/** Pure helper: chunk recorded datagrams into bounded batches so a single backfill WS message
 *  never carries an unbounded number of frames. Base64 encoding happens at the send site
 *  (WsControlClient) using the Android encoder; this stays pure so it is JVM-unit-testable. */
object BackfillResponder {
    const val MAX_FRAMES_PER_MSG = 256

    fun chunk(frames: List<ByteArray>): List<List<ByteArray>> {
        if (frames.isEmpty()) return emptyList()
        val out = ArrayList<List<ByteArray>>()
        var i = 0
        while (i < frames.size) {
            val end = minOf(i + MAX_FRAMES_PER_MSG, frames.size)
            out.add(frames.subList(i, end))
            i = end
        }
        return out
    }
}
