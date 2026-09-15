# Phase 2A — Phone Foundation for Backfill (acquisition survives drops, stable identity, continuous seq) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the phone keep acquiring and recording losslessly across WebSocket/UDP drops, identify itself with a stable `client_id`, and keep per-sensor `seq` continuous across reconnects — so the on-phone `.ssbin` is a complete, stably-addressed source that Phase 2B backfill can serve from.

**Architecture:** Today a control-channel drop tears down acquisition (`StreamController.stop()` → `SensorEventSource.stop()` clears `seqByHandle` and unregisters sensors), so nothing is recorded during an outage and every sensor's `seq` restarts at 0 with a fresh laptop-assigned `device_id` on reconnect. This plan **splits `StreamController` into a session-long acquisition+recording half and a per-connection UDP-sender half.** Acquisition (`SensorEventSource`) and the recorder drain run for the whole streaming session; only the UDP sender cycles on WS drop/reconnect. `SensorEventSource` is never stopped mid-session, so `seq` stays continuous. A `client_id` (random UUID persisted in `SharedPreferences`) is generated once and sent in `hello`, giving the laptop a stable key across reconnects even though `device_id` still rotates.

**Tech Stack:** Kotlin, Jetpack Compose, kotlinx.coroutines, Android `SensorManager`; JVM JUnit for the Context-free helper. Build with **JBR 21**.

**Spec:** `docs/superpowers/specs/2026-09-14-onphone-recording-backfill-design.md` (this plan implements the phone-side prerequisites for §5–§8; the backfill protocol/reconciliation itself is Plan 2B).

## Global Constraints

