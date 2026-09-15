# Phase 2B — Backfill Protocol + Laptop Reconciliation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the laptop's session recording **gap-free** after UDP loss (screen-off/throttle or a Wi-Fi drop+reconnect): the laptop detects missing per-sensor `seq` ranges, requests them over the reliable WS control channel, the phone serves the exact recorded datagrams from its `.ssbin` ring, and the laptop merges them into the session recording — deduped and ordered on read.

**Architecture:** Live UDP is untouched (no added latency). A laptop **GapTracker**, fed by the receiver and keyed by the phone's stable **`client_id`** (from Phase 2A, so gaps survive the `device_id` rotation on reconnect), records missing `seq` ranges per `(client_id, handle)`. After a grace window a **Reconciler** sends a `resend` message over the current phone's control WS. The phone's **backfill responder** reads the requested `(handle, seq)` datagrams via `LocalRecorder.readRawRange` (Phase 1) and returns them as **base64-encoded raw datagram bytes** (the phone codec is encode-only — reusing the frozen wire bytes avoids any phone-side decoder or a parallel JSON record schema). The laptop decodes each with the existing `decode_datagram`, appends them to the session `.ssbin`, and `recordings.py` **dedups by `(handle, seq)`** on read so late-live + backfilled duplicates collapse into one gap-free, time-ordered timeline. ACKs let the phone prune served data; ranges no longer in the ring return `backfill_unavailable` → a counted permanent gap.

**Tech Stack:** Python 3.11 asyncio (laptop: `protocol`, `control`, `receiver`, `logging_sink`, `recordings`, `app`, `dashboard`), pytest. Kotlin + OkHttp + kotlinx.coroutines (phone: `WsControlClient`, `StreamEngine`, `LocalRecorder`), JVM JUnit. Build phone with **JBR 21**.

**Spec:** `docs/superpowers/specs/2026-09-14-onphone-recording-backfill-design.md` (this plan implements §6, §7, §8, §10, §11, §12). Builds directly on Plan 2A (`docs/superpowers/plans/2026-09-15-onphone-backfill-phase2a-phone-foundation.md`), which is DONE + on-device verified.

## Global Constraints

- **Do not change the telemetry wire format or the golden-pinned codec** (`BinaryPacketCodec.kt` / `protocol.encode_datagram`/`decode_datagram`). Backfill adds **control-channel JSON messages only**; the datagram bytes it carries are the exact recorded wire bytes.
- **Live UDP path is untouched** — backfill must never add latency to or gate the live sender/receiver. It runs on the reliable WS control channel and off the hot path.
- **`.ssbin` stays byte-compatible** with `logging_sink.py` (magic `SSLOG1\n`, frame `struct <qI` = `(t_recv_ns i64 LE, datagram_len u32 LE)`, then datagram bytes). Backfilled datagrams are appended as ordinary frames (out-of-order on disk is fine; dedup/order happens on read).
- **Backfill transport = base64 of raw datagram bytes** (one record per datagram, matching how `LocalRecorder` stores them). This is a deliberate deviation from spec §7's JSON-record shape, chosen because the phone codec is encode-only; documented in Task 1.
- **Chunk backfill** to bound WS frame size: ≤ 256 datagrams per `backfill` message.
- **Grace window before requesting** a gap: default **750 ms** (a merely-late live packet must not be treated as a gap).
- **Phone sensor callback stays lightweight**; `LocalRecorder` stays Context-free and JVM-testable; recorder methods are already `@Synchronized` (Phase 1) so `readRawRange` from the WS thread is safe against the IO writer.
- **Build/run phone with JBR 21**: prefix Gradle with `JAVA_HOME="/c/Users/devam/.jdks/jbr-21.0.11"`. Laptop tests: `cd laptop && ./.venv/Scripts/python.exe -m pytest`. Device serials: S25 `RZGL31H3N4V`, M36 `RZGL50Z0GYJ`.
- **New control messages** (constants in both `protocol.py` and the Kotlin client): `MSG_RESEND="resend"`, `MSG_BACKFILL="backfill"`, `MSG_BACKFILL_UNAVAILABLE="backfill_unavailable"`, `MSG_BACKFILL_ACK="backfill_ack"`.

---

### Task 1: Protocol constants + base64 frame helpers (laptop)

**Files:**
- Modify: `laptop/sensorstream/protocol.py` (add 4 message-type constants + two helpers)
- Test: `laptop/tests/test_backfill_protocol.py` (create)

**Interfaces:**
- Produces: `MSG_RESEND`, `MSG_BACKFILL`, `MSG_BACKFILL_UNAVAILABLE`, `MSG_BACKFILL_ACK` (str constants).
- Produces: `encode_frame_b64(datagram_bytes: bytes) -> str` and `decode_frame_b64(s: str) -> bytes` (round-trip base64; `decode_frame_b64` raises `ProtocolError` on malformed input).
- Consumes: `ProtocolError` (existing).

- [ ] **Step 1: Write the failing test**

```python
# laptop/tests/test_backfill_protocol.py
import pytest
from sensorstream import protocol as p


def test_message_constants_present():
    assert p.MSG_RESEND == "resend"
    assert p.MSG_BACKFILL == "backfill"
    assert p.MSG_BACKFILL_UNAVAILABLE == "backfill_unavailable"
    assert p.MSG_BACKFILL_ACK == "backfill_ack"


def test_frame_b64_roundtrip():
    raw = p.encode_datagram(p.Datagram(device_id=7, records=[
        p.Record(1, 0, 42, 1234, 3, [1.0, 2.0, 3.0])]))
    s = p.encode_frame_b64(raw)
    assert isinstance(s, str)
    assert p.decode_frame_b64(s) == raw


def test_decode_frame_b64_rejects_garbage():
    with pytest.raises(p.ProtocolError):
        p.decode_frame_b64("not!base64!!")
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd laptop && ./.venv/Scripts/python.exe -m pytest tests/test_backfill_protocol.py -q`
Expected: FAIL — `AttributeError: module 'sensorstream.protocol' has no attribute 'MSG_RESEND'`.

- [ ] **Step 3: Write minimal implementation**

In `protocol.py`, after the existing `MSG_ACTIVE = "active"` line, add:

