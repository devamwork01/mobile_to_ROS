# On-Phone Lossless Recording (Phase 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a bounded, lossless on-phone `.ssbin` recorder that captures every acquired sensor sample independent of the network, so screen-off/backgrounded data is never lost.

**Architecture:** A synchronous storage engine (`LocalRecorder`) writes segmented `.ssbin` files (byte-compatible with the laptop's format) and indexes each record's raw bytes by `(handle, seq)` for later backfill. The sensor callback stays lightweight: it `trySend`s to a second bounded "recorder" channel that an IO coroutine drains into `LocalRecorder`. A size-OR-age ring prunes oldest segments. This is Phase 1; the cross-device **backfill/reconciliation** is a separate Phase 2 plan that consumes `LocalRecorder.readRawRange`.

**Tech Stack:** Kotlin, Android (foreground service already present), Gradle (JVM unit tests via `testDebugUnitTest`), reuse of `com.sensorstream.codec.BinaryPacketCodec`.

**Spec:** [`docs/superpowers/specs/2026-09-14-onphone-recording-backfill-design.md`](../specs/2026-09-14-onphone-recording-backfill-design.md)

## Global Constraints

- **Do not change the telemetry wire format or `BinaryPacketCodec`** (golden-vector pinned). Encode only.
- **On-phone `.ssbin` must match `laptop/sensorstream/logging_sink.py`:** file starts with magic bytes `SSLOG1\n` (7 bytes), then repeated frames `struct <qI` = `(frameTimestampNs: i64 LE, datagramLen: u32 LE)` followed by `datagramLen` datagram bytes. One record per datagram.
- **Sensor callback must stay lightweight** — non-blocking `trySend` only; never disk I/O or blocking in the callback.
- **Storage cap = size OR age, whichever hits first**, prune oldest whole segment; always keep the current segment. Defaults: `maxBytes = 150 MB`, `maxAgeMs = 20 min`, `segmentMs = 10 s`. Configurable via `SharedPreferences`.
- **`LocalRecorder` takes a `File` directory and an injectable clock `now: () -> Long`** (no Android `Context`) so it unit-tests on the JVM.
- **Build/run tests with JBR 21** (Android Studio's bundled JBR is Java 25 and breaks Gradle 8.9): prefix Gradle with `JAVA_HOME="/c/Users/devam/.jdks/jbr-21.0.11"`.
- **Frame timestamp** = `sample.tAcquireNs` (the phone's `elapsedRealtimeNanos` acquisition stamp).

---

## File Structure

- Create: `android/app/src/main/java/com/sensorstream/stream/LocalRecorder.kt` — segmented `.ssbin` writer + `(handle,seq)` raw-byte index + size/age ring prune + `readRawRange` + `stats`. Synchronous; JVM-testable.
- Create: `android/app/src/test/java/com/sensorstream/stream/LocalRecorderTest.kt` — JVM unit tests (temp dir + fake clock).
- Modify: `android/app/src/main/java/com/sensorstream/stream/StreamController.kt` — add a recorder channel + IO drain that feeds `LocalRecorder`; expose recorder stats.
- Modify: `android/app/src/main/java/com/sensorstream/stream/StreamEngine.kt` — construct/own `LocalRecorder` (dir + config), start/stop with the session, surface stats in `EngineState`.
- Modify: `android/app/src/main/java/com/sensorstream/stream/StreamController.kt` (`start` signature) and `android/app/src/main/java/com/sensorstream/service/StreamingService.kt` — pass the records directory (`filesDir`) + config down.
- Modify: `android/app/src/main/java/com/sensorstream/ui/StreamScreen.kt` — show recorder diagnostics (recorded count, ring size/age, dropped-oldest) in the status card.

---

### Task 1: LocalRecorder — write one record to a segment (exact `.ssbin` bytes)

**Files:**
- Create: `android/app/src/main/java/com/sensorstream/stream/LocalRecorder.kt`
- Test: `android/app/src/test/java/com/sensorstream/stream/LocalRecorderTest.kt`

**Interfaces:**
- Consumes: `com.sensorstream.codec.BinaryPacketCodec.encode(deviceId: Int, records: List<SensorSample>, flags: Int): ByteArray`; `com.sensorstream.core.SensorSample`.
- Produces: `class LocalRecorder(dir: File, deviceId: Int, maxBytes: Long, maxAgeMs: Long, segmentMs: Long, flags: Int = BinaryPacketCodec.FLAG_STAGE_TS, now: () -> Long = { System.currentTimeMillis() })` with `fun write(sample: SensorSample)`, `fun close()`. Companion: `LocalRecorder.MAGIC: ByteArray` (`"SSLOG1\n"`).

- [ ] **Step 1: Write the failing test**

```kotlin
// android/app/src/test/java/com/sensorstream/stream/LocalRecorderTest.kt
package com.sensorstream.stream

import com.sensorstream.codec.BinaryPacketCodec
import com.sensorstream.core.SensorSample
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LocalRecorderTest {
    private fun tempDir(): File = File.createTempFile("rec", "").let { it.delete(); it.mkdirs(); it }
    private fun sample(handle: Int, seq: Long) =
        SensorSample(sensorType = handle + 1, handle = handle, seq = seq, timestampNs = 1000L + seq,
            accuracy = 3, values = floatArrayOf(1f, 2f, 3f), tAcquireNs = 5000L + seq)

    @Test
    fun writesMagicAndOneFrameMatchingCodec() {
        val dir = tempDir()
        val rec = LocalRecorder(dir, deviceId = 7, maxBytes = 1_000_000, maxAgeMs = 60_000, segmentMs = 10_000,
            now = { 0L })
        val s = sample(handle = 0, seq = 0)
        rec.write(s)
        rec.close()

        val seg = dir.listFiles { f -> f.name.endsWith(".ssbin") }!!.single()
        val bytes = seg.readBytes()
        // magic
        assertArrayEquals(LocalRecorder.MAGIC, bytes.copyOfRange(0, LocalRecorder.MAGIC.size))
        // frame header + datagram == expected
        val datagram = BinaryPacketCodec.encode(7, listOf(s), BinaryPacketCodec.FLAG_STAGE_TS)
        val hdr = ByteBuffer.wrap(bytes, LocalRecorder.MAGIC.size, 12).order(ByteOrder.LITTLE_ENDIAN)
        val frameTs = hdr.long
        val len = hdr.int
        assertTrue(frameTs == s.tAcquireNs)
        assertTrue(len == datagram.size)
        val payload = bytes.copyOfRange(LocalRecorder.MAGIC.size + 12, LocalRecorder.MAGIC.size + 12 + len)
        assertArrayEquals(datagram, payload)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /c/mobile_to_ROS_app/android && JAVA_HOME="/c/Users/devam/.jdks/jbr-21.0.11" ./gradlew testDebugUnitTest --tests "com.sensorstream.stream.LocalRecorderTest.writesMagicAndOneFrameMatchingCodec"`
Expected: FAIL — `LocalRecorder` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// android/app/src/main/java/com/sensorstream/stream/LocalRecorder.kt
package com.sensorstream.stream

import com.sensorstream.codec.BinaryPacketCodec
import com.sensorstream.core.SensorSample
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Bounded, lossless on-phone recorder. Writes segmented .ssbin files byte-compatible with the
 * laptop (logging_sink.py) and indexes each record's raw datagram bytes by (handle, seq) so a
 * later backfill can resend them verbatim. Synchronous and Context-free for JVM testability;
 * callers must drive write() from an IO thread, never the sensor callback.
 */
class LocalRecorder(
    private val dir: File,
    private val deviceId: Int,
    private val maxBytes: Long,
    private val maxAgeMs: Long,
    private val segmentMs: Long,
    private val flags: Int = BinaryPacketCodec.FLAG_STAGE_TS,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        val MAGIC: ByteArray = "SSLOG1\n".toByteArray(Charsets.US_ASCII)
        private const val FRAME_HEADER = 12 // i64 + u32
    }

    private class Segment(val file: File, val startMs: Long) {
        val out = BufferedOutputStream(FileOutputStream(file))
        var bytes = 0L
    }

    private var current: Segment? = null

    init {
        dir.mkdirs()
    }

    fun write(sample: SensorSample) {
        val seg = current ?: openSegment()
        val datagram = BinaryPacketCodec.encode(deviceId, listOf(sample), flags)
        val header = ByteBuffer.allocate(FRAME_HEADER).order(ByteOrder.LITTLE_ENDIAN)
        header.putLong(sample.tAcquireNs)
        header.putInt(datagram.size)
        seg.out.write(header.array())
        seg.out.write(datagram)
        seg.bytes += FRAME_HEADER + datagram.size
    }

    private fun openSegment(): Segment {
        val ts = now()
        val seg = Segment(File(dir, "seg_$ts.ssbin"), ts)
        seg.out.write(MAGIC)
        seg.bytes = MAGIC.size.toLong()
        current = seg
        return seg
    }

    fun close() {
        current?.out?.flush()
        current?.out?.close()
        current = null
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /c/mobile_to_ROS_app/android && JAVA_HOME="/c/Users/devam/.jdks/jbr-21.0.11" ./gradlew testDebugUnitTest --tests "com.sensorstream.stream.LocalRecorderTest.writesMagicAndOneFrameMatchingCodec"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git -C /c/mobile_to_ROS_app add android/app/src/main/java/com/sensorstream/stream/LocalRecorder.kt android/app/src/test/java/com/sensorstream/stream/LocalRecorderTest.kt
git -C /c/mobile_to_ROS_app commit -m "feat(phone): LocalRecorder writes .ssbin frames (matches laptop format)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019dUjvRPerLwYiR5D56Poz3"
```

---

### Task 2: Segment rotation + per-segment `(handle,seq)` raw-byte index

**Files:**
- Modify: `android/app/src/main/java/com/sensorstream/stream/LocalRecorder.kt`
- Test: `android/app/src/test/java/com/sensorstream/stream/LocalRecorderTest.kt`

**Interfaces:**
- Produces: rotation when `now() - segment.startMs >= segmentMs`; an in-memory index per segment mapping `(handle, seq)` → the datagram's `(offsetInFile, length)`. No new public method yet (index is used by Task 4's `readRawRange`).

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun rotatesSegmentsByTime() {
        val dir = tempDir()
        var t = 0L
        val rec = LocalRecorder(dir, deviceId = 1, maxBytes = 10_000_000, maxAgeMs = 600_000,
            segmentMs = 100, now = { t })
        rec.write(sample(0, 0))       // opens segment @ t=0
        t = 50; rec.write(sample(0, 1)) // same segment
        t = 150; rec.write(sample(0, 2)) // >=100ms since start -> new segment
        rec.close()
        val segs = dir.listFiles { f -> f.name.endsWith(".ssbin") }!!
        assertTrue("expected 2 segments, got ${segs.size}", segs.size == 2)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /c/mobile_to_ROS_app/android && JAVA_HOME="/c/Users/devam/.jdks/jbr-21.0.11" ./gradlew testDebugUnitTest --tests "com.sensorstream.stream.LocalRecorderTest.rotatesSegmentsByTime"`
Expected: FAIL — only 1 segment.

- [ ] **Step 3: Write minimal implementation**

Extend `Segment` with the index and offset tracking, and rotate in `write`:

```kotlin
    private class Segment(val file: File, val startMs: Long) {
        val out = BufferedOutputStream(FileOutputStream(file))
        var bytes = 0L
        // (handle -> list of (seq, datagramOffset, datagramLen)) for readRawRange
        val index = HashMap<Int, ArrayList<Triple<Long, Long, Int>>>()
    }

    fun write(sample: SensorSample) {
        var seg = current ?: openSegment()
        if (now() - seg.startMs >= segmentMs) { rotate(); seg = current!! }
        val datagram = BinaryPacketCodec.encode(deviceId, listOf(sample), flags)
        val header = ByteBuffer.allocate(FRAME_HEADER).order(ByteOrder.LITTLE_ENDIAN)
        header.putLong(sample.tAcquireNs); header.putInt(datagram.size)
        val datagramOffset = seg.bytes + FRAME_HEADER
        seg.out.write(header.array()); seg.out.write(datagram)
        seg.bytes += FRAME_HEADER + datagram.size
        seg.index.getOrPut(sample.handle) { ArrayList() }
            .add(Triple(sample.seq, datagramOffset, datagram.size))
    }

    private fun rotate() {
        current?.out?.flush(); current?.out?.close()
        openSegment()
    }
```

Keep a list of live segments for later tasks:

```kotlin
    private val segments = ArrayList<Segment>()
    // in openSegment(): segments.add(seg) after creating it
```

Update `openSegment()` to append to `segments`, and `close()` to flush the current segment.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /c/mobile_to_ROS_app/android && JAVA_HOME="/c/Users/devam/.jdks/jbr-21.0.11" ./gradlew testDebugUnitTest --tests "com.sensorstream.stream.LocalRecorderTest.rotatesSegmentsByTime"`
Expected: PASS. Also re-run Task 1's test to confirm no regression (`--tests "com.sensorstream.stream.LocalRecorderTest"`).

- [ ] **Step 5: Commit**

```bash
git -C /c/mobile_to_ROS_app add android/app/src/main/java/com/sensorstream/stream/LocalRecorder.kt android/app/src/test/java/com/sensorstream/stream/LocalRecorderTest.kt
git -C /c/mobile_to_ROS_app commit -m "feat(phone): LocalRecorder segment rotation + per-segment (handle,seq) index

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019dUjvRPerLwYiR5D56Poz3"
```

---

### Task 3: Ring prune (size OR age) + stats with dropped-oldest

**Files:**
- Modify: `android/app/src/main/java/com/sensorstream/stream/LocalRecorder.kt`
- Test: `android/app/src/test/java/com/sensorstream/stream/LocalRecorderTest.kt`

**Interfaces:**
- Produces: `data class Stats(val recorded: Long, val bytesOnDisk: Long, val oldestAgeMs: Long, val droppedOldest: Long, val writeErrors: Long)` and `fun stats(): Stats`. After each `write`, prune oldest segments while `(total bytes > maxBytes) || (oldest.startMs age > maxAgeMs)`, never removing the current segment; each pruned record increments `droppedOldest`.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun prunesOldestBySize() {
        val dir = tempDir()
        var t = 0L
        // tiny size cap so a few records force a prune; small segments so multiple exist
        val rec = LocalRecorder(dir, deviceId = 1, maxBytes = 400, maxAgeMs = 600_000,
            segmentMs = 1, now = { t })
        for (i in 0 until 20) { t = i.toLong(); rec.write(sample(0, i.toLong())) }
        rec.close()
        val st = rec.stats()
        assertTrue("bytes ${st.bytesOnDisk} should be <= cap-ish", st.bytesOnDisk <= 400 + 200)
        assertTrue("expected some dropped, got ${st.droppedOldest}", st.droppedOldest > 0)
        assertTrue("at least current segment remains", dir.listFiles { f -> f.name.endsWith(".ssbin") }!!.isNotEmpty())
    }

    @Test
    fun prunesOldestByAge() {
        val dir = tempDir()
        var t = 0L
        val rec = LocalRecorder(dir, deviceId = 1, maxBytes = 10_000_000, maxAgeMs = 100,
            segmentMs = 10, now = { t })
        rec.write(sample(0, 0))                 // seg @0
        t = 500; rec.write(sample(0, 1))        // seg @500; seg@0 is now >100ms old -> pruned
        rec.close()
        assertTrue(rec.stats().droppedOldest >= 1)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /c/mobile_to_ROS_app/android && JAVA_HOME="/c/Users/devam/.jdks/jbr-21.0.11" ./gradlew testDebugUnitTest --tests "com.sensorstream.stream.LocalRecorderTest.prunesOldestBySize" --tests "com.sensorstream.stream.LocalRecorderTest.prunesOldestByAge"`
Expected: FAIL — `stats` unresolved / no pruning.

- [ ] **Step 3: Write minimal implementation**

Add counters and prune. Track total bytes and recorded count; call `prune()` at the end of `write`:

```kotlin
    data class Stats(
        val recorded: Long, val bytesOnDisk: Long, val oldestAgeMs: Long,
        val droppedOldest: Long, val writeErrors: Long,
    )

    private var recorded = 0L
    private var droppedOldest = 0L
    private var writeErrors = 0L

    // at end of write(): recorded++ ; prune()

    private fun totalBytes(): Long = segments.sumOf { it.bytes }

    private fun prune() {
        while (segments.size > 1) {
            val oldest = segments.first()
            val overSize = totalBytes() > maxBytes
            val overAge = now() - oldest.startMs > maxAgeMs
            if (!overSize && !overAge) break
            // count records in the segment as dropped, then delete it
            droppedOldest += oldest.index.values.sumOf { it.size.toLong() }
            runCatching { oldest.out.close() }
            oldest.file.delete()
            segments.removeAt(0)
        }
    }

    fun stats(): Stats {
        val oldestAge = segments.firstOrNull()?.let { now() - it.startMs } ?: 0L
        return Stats(recorded, totalBytes(), oldestAge, droppedOldest, writeErrors)
    }
```

Wrap the disk writes in `write` with `runCatching { … }.onFailure { writeErrors++ }` so a disk error is counted, not fatal.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /c/mobile_to_ROS_app/android && JAVA_HOME="/c/Users/devam/.jdks/jbr-21.0.11" ./gradlew testDebugUnitTest --tests "com.sensorstream.stream.LocalRecorderTest"`
Expected: PASS (all LocalRecorder tests).

- [ ] **Step 5: Commit**

```bash
git -C /c/mobile_to_ROS_app add android/app/src/main/java/com/sensorstream/stream/LocalRecorder.kt android/app/src/test/java/com/sensorstream/stream/LocalRecorderTest.kt
git -C /c/mobile_to_ROS_app commit -m "feat(phone): LocalRecorder size/age ring prune + stats (droppedOldest)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019dUjvRPerLwYiR5D56Poz3"
```

---

### Task 4: `readRawRange(handle, fromSeq, toSeq)` (for Phase 2 backfill)

**Files:**
- Modify: `android/app/src/main/java/com/sensorstream/stream/LocalRecorder.kt`
- Test: `android/app/src/test/java/com/sensorstream/stream/LocalRecorderTest.kt`

**Interfaces:**
- Produces: `fun readRawRange(handle: Int, fromSeq: Long, toSeq: Long): List<ByteArray>` — the stored datagram bytes for records of `handle` with `fromSeq <= seq <= toSeq`, in seq order, read from the (still-present) segment files. Records already pruned are simply absent (Phase 2 treats absent ranges as permanent gaps).

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun readRawRangeReturnsRequestedDatagrams() {
        val dir = tempDir()
        var t = 0L
        val rec = LocalRecorder(dir, deviceId = 3, maxBytes = 10_000_000, maxAgeMs = 600_000,
            segmentMs = 30, now = { t })
        val written = (0L until 6L).map { seq -> sample(handle = 2, seq = seq).also { t = seq * 10; rec.write(it) } }
        rec.close()
        val got = rec.readRawRange(handle = 2, fromSeq = 2, toSeq = 4)
        // expect datagrams for seq 2,3,4 == codec output
        val expected = written.filter { it.seq in 2..4 }
            .map { BinaryPacketCodec.encode(3, listOf(it), BinaryPacketCodec.FLAG_STAGE_TS) }
        assertTrue(got.size == 3)
        got.forEachIndexed { i, b -> assertArrayEquals(expected[i], b) }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /c/mobile_to_ROS_app/android && JAVA_HOME="/c/Users/devam/.jdks/jbr-21.0.11" ./gradlew testDebugUnitTest --tests "com.sensorstream.stream.LocalRecorderTest.readRawRangeReturnsRequestedDatagrams"`
Expected: FAIL — `readRawRange` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
    fun readRawRange(handle: Int, fromSeq: Long, toSeq: Long): List<ByteArray> {
        current?.out?.flush() // ensure current segment's bytes are on disk before reading
        val out = ArrayList<Pair<Long, ByteArray>>()
        for (seg in segments) {
            val entries = seg.index[handle] ?: continue
            for ((seq, offset, len) in entries) {
                if (seq < fromSeq || seq > toSeq) continue
                java.io.RandomAccessFile(seg.file, "r").use { raf ->
                    raf.seek(offset)
                    val buf = ByteArray(len)
                    raf.readFully(buf)
                    out.add(seq to buf)
                }
            }
        }
        return out.sortedBy { it.first }.map { it.second }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /c/mobile_to_ROS_app/android && JAVA_HOME="/c/Users/devam/.jdks/jbr-21.0.11" ./gradlew testDebugUnitTest --tests "com.sensorstream.stream.LocalRecorderTest"`
Expected: PASS (all).

- [ ] **Step 5: Commit**

```bash
git -C /c/mobile_to_ROS_app add android/app/src/main/java/com/sensorstream/stream/LocalRecorder.kt android/app/src/test/java/com/sensorstream/stream/LocalRecorderTest.kt
git -C /c/mobile_to_ROS_app commit -m "feat(phone): LocalRecorder.readRawRange serves datagrams by (handle,seq)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019dUjvRPerLwYiR5D56Poz3"
```

---

### Task 5: Fan-out — feed LocalRecorder from a dedicated IO drain (lossless, off the callback)

**Files:**
- Modify: `android/app/src/main/java/com/sensorstream/stream/StreamController.kt`
- Modify: `android/app/src/main/java/com/sensorstream/stream/StreamEngine.kt`
- Modify: `android/app/src/main/java/com/sensorstream/service/StreamingService.kt`

**Interfaces:**
- Consumes: `LocalRecorder(dir, deviceId, maxBytes, maxAgeMs, segmentMs)`, `LocalRecorder.write`, `LocalRecorder.stats`, `LocalRecorder.close`.
- Produces: `StreamController.start(host, port, regs, recorder: LocalRecorder?)` (added optional param); the sensor callback additionally does a non-blocking `recCh.trySend(sample)`; a new IO coroutine drains `recCh` into `recorder.write`. `StreamController` exposes `recorderStats(): LocalRecorder.Stats?`. `StreamEngine` constructs the recorder from a records dir + config and passes it in; `EngineState` gains recorder fields (Task 6 renders them).

- [ ] **Step 1: Add the recorder channel + drain to StreamController**

In `StreamController`:
- Add field `private var recorder: LocalRecorder? = null` and `private var recCh: Channel<SensorSample>? = null`.
- Change `start(host, port, regs)` → `start(host: String, port: Int, regs: List<SensorEventSource.Reg>, recorder: LocalRecorder? = null)`; store `this.recorder = recorder`.
- Create a lossless recorder channel: `val rc = Channel<SensorSample>(capacity = 16384, onBufferOverflow = BufferOverflow.SUSPEND); recCh = rc` (large; disk keeps up at ~36 KB/s).
- In `source.onSample`, after the existing network `trySend`, add: `if (recorder != null) rc.trySend(sample)`.
- Launch an IO drain in the existing scope: `s.launch { for (sample in rc) recorder?.write(sample) }`.
- In `stop()`: `recCh?.close(); recCh = null; recorder?.close(); recorder = null`.
- Add `fun recorderStats(): LocalRecorder.Stats? = recorder?.stats()`.

- [ ] **Step 2: Construct + pass the recorder from StreamEngine**

In `StreamEngine`:
- Add a records dir + config passed to `start` (see Task 5 Step 3 for the service wiring). Add fields: `private var recordDir: File? = null; private var recMaxBytes = 150L*1024*1024; private var recMaxAgeMs = 20L*60*1000; private val recSegmentMs = 10_000L`.
- In `startTelemetry(udpPort)`, build the recorder and pass it:

```kotlin
val recorder = recordDir?.let {
    LocalRecorder(File(it, "onphone"), controller.deviceId, recMaxBytes, recMaxAgeMs, recSegmentMs)
}
controller.start(host, udpPort, regs, recorder)
```

- Add `fun configureRecording(dir: File, maxBytes: Long, maxAgeMs: Long) { recordDir = dir; recMaxBytes = maxBytes; recMaxAgeMs = maxAgeMs }` and call it from the service before `start`.

- [ ] **Step 3: Wire the records dir + config from the service**

In `StreamingService`, before starting the engine, read config from `SharedPreferences` (defaults 150 MB / 20 min) and call:

```kotlin
val prefs = getSharedPreferences("sensorstream", Context.MODE_PRIVATE)
val maxBytes = prefs.getLong("rec_max_bytes", 150L * 1024 * 1024)
val maxAgeMs = prefs.getLong("rec_max_age_ms", 20L * 60 * 1000)
engine.configureRecording(filesDir, maxBytes, maxAgeMs)
```

(Place this next to where the service starts the engine/streaming. `filesDir` is app-private storage.)

- [ ] **Step 4: Build + verify on device (integration — not unit-testable without instrumentation)**

Run: `cd /c/mobile_to_ROS_app/android && JAVA_HOME="/c/Users/devam/.jdks/jbr-21.0.11" ./gradlew assembleDebug` (expect BUILD SUCCESSFUL), then install on a connected phone and stream ~15 s:

```bash
ADB="/c/Users/devam/AppData/Local/Android/Sdk/platform-tools/adb.exe"; D=<serial>
"$ADB" -s $D install -r app/build/outputs/apk/debug/app-debug.apk
# after connecting + streaming from the app for ~15s:
"$ADB" -s $D shell run-as com.sensorstream ls -la files/onphone
```
Expected: one or more `seg_*.ssbin` files present and growing while streaming, pruned to stay under the caps.

- [ ] **Step 5: Commit**

```bash
git -C /c/mobile_to_ROS_app add android/app/src/main/java/com/sensorstream/stream/StreamController.kt android/app/src/main/java/com/sensorstream/stream/StreamEngine.kt android/app/src/main/java/com/sensorstream/service/StreamingService.kt
git -C /c/mobile_to_ROS_app commit -m "feat(phone): fan-out acquisition into LocalRecorder via IO drain (lossless, off callback)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019dUjvRPerLwYiR5D56Poz3"
```

---

### Task 6: Surface recorder diagnostics in the phone status card

**Files:**
- Modify: `android/app/src/main/java/com/sensorstream/stream/StreamEngine.kt` (add fields to `EngineState`, poll `controller.recorderStats()` in the 1 s loop)
- Modify: `android/app/src/main/java/com/sensorstream/ui/StreamScreen.kt` (render them)

**Interfaces:**
- Consumes: `StreamController.recorderStats(): LocalRecorder.Stats?`.
- Produces: `EngineState` gains `recBytes: Long = 0`, `recDropped: Long = 0`, `recOldestAgeMs: Long = 0`; the status card shows a "recording" line.

- [ ] **Step 1: Add fields to EngineState + populate in the 1 s loop**

In `StreamEngine`, add to `data class EngineState(...)`: `val recBytes: Long = 0L, val recDropped: Long = 0L, val recOldestAgeMs: Long = 0L`. In the existing 1 s heartbeat loop, read stats and copy them into `_state`:

```kotlin
val rs = controller.recorderStats()
_state.value = _state.value.copy(
    sentPackets = controller.sentPackets.get(),
    droppedSamples = controller.droppedSamples.get(),
    sendBps = bps,
    recBytes = rs?.bytesOnDisk ?: 0L,
    recDropped = rs?.droppedOldest ?: 0L,
    recOldestAgeMs = rs?.oldestAgeMs ?: 0L,
)
```

- [ ] **Step 2: Render a recording line in the connection card**

In `StreamScreen`'s `ConnectionCard`, after the "network out" line, add:

```kotlin
Text(
    "on-phone rec  %s  ·  buffered %ds  ·  dropped %d".format(
        fmtBytes(engine.recBytes), engine.recOldestAgeMs / 1000, engine.recDropped
    ),
    fontFamily = FontFamily.Monospace,
    style = MaterialTheme.typography.bodySmall,
)
```

Add a small helper near `fmtBps`:

```kotlin
private fun fmtBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.0f KB".format(b / 1024.0)
    else -> "%.1f MB".format(b / (1024.0 * 1024))
}
```

- [ ] **Step 3: Build + verify on device**

Run: `cd /c/mobile_to_ROS_app/android && JAVA_HOME="/c/Users/devam/.jdks/jbr-21.0.11" ./gradlew assembleDebug`, install, stream, and screenshot: the status card shows `on-phone rec  X MB · buffered Ns · dropped 0`, with the MB rising then holding at the cap.

```bash
ADB="/c/Users/devam/AppData/Local/Android/Sdk/platform-tools/adb.exe"; D=<serial>
"$ADB" -s $D install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" -s $D exec-out screencap -p > /tmp/rec.png   # inspect the status card
```

- [ ] **Step 4: Commit**

```bash
git -C /c/mobile_to_ROS_app add android/app/src/main/java/com/sensorstream/stream/StreamEngine.kt android/app/src/main/java/com/sensorstream/ui/StreamScreen.kt
git -C /c/mobile_to_ROS_app commit -m "feat(phone): show on-phone recording size/buffered/dropped in status card

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019dUjvRPerLwYiR5D56Poz3"
```

---

## Phase 2 (separate plan, later)

Backfill/reconciliation, built on `LocalRecorder.readRawRange`: `protocol.py` + Kotlin message constants (`resend`/`backfill`/`backfill_unavailable`/`backfill_ack`); laptop gap tracker + resend requester + merge (append + dedup-by-`(handle,seq)` on read in `recordings.py`) + ACK-driven prune (`LocalRecorder.ackUpTo`). Note: because the phone codec is encode-only, **backfill will resend raw datagram bytes** (base64 over the WS), which the laptop decodes with `decode_datagram` — a refinement of the spec's JSON-record shape.

## Self-Review

- **Spec coverage (Phase 1 scope):** on-phone lossless `.ssbin` recording (Tasks 1–5) ✓; byte-compatible with laptop format (Task 1 constraint + test) ✓; bounded ring, size OR age, prune oldest, defaults, configurable (Task 3 + Task 5 Step 3) ✓; lightweight callback / non-blocking fan-out (Task 5) ✓; `readRawRange` for Phase-2 backfill (Task 4) ✓; diagnostics: recorded/size/oldest-age/dropped (Tasks 3, 6) ✓. Deferred to Phase 2 (explicitly): backfill protocol, gap tracker, merge/dedup, ACK-prune, permanent-gap accounting — tracked above.
- **Placeholder scan:** none — every code step has concrete Kotlin.
- **Type consistency:** `LocalRecorder(dir, deviceId, maxBytes, maxAgeMs, segmentMs, flags, now)`, `write`, `readRawRange`, `stats()→Stats`, `close` used consistently across tasks; `StreamController.start(..., recorder)` and `recorderStats()` match their consumers in Tasks 5–6; `EngineState` fields `recBytes/recDropped/recOldestAgeMs` defined (Task 6 Step 1) before use (Task 6 Step 2).