- **Do not change the telemetry wire format or `BinaryPacketCodec`** (golden-vector pinned; encode-only). Backfill in 2B reuses the exact recorded datagram bytes.
- **On-phone `.ssbin` must stay byte-compatible** with `laptop/sensorstream/logging_sink.py` (magic `SSLOG1\n`, frames `struct <qI` = `(frameTimestampNs i64 LE, datagramLen u32 LE)`, one record per datagram).
- **Sensor callback stays lightweight** — non-blocking `trySend` only; never disk I/O or blocking in `SensorEventSource.onSensorChanged`.
- **`LocalRecorder` stays Context-free** (takes a `File` dir + injectable `now: () -> Long`) so it unit-tests on the JVM. Do not add Android imports to it.
- **Build/run tests with JBR 21** (Android Studio's bundled JDK is Java 25 and breaks Gradle 8.9): prefix every Gradle call with `JAVA_HOME="/c/Users/devam/.jdks/jbr-21.0.11"`. `adb` is at `/c/Users/devam/AppData/Local/Android/Sdk/platform-tools/adb.exe`. Device serials: S25 Ultra `RZGL31H3N4V`, M36 5G `RZGL50Z0GYJ`.
- **Universal axis colors** (unchanged, for any UI touch): X=`#ff5c5c`, Y=`#3fd07a`, Z=`#4c8dff`.
- **Recorder lifecycle from Phase 1 stays:** the recorder is built once per streaming session and owned/closed by `StreamEngine`; it already survives reconnects. This plan additionally keeps *acquisition* alive across reconnects so the recorder actually receives samples during an outage.

---

### Task 1: Stable `client_id` (persisted UUID) sent in `hello`

**Files:**
- Create: `android/app/src/main/java/com/sensorstream/core/ClientId.kt`
- Test: `android/app/src/test/java/com/sensorstream/core/ClientIdTest.kt`
- Modify: `android/app/src/main/java/com/sensorstream/stream/StreamEngine.kt` (add `clientId` field + include in `buildHello()`)
- Modify: `android/app/src/main/java/com/sensorstream/service/StreamingService.kt` (read/create the id from `SharedPreferences("sensorstream")`, pass to the engine)

**Interfaces:**
- Produces: `object ClientId { fun getOrCreate(read: () -> String?, write: (String) -> Unit): String }` — pure, Context-free, JVM-testable. Returns an existing non-blank id or creates+persists a new `UUID.randomUUID().toString()`.
- Produces: `StreamEngine.clientId: String` (settable before `start`); `buildHello()` adds `"client_id"` to the JSON.
- Consumes (2B, laptop): the `client_id` string in the `hello` message.

- [ ] **Step 1: Write the failing test**

```kotlin
// android/app/src/test/java/com/sensorstream/core/ClientIdTest.kt
package com.sensorstream.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientIdTest {
    @Test fun createsAndPersistsWhenAbsent() {
        var stored: String? = null
        val id = ClientId.getOrCreate(read = { stored }, write = { stored = it })
        assertTrue("id is non-blank", id.isNotBlank())
        assertEquals("persisted the created id", id, stored)
    }

    @Test fun reusesExistingId() {
        var stored: String? = "existing-abc"
        val id = ClientId.getOrCreate(read = { stored }, write = { stored = it })
        assertEquals("existing-abc", id)
        assertEquals("existing-abc", stored) // unchanged
    }

    @Test fun replacesBlankId() {
        var stored: String? = "   "
        val id = ClientId.getOrCreate(read = { stored }, write = { stored = it })
        assertTrue(id.isNotBlank())
        assertEquals(id, stored)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /c/mobile_to_ROS_app/android && JAVA_HOME="/c/Users/devam/.jdks/jbr-21.0.11" ./gradlew testDebugUnitTest --tests "com.sensorstream.core.ClientIdTest"`
Expected: FAIL — `Unresolved reference: ClientId` (compile error).

- [ ] **Step 3: Write minimal implementation**

```kotlin
// android/app/src/main/java/com/sensorstream/core/ClientId.kt
package com.sensorstream.core

import java.util.UUID

/** Stable per-install identity, persisted by the caller. Context-free for JVM testability:
 *  the caller supplies the read/write to SharedPreferences. Used so the laptop can key a phone
 *  across reconnects even though the telemetry device_id is reassigned each control handshake. */
object ClientId {
    fun getOrCreate(read: () -> String?, write: (String) -> Unit): String {
        val existing = read()
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        write(created)
        return created
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /c/mobile_to_ROS_app/android && JAVA_HOME="/c/Users/devam/.jdks/jbr-21.0.11" ./gradlew testDebugUnitTest --tests "com.sensorstream.core.ClientIdTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Wire into StreamEngine + StreamingService**

In `StreamEngine.kt`, add a settable field near the other config vars (e.g. next to `recordDir`):

```kotlin
    /** Stable per-install id sent in hello; set by the Service before start(). Empty = not set. */
    @Volatile var clientId: String = ""
```

In `StreamEngine.buildHello()`, add the field to the JSON (after `.put("app_version", "0.1.0")`):

```kotlin
            .put("client_id", clientId)
```

In `StreamingService.kt` `onStartCommand` `ACTION_START`, where the engine is configured (next to `engine.configureRecording(...)`), read/persist the id and set it:

```kotlin
val cid = com.sensorstream.core.ClientId.getOrCreate(
    read = { prefs.getString("client_id", null) },
    write = { prefs.edit().putString("client_id", it).apply() },
)
engine.clientId = cid
```

(`prefs` is the existing `getSharedPreferences("sensorstream", Context.MODE_PRIVATE)` from the Phase 1 recording config; reuse it.)

- [ ] **Step 6: Build gate**

Run: `cd /c/mobile_to_ROS_app/android && JAVA_HOME="/c/Users/devam/.jdks/jbr-21.0.11" ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/sensorstream/core/ClientId.kt \
        android/app/src/test/java/com/sensorstream/core/ClientIdTest.kt \
        android/app/src/main/java/com/sensorstream/stream/StreamEngine.kt \
        android/app/src/main/java/com/sensorstream/service/StreamingService.kt
git commit -m "feat(phone): stable persisted client_id sent in hello

Random UUID persisted in SharedPreferences('sensorstream','client_id'),
generated once per install via a Context-free ClientId.getOrCreate helper
(JVM-tested) and included in the hello message, so the laptop can key a
phone across reconnects even though device_id is reassigned each handshake.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Split `StreamController` — session-long acquisition/recording vs per-connection UDP sender

**Files:**
- Modify: `android/app/src/main/java/com/sensorstream/stream/StreamController.kt` (whole `start`/`stop` restructure)

**Interfaces:**
- Consumes: `SensorEventSource` (`onSample`, `start(regs)`, `updateRegs`, `stop`), `UdpTelemetrySender` (`connect`, `send`, `close`), `LocalRecorder` (`write`, owned by `StreamEngine`).
- Produces (used by `StreamEngine` in Task 3):
  - `fun startSession(regs: List<SensorEventSource.Reg>, recorder: LocalRecorder?)` — starts acquisition + recorder drain for the whole streaming session; resets counters. Idempotent (calls `stopSession()` first).
  - `fun connectSender(host: String, port: Int)` — (re)starts only the UDP sender coroutine draining the network channel; safe to call on every (re)connect.
  - `fun disconnectSender()` — cancels only the sender coroutine and closes the socket; acquisition + recorder keep running.
  - `fun updateRegs(regs: List<SensorEventSource.Reg>)` — unchanged behavior (live reconfig on the running source).
  - `fun stopSession()` — stop everything (source, drains, sender). Replaces the old `stop()`.
  - Unchanged public fields: `deviceId`, `onUiSample`, `onError`, `sentPackets`, `sentBytes`, `droppedSamples`, `recorderDropped`, `recorderStats()`.

- [ ] **Step 1: Restructure `StreamController`**

Replace the `start(...)`/`stop()` pair with the session/sender split below. Key design points, all preserved from Phase 1:
- One `sessionScope` (SupervisorJob + Dispatchers.IO) owns the recorder drain and the sender jobs.
- The network `Channel(4096, DROP_OLDEST)` and recorder `Channel(16384, SUSPEND)` are created in `startSession` and live for the whole session. During a sender outage nothing drains the network channel, so it sheds via DROP_OLDEST (those samples are exactly what 2B backfills) — the recorder channel keeps draining, so **recording is lossless during the outage**.
- `connectSender` launches a sender job that drains the network channel; `disconnectSender` cancels just that job and closes the socket, leaving the channel and acquisition intact.
- The sensor callback keeps the Phase-1 fan-out shape (network `trySend`, recorder `trySend` with `recorderDropped`, `onUiSample`).

```kotlin
package com.sensorstream.stream

import android.hardware.SensorManager
import android.os.SystemClock
import com.sensorstream.codec.BinaryPacketCodec
import com.sensorstream.core.SensorSample
import com.sensorstream.net.UdpTelemetrySender
import com.sensorstream.sensor.SensorEventSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * Ties acquisition to networking through bounded channels. Acquisition + recording run for the
 * whole streaming session ([startSession]/[stopSession]); the UDP sender is a per-connection job
 * ([connectSender]/[disconnectSender]) so a control-channel drop pauses only the network send while
 * the sensors keep firing and the recorder keeps writing (seq stays continuous, recording lossless).
 */
class StreamController(sm: SensorManager) {

    private val source = SensorEventSource(sm)
    private val sender = UdpTelemetrySender()

    private var sessionScope: CoroutineScope? = null
    private var channel: Channel<SensorSample>? = null   // network path (DROP_OLDEST, best-effort)
    @Volatile private var recorder: LocalRecorder? = null
    private var recCh: Channel<SensorSample>? = null      // recorder path (SUSPEND, lossless)
    private var recDrainJob: Job? = null
    private var senderJob: Job? = null

    val sentPackets = AtomicLong(0)
    val sentBytes = AtomicLong(0)
    val droppedSamples = AtomicLong(0)   // network-channel drops (recovered later via backfill)
    val recorderDropped = AtomicLong(0)  // fan-out drops before the recorder (should stay 0)

    @Volatile var deviceId: Int = (System.currentTimeMillis() and 0xFFFFFFFFL).toInt()
    @Volatile var onUiSample: ((SensorSample) -> Unit)? = null
    @Volatile var onError: ((Throwable) -> Unit)? = null

    /** Start acquisition + recording for the whole session. Sender is attached separately. */
    fun startSession(regs: List<SensorEventSource.Reg>, recorder: LocalRecorder?) {
        stopSession()
        sentPackets.set(0); sentBytes.set(0); droppedSamples.set(0); recorderDropped.set(0)
        this.recorder = recorder

        val ch = Channel<SensorSample>(capacity = 4096, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        channel = ch
        val rc = Channel<SensorSample>(capacity = 16384, onBufferOverflow = BufferOverflow.SUSPEND)
        recCh = rc
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        sessionScope = s

        source.onSample = { sample ->
            if (ch.trySend(sample).isFailure) droppedSamples.incrementAndGet()
            if (recorder != null && rc.trySend(sample).isFailure) recorderDropped.incrementAndGet()
            onUiSample?.invoke(sample)
        }
        recDrainJob = s.launch { for (sample in rc) recorder?.write(sample) }
        source.start(regs)
    }

    /** (Re)attach the UDP sender. Call on every (re)connect; drains the shared network channel. */
    fun connectSender(host: String, port: Int) {
        val s = sessionScope ?: return
        disconnectSender()
        val ch = channel ?: return
        senderJob = s.launch {
            try {
                sender.connect(host, port)
            } catch (t: Throwable) {
                onError?.invoke(t)
                return@launch
            }
            val one = ArrayList<SensorSample>(1)
            for (sample in ch) {
                one.clear(); one.add(sample)
                try {
                    sample.tSerializeNs = SystemClock.elapsedRealtimeNanos()
                    val bytes = BinaryPacketCodec.encode(deviceId, one, BinaryPacketCodec.FLAG_STAGE_TS)
                    sender.send(bytes)
                    sentPackets.incrementAndGet()
                    sentBytes.addAndGet(bytes.size.toLong())
                } catch (t: Throwable) {
                    onError?.invoke(t)
                }
            }
        }
    }

    /** Pause the UDP sender only; acquisition + recorder keep running (lossless during an outage). */
    fun disconnectSender() {
        senderJob?.cancel()
        senderJob = null
        sender.close()
    }

    /** Live-reconfigure the streamed sensor set without dropping acquisition. */
    fun updateRegs(regs: List<SensorEventSource.Reg>) {
        if (sessionScope == null) return
        source.updateRegs(regs)
    }

    /** Tear down the whole session. Recorder is owned + closed by StreamEngine. */
    fun stopSession() {
        source.onSample = null
        source.stop()
        disconnectSender()
        channel?.close(); channel = null
        recCh?.close(); recCh = null
        val drain = recDrainJob
        if (drain != null) runBlocking { withTimeoutOrNull(500) { drain.join() } }
        recDrainJob = null
        sessionScope?.cancel(); sessionScope = null
        recorder = null
    }

    fun recorderStats(): LocalRecorder.Stats? = recorder?.stats()
}
```

- [ ] **Step 2: Build gate**

Run: `cd /c/mobile_to_ROS_app/android && JAVA_HOME="/c/Users/devam/.jdks/jbr-21.0.11" ./gradlew assembleDebug`
Expected: FAIL to compile — `StreamEngine` still calls the old `controller.start(...)` / `controller.stop()`. That is expected; Task 3 updates the caller. (If you are doing Task 2 and Task 3 as one unit, skip ahead; otherwise this red build is the task boundary and Task 3 makes it green.)

- [ ] **Step 3: Confirm the LocalRecorder unit tests still pass** (StreamController isn't unit-tested, but the recorder it drives is; guard against accidental edits):

Run: `cd /c/mobile_to_ROS_app/android && JAVA_HOME="/c/Users/devam/.jdks/jbr-21.0.11" ./gradlew testDebugUnitTest --tests "com.sensorstream.stream.LocalRecorderTest"`
Expected: PASS (9 tests) — this task must not have touched `LocalRecorder.kt`.

- [ ] **Step 4: Commit** (with Task 3, since the tree only compiles once the caller is updated — commit them together at the end of Task 3). If committing separately is required by your workflow, note in the message that the build is green only after Task 3.

---

### Task 3: Rewire `StreamEngine` — keep acquiring across drops; connect/disconnect sender on WS up/down

**Files:**
- Modify: `android/app/src/main/java/com/sensorstream/stream/StreamEngine.kt`

**Interfaces:**
- Consumes: `StreamController.startSession`, `connectSender`, `disconnectSender`, `updateRegs`, `stopSession`, `recorderStats` (Task 2).
- Produces: unchanged public surface (`start`, `stop`, `updateSelections`, `state`, `latestFor`, `hzFor`, `recorderStats`, `configureRecording`, `clientId`). New behavior: acquisition/recording persist across control-channel drops.

- [ ] **Step 1: Add a session-started guard field**

Near the other private vars (e.g. below `recorder`):

```kotlin
    // True once acquisition+recording started this streaming session; reconnects only re-attach
    // the UDP sender, they do not restart acquisition (keeps seq continuous + recording lossless).
    private var sessionStarted = false
```

- [ ] **Step 2: Replace `startTelemetry(udpPort)` body**

The method currently builds the recorder (once) and calls `controller.start(host, udpPort, regs, recorder)`. Replace with: build the recorder once, start the session once, and (re)attach the sender every time. Note `startTelemetry` runs on every `onHelloAck` (first connect and every reconnect).

```kotlin
    private fun startTelemetry(udpPort: Int) {
        val regs = selections.mapNotNull { sel ->
            repo.sensorAt(sel.handle)?.let { SensorEventSource.Reg(it, sel.handle, sel.periodUs) }
        }
        if (regs.isEmpty()) {
            _state.value = _state.value.copy(error = "No sensors selected")
            return
        }
        if (!sessionStarted) {
            // Build the recorder once per session (flags=0: recorder must not read tSerializeNs,
            // which the sender mutates concurrently; serialize-ts is meaningless for a local record).
            if (recorder == null) {
                recorder = recordDir?.let {
                    LocalRecorder(File(it, "onphone"), controller.deviceId, recMaxBytes, recMaxAgeMs, recSegmentMs, flags = 0)
                }
            }
            controller.startSession(regs, recorder)
            sessionStarted = true
        }
        controller.connectSender(host, udpPort)
        _state.value = _state.value.copy(streaming = true)
        control.sendActive(regs.map { it.handle })
    }
```

- [ ] **Step 3: Change `onDropped()` to pause only the sender**

The current `onDropped()` calls `controller.stop()`. Change it to `controller.disconnectSender()` so acquisition + recording continue during the outage. Keep the reconnect/backoff logic. Update the state so the UI shows "reconnecting" without claiming it stopped streaming:

```kotlin
    private fun onDropped() {
        controller.disconnectSender()   // keep acquiring + recording across the outage
        _state.value = _state.value.copy(connected = false)  // streaming stays true (recorder is live)
        if (!desired) {
            _state.value = _state.value.copy(connecting = false, streaming = false)
            controller.stopSession()
            return
        }
        val attempt = reconnectAttempts.coerceAtMost(4)
        reconnectAttempts += 1
        val delayMs = (500L shl attempt).coerceAtMost(8000L) // 0.5,1,2,4,8s
        _state.value = _state.value.copy(connecting = true)
        scope?.launch {
            delay(delayMs)
            if (desired && isActive) connectControl()
        }
    }
```

- [ ] **Step 4: Update `stop()` to end the session**

Replace the `controller.stop()` call in `stop()` with `controller.stopSession()`, and reset `sessionStarted`:

```kotlin
    fun stop() {
        desired = false
        connGen++
        controller.onUiSample = null
        controller.stopSession()   // flushes recorder tail; leaves recorder for engine to close
        control.close()
        scope?.cancel()
        scope = null
        recorder?.close()
        recorder = null
        sessionStarted = false
        _state.value = _state.value.copy(connecting = false, connected = false, streaming = false)
    }
```

- [ ] **Step 5: Build gate (now green)**

Run: `cd /c/mobile_to_ROS_app/android && JAVA_HOME="/c/Users/devam/.jdks/jbr-21.0.11" ./gradlew testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL; unit tests still pass (11 total: 2 codec + 9 LocalRecorder). No `controller.start`/`controller.stop` references remain.

- [ ] **Step 6: Commit (Tasks 2 + 3 together)**

```bash
git add android/app/src/main/java/com/sensorstream/stream/StreamController.kt \
        android/app/src/main/java/com/sensorstream/stream/StreamEngine.kt
git commit -m "feat(phone): keep acquiring+recording across WS drops (session/sender split)

Split StreamController into a session-long acquisition+recorder half
(startSession/stopSession) and a per-connection UDP sender half
(connectSender/disconnectSender). A control-channel drop now pauses only
the UDP sender: SensorEventSource keeps firing and the recorder keeps
writing, so the on-phone .ssbin has no hole during an outage and per-sensor
seq stays continuous across reconnects. StreamEngine builds the recorder +
starts the session once, re-attaches the sender on each onHelloAck, and on
a drop calls disconnectSender() instead of tearing acquisition down.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: On-device verification — recording continues across a drop, seq continuous

**Files:** none (verification task; run by the controller + user).

**Interfaces:** Consumes the built APK from Task 3. Produces the evidence that 2B can rely on: a hole-free `.ssbin` across an outage and continuous `seq`.

- [ ] **Step 1: Install the build**

```bash
ADB="/c/Users/devam/AppData/Local/Android/Sdk/platform-tools/adb.exe"; D=<serial>
cd /c/mobile_to_ROS_app/android
JAVA_HOME="/c/Users/devam/.jdks/jbr-21.0.11" ./gradlew assembleDebug
"$ADB" -s $D install -r app/build/outputs/apk/debug/app-debug.apk   # expect: Success
```

- [ ] **Step 2: Start the laptop server and stream from the phone**

Start `python -m sensorstream.app` on the laptop (same LAN as the phone). On the phone: Find laptop automatically → select a few sensors → Start. Confirm the status card shows `on-phone rec … · buffered … · pruned 0`.

- [ ] **Step 3: Force a control-channel outage WITHOUT stopping acquisition**

Kill the laptop server (`taskkill //PID <pid> //F`), wait ~15 s, restart it. On the phone the status should show reconnecting then streaming again.

- [ ] **Step 4: Verify recording had NO hole during the outage**

```bash
"$ADB" -s $D exec-out run-as com.sensorstream sh -c 'ls -la files/onphone | sort -t_ -k3 -n'
```
Expected (the Phase-2A change): the segment file **timestamps are continuous across the outage** (roughly one every ~10 s with **no ~15 s gap**), unlike Phase 1 where acquisition stopped during a full disconnect. The salt series continues monotonically (recorder reused — Phase 1 behavior retained).

- [ ] **Step 5: Verify seq continuity across the reconnect**

Pull two segments spanning the outage and confirm the per-handle `seq` is monotonic across the boundary (no reset to 0). Quick check on the laptop with the existing decoder:

```bash
# copy one pre-outage and one post-outage segment off the phone, then:
python - <<'PY'
from sensorstream.protocol import decode_datagram
# read a .ssbin: skip 7-byte magic, then repeated (i64 tAcq,u32 len)+datagram
import struct, sys
def seqs(path, handle):
    b=open(path,'rb').read(); assert b[:7]==b'SSLOG1\n'; o=7; out=[]
    while o+12<=len(b):
        _,dl=struct.unpack_from('<qI',b,o); o+=12
        dg=decode_datagram(b[o:o+dl]); o+=dl
        for r in dg.records:
            if r.sensor_handle==handle: out.append(r.seq)
    return out
# print last few seqs of the pre-outage file and first few of the post-outage file
PY
```
Expected: the last `seq` before the outage and the first `seq` after it are **consecutive-ish and strictly increasing** (continuous), confirming acquisition never reset.

- [ ] **Step 6: Verify the laptop `hello` carries `client_id`**

On the laptop, log or inspect the incoming hello (temporary print in `control._on_hello`, or a `read_console`): confirm a non-empty `client_id`, and that it is the **same value after a reconnect** (persisted).

- [ ] **Step 7: Record the evidence**

Capture the segment listing (continuous timestamps), the seq-continuity result, and the stable `client_id` into the SDD ledger / a short note. This is the acceptance gate for Plan 2A and the precondition set Plan 2B builds on.

---

## Self-Review

**Spec coverage (phone-side prerequisites):**
- §5.1 acquisition fan-out stays lossless off the callback — preserved in Task 2 (`recorderDropped` guarded `trySend`). ✔
- "keep acquiring across drops" (the Full-spec scope the user chose) — Tasks 2+3. ✔
- Stable identity for cross-reconnect keying (§8 reconnect handling) — Task 1 `client_id`. ✔
- Continuous seq across reconnect — falls out of Task 2 (source never stopped mid-session); verified in Task 4. ✔
- Wire format / `.ssbin` / codec untouched — Global Constraints; no task edits them. ✔
- Backfill responder, ACK-prune, laptop gap tracker/reconciler, recordings dedup, diagnostics — **deferred to Plan 2B** (out of scope here by design). ✔ (gap is intentional, not missing.)

**Placeholder scan:** No TBD/TODO; every code step has real code; the one deliberately-red build (Task 2 Step 2) is called out with the reason and the task that greens it.

**Type consistency:** `ClientId.getOrCreate(read, write)` used identically in test, impl, and Service wiring. `StreamController` new methods (`startSession`, `connectSender`, `disconnectSender`, `stopSession`, `updateRegs`) match their `StreamEngine` call sites in Task 3. `recorder`/`sessionScope`/`channel`/`recCh` field names consistent across Task 2's methods. `flags = 0` recorder construction matches Phase 1.

---

## Execution Handoff

Plan complete and saved. Plan 2B (backfill protocol + laptop reconciliation + phone responder + recordings dedup + diagnostics) will be written against the realized 2A interfaces once 2A lands.