```python
MSG_RESEND = "resend"                       # laptop→phone: request missing seq ranges
MSG_BACKFILL = "backfill"                   # phone→laptop: base64 raw datagrams for a range
MSG_BACKFILL_UNAVAILABLE = "backfill_unavailable"  # phone→laptop: ranges no longer in the ring
MSG_BACKFILL_ACK = "backfill_ack"           # laptop→phone: highest contiguous seq safe to prune
```

At the end of `protocol.py`, add the helpers:

```python
import base64  # (place with the other imports at the top; shown here for locality)


def encode_frame_b64(datagram_bytes: bytes) -> str:
    """Base64-encode one raw telemetry datagram for carriage over the JSON control channel.

    Backfill reuses the exact recorded wire bytes (the phone codec is encode-only), so the
    laptop decodes them with the ordinary :func:`decode_datagram`. One record per datagram.
    """
    return base64.b64encode(datagram_bytes).decode("ascii")


def decode_frame_b64(s: str) -> bytes:
    """Inverse of :func:`encode_frame_b64`. Raises :class:`ProtocolError` on malformed input."""
    try:
        return base64.b64decode(s, validate=True)
    except (ValueError, TypeError) as exc:
        raise ProtocolError(f"bad base64 frame: {exc}") from exc
```

(Move the `import base64` up next to `import struct` at the top of the file.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd laptop && ./.venv/Scripts/python.exe -m pytest tests/test_backfill_protocol.py -q`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add laptop/sensorstream/protocol.py laptop/tests/test_backfill_protocol.py
git commit -m "feat(laptop): backfill control-message constants + base64 frame helpers

MSG_RESEND/MSG_BACKFILL/MSG_BACKFILL_UNAVAILABLE/MSG_BACKFILL_ACK plus
encode_frame_b64/decode_frame_b64 to carry raw datagram bytes over the JSON
control channel (phone codec is encode-only, so backfill reuses wire bytes).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: GapTracker — detect missing seq ranges per (client, handle) (laptop)

**Files:**
- Create: `laptop/sensorstream/backfill.py`
- Test: `laptop/tests/test_gap_tracker.py`

**Interfaces:**
- Produces:
  ```python
  class GapTracker:
      def __init__(self, grace_ns: int = 750_000_000): ...
      def observe(self, client_id: str, handle: int, seq: int, t_recv_ns: int) -> None
      def due_ranges(self, now_ns: int) -> list[tuple[str, int, int, int]]
          # -> list of (client_id, handle, from_seq, to_seq) inclusive, whose gap is older than grace
      def resolve(self, client_id: str, handle: int, seqs: list[int]) -> None
          # mark these seqs received (via backfill), shrinking/closing tracked gaps
      def mark_permanent(self, client_id: str, handle: int, from_seq: int, to_seq: int) -> None
      def contiguous_upto(self, client_id: str, handle: int) -> int
          # highest seq with no gap below it (for backfill_ack pruning)
      def stats(self) -> dict  # {"open_gaps": n, "permanent": n, "backfilled": n}
  ```
- Consumes: nothing external (pure). `seq` is monotonic within a session (Phase 2A guarantee) and keyed by `client_id` so it survives `device_id` rotation on reconnect.

- [ ] **Step 1: Write the failing test**

```python
# laptop/tests/test_gap_tracker.py
from sensorstream.backfill import GapTracker

C = "client-1"
S = 1_000_000_000  # 1s in ns


def test_no_gap_when_contiguous():
    g = GapTracker(grace_ns=S)
    for seq in range(5):
        g.observe(C, 0, seq, t_recv_ns=seq * S)
    assert g.due_ranges(now_ns=10 * S) == []
    assert g.contiguous_upto(C, 0) == 4


def test_detects_gap_after_grace():
    g = GapTracker(grace_ns=S)
    g.observe(C, 0, 0, t_recv_ns=0)
    g.observe(C, 0, 3, t_recv_ns=1 * S)   # missing 1,2
    # within grace: not yet due
    assert g.due_ranges(now_ns=1 * S) == []
    # after grace: due
    assert g.due_ranges(now_ns=1 * S + S + 1) == [(C, 0, 1, 2)]


def test_resolve_closes_gap():
    g = GapTracker(grace_ns=S)
    g.observe(C, 0, 0, 0)
    g.observe(C, 0, 3, 1 * S)
    g.resolve(C, 0, [1, 2])
    assert g.due_ranges(now_ns=100 * S) == []
    assert g.contiguous_upto(C, 0) == 3


def test_partial_resolve_leaves_remainder():
    g = GapTracker(grace_ns=S)
    g.observe(C, 0, 0, 0)
    g.observe(C, 0, 4, 1 * S)   # missing 1,2,3
    g.resolve(C, 0, [2])        # 1 and 3 still missing
    due = g.due_ranges(now_ns=100 * S)
    # remaining missing seqs are 1 and 3 (coalescing may report them as ranges)
    missing = set()
    for (_c, _h, a, b) in due:
        missing |= set(range(a, b + 1))
    assert missing == {1, 3}


def test_permanent_gap_not_re_requested():
    g = GapTracker(grace_ns=S)
    g.observe(C, 0, 0, 0)
    g.observe(C, 0, 3, 1 * S)
    g.mark_permanent(C, 0, 1, 2)
    assert g.due_ranges(now_ns=100 * S) == []
    assert g.stats()["permanent"] == 2


def test_two_clients_isolated():
    g = GapTracker(grace_ns=S)
    g.observe("a", 0, 0, 0); g.observe("a", 0, 2, S)   # a missing 1
    g.observe("b", 0, 0, 0); g.observe("b", 0, 1, S)   # b contiguous
    due = g.due_ranges(now_ns=100 * S)
    assert due == [("a", 0, 1, 1)]
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd laptop && ./.venv/Scripts/python.exe -m pytest tests/test_gap_tracker.py -q`
Expected: FAIL — `ModuleNotFoundError: No module named 'sensorstream.backfill'`.

- [ ] **Step 3: Write minimal implementation**

```python
# laptop/sensorstream/backfill.py
"""Laptop-side backfill: gap detection + reconciliation state.

Keyed by the phone's stable ``client_id`` (Phase 2A) so tracked gaps survive the
``device_id`` rotation on reconnect. Pure/state-only — no I/O — so it unit-tests cleanly
and can be driven from the asyncio loop without blocking it.
"""
from __future__ import annotations

from typing import Dict, List, Tuple

Key = Tuple[str, int]  # (client_id, handle)


class _PerSensor:
    __slots__ = ("received", "missing", "permanent", "backfilled", "max_seq", "gap_since")

    def __init__(self) -> None:
        self.missing: Dict[int, int] = {}     # seq -> t_recv_ns when first noticed missing
        self.permanent: set[int] = set()
        self.backfilled = 0
        self.max_seq = -1

    def observe(self, seq: int, t_recv_ns: int) -> None:
        if self.max_seq < 0:
            self.max_seq = seq
            return
        if seq > self.max_seq:
            for s in range(self.max_seq + 1, seq):      # newly-missing interior seqs
                if s not in self.permanent:
                    self.missing.setdefault(s, t_recv_ns)
            self.max_seq = seq
        self.missing.pop(seq, None)                     # a late/backfilled arrival fills a hole

    def contiguous_upto(self) -> int:
        # highest seq with no missing/permanent hole strictly below it
        holes = self.missing.keys() | self.permanent
        if not holes:
            return self.max_seq
        return min(holes) - 1


def _coalesce(seqs: List[int]) -> List[Tuple[int, int]]:
    out: List[Tuple[int, int]] = []
    for s in sorted(seqs):
        if out and s == out[-1][1] + 1:
            out[-1] = (out[-1][0], s)
        else:
            out.append((s, s))
    return out


class GapTracker:
    def __init__(self, grace_ns: int = 750_000_000) -> None:
        self._grace = grace_ns
        self._sensors: Dict[Key, _PerSensor] = {}

    def _get(self, client_id: str, handle: int) -> _PerSensor:
        return self._sensors.setdefault((client_id, handle), _PerSensor())

    def observe(self, client_id: str, handle: int, seq: int, t_recv_ns: int) -> None:
        self._get(client_id, handle).observe(seq, t_recv_ns)

    def due_ranges(self, now_ns: int) -> List[Tuple[str, int, int, int]]:
        out: List[Tuple[str, int, int, int]] = []
        for (cid, h), ps in self._sensors.items():
            due = [s for s, t in ps.missing.items() if now_ns - t >= self._grace]
            for a, b in _coalesce(due):
                out.append((cid, h, a, b))
        out.sort()
        return out

    def resolve(self, client_id: str, handle: int, seqs: List[int]) -> None:
        ps = self._get(client_id, handle)
        for s in seqs:
            if ps.missing.pop(s, None) is not None:
                ps.backfilled += 1

    def mark_permanent(self, client_id: str, handle: int, from_seq: int, to_seq: int) -> None:
        ps = self._get(client_id, handle)
        for s in range(from_seq, to_seq + 1):
            ps.missing.pop(s, None)
            ps.permanent.add(s)

    def contiguous_upto(self, client_id: str, handle: int) -> int:
        return self._get(client_id, handle).contiguous_upto()

    def stats(self) -> dict:
        open_gaps = sum(len(ps.missing) for ps in self._sensors.values())
        permanent = sum(len(ps.permanent) for ps in self._sensors.values())
        backfilled = sum(ps.backfilled for ps in self._sensors.values())
        return {"open_gaps": open_gaps, "permanent": permanent, "backfilled": backfilled}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd laptop && ./.venv/Scripts/python.exe -m pytest tests/test_gap_tracker.py -q`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add laptop/sensorstream/backfill.py laptop/tests/test_gap_tracker.py
git commit -m "feat(laptop): GapTracker — per-(client,handle) missing-seq detection with grace window

Keyed by stable client_id so gaps survive device_id rotation on reconnect.
Pure/state-only: observe seqs, report due ranges past the grace window,
resolve on backfill, mark permanent when unavailable, and report the
contiguous-upto seq for ACK pruning.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Dedup + order on read in recordings.py (laptop)

**Files:**
- Modify: `laptop/sensorstream/recordings.py` (`query_signal` and `_peek_sensors` dedup by `(handle, seq)`, order by `t_sensor_ns`)
- Test: `laptop/tests/test_recordings_dedup.py`

**Interfaces:**
- Consumes: `read_frames`, `decode_datagram` (existing).
- Produces: `query_signal` returns a series with duplicates collapsed by `(handle, seq)` and ordered by `t_sensor_ns`, even when the `.ssbin` has out-of-order / duplicated frames (as backfill appends produce).

- [ ] **Step 1: Write the failing test**

```python
# laptop/tests/test_recordings_dedup.py
import os
from sensorstream import protocol as p
from sensorstream.logging_sink import LOG_MAGIC, FRAME
from sensorstream import recordings


def _write_ssbin(path, frames):
    with open(path, "wb") as fh:
        fh.write(LOG_MAGIC)
        for t_recv, dg in frames:
            raw = p.encode_datagram(dg)
            fh.write(FRAME.pack(t_recv, len(raw)))
            fh.write(raw)


def _dg(handle, seq, t_sensor, val):
    return p.Datagram(device_id=1, records=[p.Record(1, handle, seq, t_sensor, 3, [val])])


def test_query_signal_dedups_and_orders(tmp_path):
    # frames written OUT OF ORDER with a duplicate (seq 1 appears twice: late-live + backfill)
    frames = [
        (10, _dg(0, 0, 100, 0.0)),
        (12, _dg(0, 2, 300, 2.0)),   # seq 1 missing here (arrived late below)
        (30, _dg(0, 1, 200, 1.0)),   # backfilled, out of order on disk
        (31, _dg(0, 1, 200, 1.0)),   # duplicate of seq 1
    ]
    path = tmp_path / "session_x.ssbin"
    _write_ssbin(str(path), frames)
    res = recordings.query_signal(str(tmp_path), "session_x", handle=0, buckets=1000)
    assert res is not None
    # 3 unique records (seq 0,1,2), ordered by t_sensor_ns, duplicate collapsed
    assert res["raw"] == 3
    assert res["t"] == [round((100 - 100) / 1e9, 6), round((200 - 100) / 1e9, 6), round((300 - 100) / 1e9, 6)]
    assert res["avg"][0] == [0.0, 1.0, 2.0]
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd laptop && ./.venv/Scripts/python.exe -m pytest tests/test_recordings_dedup.py -q`
Expected: FAIL — current `query_signal` appends every record in file order, so `raw == 4` (duplicate counted) and `t` is unsorted.

- [ ] **Step 3: Write minimal implementation**

In `recordings.py` `query_signal`, replace the per-record collection loop (the `for _t_recv, data in read_frames(path): ... ts.append(t); vals.append(r.values)` block, before the `if not ts:` check) with a dedup-by-`(handle,seq)` map, then sort by `t_sensor_ns`:

```python
    seen: dict = {}   # seq -> (t_sensor_ns, values); dedup backfill/late duplicates by seq
    stype: Optional[int] = None
    ncomp = 0
    for _t_recv, data in read_frames(path):
        try:
            dg = p.decode_datagram(data)
        except p.ProtocolError:
            continue
        for r in dg.records:
            if r.sensor_handle != handle:
                continue
            t = r.t_sensor_ns
            if start_ns is not None and t < start_ns:
                continue
            if end_ns is not None and t > end_ns:
                continue
            if r.seq not in seen:          # first write wins; duplicates collapse
                seen[r.seq] = (t, r.values)
                if stype is None:
                    stype = r.sensor_type
                    ncomp = len(r.values)

    if not seen:
        return {"handle": handle, "type": stype, "ncomp": 0, "raw": 0, "buckets": 0,
                "start": None, "end": None, "t": [], "avg": [], "min": [], "max": []}

    ordered = sorted(seen.values(), key=lambda tv: tv[0])   # by t_sensor_ns
    ts = [t for t, _v in ordered]
    vals = [v for _t, v in ordered]
```

The rest of `query_signal` (bucketing over `ts`/`vals`) is unchanged. Also update `_peek_sensors` to dedup — it only tracks distinct handles so no change is needed there (it already keys `found` by handle); leave it.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd laptop && ./.venv/Scripts/python.exe -m pytest tests/test_recordings_dedup.py tests/ -q`
Expected: PASS (new test + no regression across the suite).

- [ ] **Step 5: Commit**

```bash
git add laptop/sensorstream/recordings.py laptop/tests/test_recordings_dedup.py
git commit -m "feat(laptop): dedup+order recordings by (handle,seq) on read

query_signal now collapses duplicate seqs (late-live + backfill) and orders
by t_sensor_ns, so an .ssbin with out-of-order/duplicated appended frames
reads back as a coherent gap-free timeline.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Phone backfill responder (serve requested ranges from the ring)

**Files:**
- Modify: `android/app/src/main/java/com/sensorstream/net/WsControlClient.kt` (parse `resend`, add `sendBackfill`/`sendBackfillUnavailable`)
- Modify: `android/app/src/main/java/com/sensorstream/stream/StreamEngine.kt` (handle `resend` → read ring → reply)
- Create: `android/app/src/main/java/com/sensorstream/stream/BackfillResponder.kt` (pure chunking helper)
- Test: `android/app/src/test/java/com/sensorstream/stream/BackfillResponderTest.kt`

**Interfaces:**
- Produces: `WsControlClient.Listener.onResend(msg: JSONObject)`; `WsControlClient.sendBackfill(deviceId: Int, clientId: String, handle: Int, framesB64: List<String>)`; `WsControlClient.sendBackfillUnavailable(deviceId: Int, clientId: String, handle: Int, fromSeq: Long, toSeq: Long)`.
- Produces: `object BackfillResponder { const val MAX_FRAMES_PER_MSG = 256; fun chunk(frames: List<ByteArray>): List<List<String>> }` — base64-encodes and chunks datagrams (≤256/msg). Pure, JVM-testable.
- Consumes: `LocalRecorder.readRawRange(handle, fromSeq, toSeq)` (Phase 1), `StreamEngine.recorder` + `clientId` (Phase 2A).

- [ ] **Step 1: Write the failing test**

```kotlin
// android/app/src/test/java/com/sensorstream/stream/BackfillResponderTest.kt
package com.sensorstream.stream

import android.util.Base64  // NB: see Step 3 note — use java.util.Base64 for JVM tests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackfillResponderTest {
    @Test fun chunksAndBase64EncodesFrames() {
        val frames = (0 until 600).map { byteArrayOf(it.toByte(), 1, 2, 3) }
        val chunks = BackfillResponder.chunk(frames)
        // 600 frames / 256 per msg -> 3 chunks (256, 256, 88)
        assertEquals(3, chunks.size)
        assertEquals(256, chunks[0].size)
        assertEquals(88, chunks[2].size)
        // each entry is base64 of the original bytes
        val first = java.util.Base64.getDecoder().decode(chunks[0][0])
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), first)
    }

    @Test fun emptyFramesProducesNoChunks() {
        assertTrue(BackfillResponder.chunk(emptyList()).isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /c/mobile_to_ROS_app/android && JAVA_HOME="/c/Users/devam/.jdks/jbr-21.0.11" ./gradlew testDebugUnitTest --tests "com.sensorstream.stream.BackfillResponderTest"`
Expected: FAIL — `Unresolved reference: BackfillResponder`.

- [ ] **Step 3: Write minimal implementation**

`BackfillResponder.kt` — use `java.util.Base64` (available on the JVM and Android API 26+; the app's minSdk is 24, but the recorder only ever runs on the streaming path where API 26+ is fine — if a minSdk-24 lint error appears, use `android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)` in the impl and keep the test using `java.util.Base64` for decoding, OR gate via `Build.VERSION`. Prefer `android.util.Base64` to be safe on-device; the pure `chunk` logic is what the test pins):

```kotlin
// android/app/src/main/java/com/sensorstream/stream/BackfillResponder.kt
package com.sensorstream.stream

import android.util.Base64

/** Pure helper: base64-encode recorded datagrams and chunk them to bound WS frame size. */
object BackfillResponder {
    const val MAX_FRAMES_PER_MSG = 256

    fun chunk(frames: List<ByteArray>): List<List<String>> {
        if (frames.isEmpty()) return emptyList()
        val out = ArrayList<List<String>>()
        var i = 0
        while (i < frames.size) {
            val end = minOf(i + MAX_FRAMES_PER_MSG, frames.size)
            out.add(frames.subList(i, end).map { Base64.encodeToString(it, Base64.NO_WRAP) })
            i = end
        }
        return out
    }
}
```

> Note: `android.util.Base64` is stubbed (throws) in plain JVM unit tests. To keep `chunk` JVM-testable, the test above decodes with `java.util.Base64`, but the impl's `Base64.encodeToString` would throw under the stub. **Resolution:** make the impl use `java.util.Base64.getEncoder().withoutPadding()`? No — padding matters for round-trip. Use `java.util.Base64.getEncoder().encodeToString(...)` in the impl (works on JVM tests AND on Android, since `java.util.Base64` is available from API 26; minSdk 24 → guard). Simplest robust choice: implement with `java.util.Base64` and set the responder call sites behind API 26 (streaming requires a foreground service already). Update the impl to:
> ```kotlin
> import java.util.Base64 as JBase64
> ...
> out.add(frames.subList(i, end).map { JBase64.getEncoder().encodeToString(it) })
> ```
> and drop the `android.util.Base64` import. This makes the test pass without an Android stub. Confirm minSdk handling with a `@RequiresApi(26)`-free path by testing on-device in Task 7 (S25/M36 are API 34+).

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /c/mobile_to_ROS_app/android && JAVA_HOME="/c/Users/devam/.jdks/jbr-21.0.11" ./gradlew testDebugUnitTest --tests "com.sensorstream.stream.BackfillResponderTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Wire the responder into WsControlClient**

Add to the `Listener` interface: `fun onResend(msg: JSONObject)`. In `onMessage`'s `when`, add:
```kotlin
                    "resend" -> listener.onResend(msg)
```
Add send methods to `WsControlClient`:
```kotlin
    fun sendBackfill(deviceId: Int, clientId: String, handle: Int, framesB64: List<String>) {
        val arr = org.json.JSONArray()
        for (f in framesB64) arr.put(f)
        ws?.send(JSONObject()
            .put("type", "backfill").put("device_id", deviceId).put("client_id", clientId)
            .put("handle", handle).put("frames", arr).toString())
    }

    fun sendBackfillUnavailable(deviceId: Int, clientId: String, handle: Int, fromSeq: Long, toSeq: Long) {
        ws?.send(JSONObject()
            .put("type", "backfill_unavailable").put("device_id", deviceId).put("client_id", clientId)
            .put("handle", handle).put("from", fromSeq).put("to", toSeq).toString())
    }
```
Add a no-op `override fun onResend(msg: JSONObject) {}` to any other `Listener` implementers if present (search `: WsControlClient.Listener`), so they still compile.

- [ ] **Step 6: Handle resend in StreamEngine**

In `StreamEngine.connectControl()`'s `object : WsControlClient.Listener`, add:
```kotlin
            override fun onResend(msg: JSONObject) {
                if (gen != connGen) return
                val rec = recorder ?: return
                val handle = msg.optInt("handle", -1)
                if (handle < 0) return
                val from = msg.optLong("from", -1)
                val to = msg.optLong("to", -1)
                if (from < 0 || to < from) return
                // Serve from the on-phone ring off the WS thread; recorder methods are @Synchronized.
                scope?.launch(Dispatchers.IO) {
                    val frames = rec.readRawRange(handle, from, to)   // List<ByteArray>, sorted by seq
                    if (frames.isEmpty()) {
                        control.sendBackfillUnavailable(controller.deviceId, clientId, handle, from, to)
                        return@launch
                    }
                    for (batch in BackfillResponder.chunk(frames)) {
                        control.sendBackfill(controller.deviceId, clientId, handle, batch)
                    }
                    // If the ring held fewer than requested (pruned prefix), tell the laptop the rest is gone.
                    // (Laptop also infers this from seqs it never receives; explicit is cleaner.)
                }
            }
```
Ensure `import kotlinx.coroutines.Dispatchers` is present in `StreamEngine.kt` (it is, from Phase 1).

- [ ] **Step 7: Build gate + unit tests**

Run: `cd /c/mobile_to_ROS_app/android && JAVA_HOME="/c/Users/devam/.jdks/jbr-21.0.11" ./gradlew testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL; unit tests pass (16 total: 2 codec + 3 ClientId + 9 LocalRecorder + 2 BackfillResponder).

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/sensorstream/stream/BackfillResponder.kt \
        android/app/src/test/java/com/sensorstream/stream/BackfillResponderTest.kt \
        android/app/src/main/java/com/sensorstream/net/WsControlClient.kt \
        android/app/src/main/java/com/sensorstream/stream/StreamEngine.kt
git commit -m "feat(phone): backfill responder — serve requested seq ranges from the ring

WsControlClient parses 'resend' and gains sendBackfill/sendBackfillUnavailable;
StreamEngine reads the requested (handle, from..to) datagrams from LocalRecorder
(off the WS thread; recorder is @Synchronized) and returns them base64-encoded
and chunked (<=256/msg) via BackfillResponder, or backfill_unavailable when the
ring no longer holds them.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Reconciler — laptop wiring (track gaps, request, merge, ACK)

**Files:**
- Modify: `laptop/sensorstream/control.py` (store `client_id` on `PhoneSession`; add `resend`/`backfill_ack` senders; route `backfill`/`backfill_unavailable` to a handler)
- Modify: `laptop/sensorstream/app.py` (build `GapTracker` + a `Reconciler` coroutine; feed the receiver; map `device_id`→`client_id`; append backfilled datagrams to the recorder)
- Create: `laptop/sensorstream/reconcile.py` (the `Reconciler` core, testable with a fake sender)
- Test: `laptop/tests/test_reconciler.py`

**Interfaces:**
- Consumes: `GapTracker` (Task 2), `decode_frame_b64`/`encode_frame_b64` + message constants (Task 1), `ControlServer.sessions` + a new `ControlServer.send_resend(device_id, handle, from, to)` and `ControlServer.send_backfill_ack(device_id, upto)`, `Recorder.on_datagram` (existing), `decode_datagram` (existing).
- Produces:
  ```python
  class Reconciler:
      def __init__(self, tracker: GapTracker, send_resend, on_backfilled, ack_every=2.0): ...
          # send_resend(device_id:int, handle:int, from_seq:int, to_seq:int) -> None
          # on_backfilled(dg: Datagram, t_recv_ns:int) -> None   # append to recorder
      def set_device_client(self, device_id: int, client_id: str) -> None
      def client_for(self, device_id: int) -> Optional[str]
      def on_live(self, device_id: int, dg: Datagram, t_recv_ns: int) -> None   # feed tracker
      async def tick(self, now_ns: int) -> None            # emit due resend requests
      def on_backfill(self, device_id: int, handle: int, frames_b64: list[str]) -> None
      def on_unavailable(self, device_id: int, handle: int, from_seq: int, to_seq: int) -> None
      def acks(self) -> list[tuple[int, list[tuple[int,int]]]]  # (device_id, [(handle, upto)])
      def stats(self) -> dict
  ```

- [ ] **Step 1: Write the failing test**

```python
# laptop/tests/test_reconciler.py
from sensorstream import protocol as p
from sensorstream.backfill import GapTracker
from sensorstream.reconcile import Reconciler

S = 1_000_000_000


def _dg(handle, seq, t_sensor, val, device_id=1):
    return p.Datagram(device_id=device_id, records=[p.Record(1, handle, seq, t_sensor, 3, [val])])


def test_requests_gap_then_merges_backfill():
    sent = []
    merged = []
    tr = Reconciler(GapTracker(grace_ns=S),
                    send_resend=lambda d, h, a, b: sent.append((d, h, a, b)),
                    on_backfilled=lambda dg, t: merged.append(dg))
    tr.set_device_client(1, "client-1")
    tr.on_live(1, _dg(0, 0, 100, 0.0), t_recv_ns=0)
    tr.on_live(1, _dg(0, 2, 300, 2.0), t_recv_ns=S)   # missing seq 1

    import asyncio
    asyncio.get_event_loop().run_until_complete(tr.tick(now_ns=2 * S + 1))
    assert sent == [(1, 0, 1, 1)]                       # requested the gap

    # phone replies with backfill for seq 1
    frame = p.encode_frame_b64(p.encode_datagram(_dg(0, 1, 200, 1.0)))
    tr.on_backfill(1, 0, [frame])
    assert len(merged) == 1 and merged[0].records[0].seq == 1
    assert tr.stats()["backfilled"] == 1

    # gap now closed: no further requests
    sent.clear()
    asyncio.get_event_loop().run_until_complete(tr.tick(now_ns=100 * S))
    assert sent == []


def test_unavailable_marks_permanent():
    tr = Reconciler(GapTracker(grace_ns=S), send_resend=lambda *a: None, on_backfilled=lambda *a: None)
    tr.set_device_client(1, "c")
    tr.on_live(1, _dg(0, 0, 100, 0.0), 0)
    tr.on_live(1, _dg(0, 2, 300, 2.0), S)
    tr.on_unavailable(1, 0, 1, 1)
    tr.stats()["permanent"] == 1
    import asyncio
    sent = []
    tr2 = tr
    asyncio.get_event_loop().run_until_complete(tr2.tick(now_ns=100 * S))
    # nothing to assert on sent (lambda); ensure permanent recorded
    assert tr.stats()["permanent"] == 1
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd laptop && ./.venv/Scripts/python.exe -m pytest tests/test_reconciler.py -q`
Expected: FAIL — `ModuleNotFoundError: No module named 'sensorstream.reconcile'`.

- [ ] **Step 3: Write the Reconciler**

```python
# laptop/sensorstream/reconcile.py
"""Reconciler: drives backfill from detected gaps and merges the results.

Pure of transport: it calls injected ``send_resend`` / ``on_backfilled`` callbacks, so it is
unit-testable and the asyncio app wires it to the control server + recorder. Keyed through the
GapTracker by client_id; the device_id<->client_id map is maintained here from control hellos.
"""
from __future__ import annotations

from typing import Callable, Dict, List, Optional, Tuple

from . import protocol as p
from .backfill import GapTracker


class Reconciler:
    def __init__(self, tracker: GapTracker,
                 send_resend: Callable[[int, int, int, int], None],
                 on_backfilled: Callable[[p.Datagram, int], None]) -> None:
        self._t = tracker
        self._send_resend = send_resend
        self._on_backfilled = on_backfilled
        self._dev2client: Dict[int, str] = {}
        self._client2dev: Dict[str, int] = {}
        self._inflight: set = set()   # (client_id, handle, from, to) already requested, awaiting reply

    def set_device_client(self, device_id: int, client_id: str) -> None:
        self._dev2client[device_id] = client_id
        if client_id:
            self._client2dev[client_id] = device_id   # latest device_id for this client

    def client_for(self, device_id: int) -> Optional[str]:
        return self._dev2client.get(device_id)

    def on_live(self, device_id: int, dg: p.Datagram, t_recv_ns: int) -> None:
        cid = self._dev2client.get(device_id)
        if cid is None:
            cid = f"dev:{device_id}"          # fallback: treat device as its own client until hello
            self.set_device_client(device_id, cid)
        for r in dg.records:
            self._t.observe(cid, r.sensor_handle, r.seq, t_recv_ns)

    async def tick(self, now_ns: int) -> None:
        for (cid, handle, a, b) in self._t.due_ranges(now_ns):
            key = (cid, handle, a, b)
            if key in self._inflight:
                continue
            dev = self._client2dev.get(cid)
            if dev is None:
                continue
            self._inflight.add(key)
            self._send_resend(dev, handle, a, b)

    def on_backfill(self, device_id: int, handle: int, frames_b64: List[str]) -> None:
        cid = self._dev2client.get(device_id) or f"dev:{device_id}"
        got: List[int] = []
        for s in frames_b64:
            try:
                raw = p.decode_frame_b64(s)
                dg = p.decode_datagram(raw)
            except p.ProtocolError:
                continue
            for r in dg.records:
                got.append(r.seq)
            self._on_backfilled(dg, 0)       # append to recorder (t_recv unknown; 0 is fine, dedup is by seq)
        self._t.resolve(cid, handle, got)
        self._inflight = {k for k in self._inflight if not (k[0] == cid and k[1] == handle
                                                            and set(range(k[2], k[3] + 1)) <= set(got))}

    def on_unavailable(self, device_id: int, handle: int, from_seq: int, to_seq: int) -> None:
        cid = self._dev2client.get(device_id) or f"dev:{device_id}"
        self._t.mark_permanent(cid, handle, from_seq, to_seq)
        self._inflight.discard((cid, handle, from_seq, to_seq))

    def acks(self) -> List[Tuple[int, List[Tuple[int, int]]]]:
        out = []
        for cid, dev in self._client2dev.items():
            per = []
            for (c, h) in list(self._t._sensors.keys()):   # internal access ok within package
                if c == cid:
                    per.append((h, self._t.contiguous_upto(cid, h)))
            if per:
                out.append((dev, per))
        return out

    def stats(self) -> dict:
        return self._t.stats()
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd laptop && ./.venv/Scripts/python.exe -m pytest tests/test_reconciler.py -q`
Expected: PASS (2 tests).

- [ ] **Step 5: Wire into control.py**

Add to `PhoneSession`: `client_id: str = ""`. In `_on_hello`, set `client_id=str(msg.get("client_id", ""))` on the session, and include it in the `phone_connected` event dict. Add senders to `ControlServer`:
```python
    async def send_resend(self, device_id: int, handle: int, from_seq: int, to_seq: int) -> bool:
        s = self._sessions.get(device_id)
        if s is None:
            return False
        await _send(s.ws, {"type": p.MSG_RESEND, "device_id": device_id,
                           "handle": handle, "from": from_seq, "to": to_seq})
        return True

    async def send_backfill_ack(self, device_id: int, upto: list) -> bool:
        s = self._sessions.get(device_id)
        if s is None:
            return False
        await _send(s.ws, {"type": p.MSG_BACKFILL_ACK, "device_id": device_id,
                           "upto": [{"handle": h, "seq": seq} for (h, seq) in upto]})
        return True
```
In `handle`'s message loop, route the phone→laptop backfill replies via `on_event` so the app can forward them to the Reconciler:
```python
                elif mtype == p.MSG_BACKFILL:
                    if session:
                        self._on_event({"kind": "backfill", "device_id": session.device_id,
                                        "handle": int(msg.get("handle", -1)),
                                        "frames": list(msg.get("frames") or [])})
                elif mtype == p.MSG_BACKFILL_UNAVAILABLE:
                    if session:
                        self._on_event({"kind": "backfill_unavailable", "device_id": session.device_id,
                                        "handle": int(msg.get("handle", -1)),
                                        "from": int(msg.get("from", 0)), "to": int(msg.get("to", 0))})
```

- [ ] **Step 6: Wire into app.py**

In `run()`: build the tracker + reconciler, feed the receiver, run a tick loop, and forward control events. Concretely:
- After `recorder = Recorder()`: 
  ```python
  from .backfill import GapTracker
  from .reconcile import Reconciler
  tracker = GapTracker()
  def _append_backfill(dg, t_recv_ns):
      if recorder.is_recording:
          recorder.on_datagram(dg, t_recv_ns)
  reconciler = Reconciler(tracker, send_resend=lambda d, h, a, b: asyncio.create_task(control.send_resend(d, h, a, b)),
                          on_backfilled=_append_backfill)
  ```
  (Define `reconciler` after `control` exists, or use a late binding: create `reconciler` right after `control = ControlServer(...)`.)
- In `on_dg`, after the existing sink/sync/record calls, add `reconciler.on_live(dg.device_id, dg, t_recv_ns)`.
- Wrap `dash.broadcast` used as `on_event` so backfill/hello events also reach the reconciler. Replace `control = ControlServer(udp_port, on_event=dash.broadcast)` with:
  ```python
  def on_control_event(ev: dict) -> None:
      kind = ev.get("kind")
      if kind == "phone_connected":
          reconciler.set_device_client(ev["device_id"], ev.get("client_id", ""))
      elif kind == "backfill":
          reconciler.on_backfill(ev["device_id"], ev["handle"], ev["frames"])
          return   # don't forward raw frames to the browser
      elif kind == "backfill_unavailable":
          reconciler.on_unavailable(ev["device_id"], ev["handle"], ev["from"], ev["to"])
          return
      dash.broadcast(ev)
  control = ControlServer(udp_port, on_event=on_control_event)
  ```
  (Create `reconciler` before `control` so the closure captures it; move the `reconciler = ...` block above the `control = ...` line, and have `send_resend` reference `control` late via a small indirection: define `_ctrl = {}` then `send_resend=lambda d,h,a,b: asyncio.create_task(_ctrl['c'].send_resend(d,h,a,b))` and set `_ctrl['c'] = control` after construction. Keep it simple and correct.)
- Add a reconcile tick + ACK task alongside `stats_task`:
  ```python
  async def reconcile_task():
      while True:
          await asyncio.sleep(0.25)
          await reconciler.tick(time.monotonic_ns())
      # ACKs can piggyback here every ~2s: for dev, upto in reconciler.acks(): await control.send_backfill_ack(dev, upto)
  ```
  and add `asyncio.create_task(reconcile_task())` to `tasks`. Add backfill stats to the `stats` broadcast (`"backfilled": reconciler.stats()["backfilled"], "permanent_gaps": reconciler.stats()["permanent"]`).
- Include the ACK send in the loop (every ~8 ticks) so the phone can prune.

- [ ] **Step 7: Run laptop suite**

Run: `cd laptop && ./.venv/Scripts/python.exe -m pytest -q`
Expected: PASS (existing 27 + new backfill/gap/dedup/reconciler tests). Fix any wiring import errors surfaced.

- [ ] **Step 8: Commit**

```bash
git add laptop/sensorstream/reconcile.py laptop/sensorstream/control.py laptop/sensorstream/app.py \
        laptop/tests/test_reconciler.py
git commit -m "feat(laptop): Reconciler wiring — request gaps, merge backfill, ACK to prune

ControlServer stores client_id and gains send_resend/send_backfill_ack and
routes backfill/backfill_unavailable via on_event. app.py builds a GapTracker
+ Reconciler, feeds the live receiver, ticks every 250ms to emit resend
requests for gaps past the grace window, appends backfilled datagrams to the
session recording (deduped on read), and marks unavailable ranges permanent.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: Diagnostics — surface backfill health (laptop dashboard + phone)

**Files:**
- Modify: `laptop/webapp/src/` (Diagnostics panel: show backfilled count + permanent gaps from the `stats` broadcast)
- Modify: `android/app/src/main/java/com/sensorstream/stream/StreamEngine.kt` + `StreamController.kt` (`backfillServed` counter) and `StreamScreen.kt` (show "backfill served N")

**Interfaces:**
- Consumes: `stats` broadcast fields `backfilled`, `permanent_gaps` (Task 5); `EngineState` gains `backfillServed: Long`.
- Produces: visible health metrics distinguishing live loss vs backfilled vs permanent gaps (spec §10).

- [ ] **Step 1: Phone counter** — add `val backfillServed = AtomicLong(0)` to `StreamController`, increment it in the `StreamEngine.onResend` handler per frame served (pass through a `controller.backfillServed.addAndGet(frames.size.toLong())`), thread into `EngineState.backfillServed` in the heartbeat loop, and render `· backfill served N` on the recording line in `StreamScreen` when > 0. Build gate: `assembleDebug` + unit tests green.

- [ ] **Step 2: Laptop dashboard** — in the Diagnostics view, read `backfilled` and `permanent_gaps` from the `stats` message (already broadcast) and show two rows: "Backfilled records" and "Permanent gaps" (red when > 0). Rebuild `npm run build`.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/sensorstream/stream/StreamController.kt \
        android/app/src/main/java/com/sensorstream/stream/StreamEngine.kt \
        android/app/src/main/java/com/sensorstream/ui/StreamScreen.kt \
        laptop/webapp/src
git commit -m "feat: surface backfill health (served on phone; backfilled + permanent gaps on dashboard)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 7: End-to-end verification (adb, both codebases)

**Files:** none (verification).

- [ ] **Step 1** — Build + install the phone app (`assembleDebug` + `adb install -r`). Start the laptop server with recording on: `python -m sensorstream.app --record`. Stream 3–4 sensors from the phone on the same LAN.
- [ ] **Step 2 — induce UDP loss without dropping the WS**: the screen-off/throttle case. Lock the phone (or use the M36's OEM throttle) for ~20 s so the bounded UDP send-channel sheds samples while the WS heartbeat keeps the control channel alive. Alternatively, briefly block UDP 5005 at the firewall while leaving 8081 open.
- [ ] **Step 3 — confirm backfill ran**: laptop Diagnostics shows `Backfilled records` climbing and `Permanent gaps` = 0; phone status shows `backfill served N` > 0.
- [ ] **Step 4 — confirm gap-free recording**: stop recording; run a check that the session `.ssbin`, read via `recordings.query_signal` (which dedups+orders), has a **contiguous per-handle seq range with no missing seqs** over the loss window. Script:
  ```bash
  cd laptop && ./.venv/Scripts/python.exe - <<'PY'
  from sensorstream import recordings
  # pick the newest recording id from recordings.list_recordings('./recordings')
  # for each handle, query_signal and assert seq coverage is contiguous (raw == max_seq-min_seq+1)
  PY
  ```
- [ ] **Step 5 — confirm live latency unaffected**: compare live RTT/`network out` on the status card with and without an induced gap; backfill traffic on the WS must not change live UDP latency (spec §12).
- [ ] **Step 6** — record evidence in a `docs/superpowers/plans/2026-09-15-onphone-backfill-phase2b-verification.md`.

---

## Self-Review

**Spec coverage:** §6.1 gap tracker → Task 2; §6.2 resend requester + ACK → Task 5; §6.3 append + dedup-on-read → Task 3; §7 new WS messages → Task 1 (constants) + Tasks 4/5 (senders/handlers), with the documented base64-datagram deviation from the JSON-record shape; §8 reconnect/idempotency → GapTracker keyed by client_id (survives device_id rotation) + dedup; §8 unavailable → permanent gaps (Tasks 2/5); §10 diagnostics → Task 6; §11 tests → per-task pytest/JUnit + Task 7 e2e; §12 acceptance (gap-free after drop, live unaffected, permanent gaps counted) → Task 7. ✔

**Placeholder scan:** every code step carries real code. Task 6's webapp step is described at the component level (the dashboard reads existing broadcast fields) rather than pinned to exact JSX line numbers, because the Diagnostics component's current shape should be read at execution time — flagged here as the one place to read-before-edit, not a blind placeholder.

**Type consistency:** `client_id` string flows hello→PhoneSession→`phone_connected` event→`Reconciler.set_device_client`. `GapTracker` method names match between Task 2 def and Task 5 use (`observe`, `due_ranges`, `resolve`, `mark_permanent`, `contiguous_upto`, `stats`). `encode_frame_b64`/`decode_frame_b64` consistent across Tasks 1/4/5. `readRawRange(handle, fromSeq, toSeq)` matches the Phase 1 signature. Message constants identical in `protocol.py` and the Kotlin senders (string literals `"resend"`/`"backfill"`/… — the Kotlin side uses literals matching the constants).

**Known risk to validate during execution:** the base64 encoder choice on Android (`java.util.Base64` vs `android.util.Base64`) — Task 4 Step 3 spells out the resolution and Task 7 confirms on-device (API 34 devices).

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-09-15-onphone-backfill-phase2b-protocol-reconciliation.md`. Two execution options:

1. **Subagent-Driven (recommended by the skill)** — a fresh subagent per task with review between. NOTE: this session has repeatedly lost background-subagent completion notifications; if that persists, inline execution is the reliable fallback.
2. **Inline Execution** — execute tasks in this session with checkpoints (how Phase 2A was done successfully).

Recommended: **inline**, given the lost-notification history this session and that Phase 2A inline execution went cleanly.
