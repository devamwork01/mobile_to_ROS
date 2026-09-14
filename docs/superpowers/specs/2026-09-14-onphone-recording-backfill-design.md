# On-phone lossless recording + laptop backfill — design spec

Status: **Design approved** (2026-09-14) · Next: implementation plan (writing-plans).
Related: [`docs/adr-001-dashboard-performance.md`](../../adr-001-dashboard-performance.md),
[`../../../android_sensor_screen_off_architecture.md`](../../../android_sensor_screen_off_architecture.md).

## 1. Objective

Guarantee **lossless 100 Hz sensor capture** during screen-off / backgrounded operation, and
make the **laptop the authoritative (gap-free) recording** — without adding latency to the live
stream.

Explicit non-goal: low-latency **live** delivery while the M36 is locked. We proved empirically
this session that the M36 throttles a `dataSync` foreground app to ~150 ms bursty delivery when
locked, *despite* a held `PARTIAL_WAKE_LOCK` + `FULL_LOW_LATENCY` WifiLock + battery-optimization
exemption. That is an OEM limit; the mediaPlayback/location workarounds are rejected (see §9).
So live latency stays best-effort; **completeness** is guaranteed via backfill.

## 2. Context — what already exists

- Foreground service (`StreamingService`, type `dataSync`) owns the pipeline; acquisition
  (`StreamEngine`/`StreamController`/`SensorEventSource`) survives the Activity via `StreamHolder`.
- `PARTIAL_WAKE_LOCK` + `FULL_LOW_LATENCY` WifiLock held while streaming; battery-opt exempt.
- Live path: `SensorEventSource.onSample` → bounded `Channel(4096, DROP_OLDEST)` → UDP.
- Every record carries a per-sensor `seq` and the phone's original `t_sensor_ns`.
- Laptop: `DashboardSink` already tracks per-`(type,handle)` seq and detects gaps/loss;
  `Recorder` writes the live stream to `.ssbin` (+ `.csv`); `recordings.py` reads `.ssbin`
  and buckets by `t_sensor_ns`.
- Android app already has `BinaryPacketCodec` (same wire format as the laptop).

**Gaps this spec closes:** no on-phone recording; the bounded buffer drops silently; nothing
reconciles the laptop's copy after loss; on-device diagnostics are partial.

## 3. Key decisions (from brainstorming)

1. **Goal:** lossless data integrity screen-off; laptop authoritative; live path untouched.
2. **Backfill = Approach A** — laptop detects gaps and requests seq ranges over the reliable
   WS control channel; phone serves them from its on-phone `.ssbin`; laptop merges by
   `(handle, seq)` + timestamp.
3. **Timestamp-based:** merge/dedup on `(handle, seq)`, order/placement by `t_sensor_ns`.
4. **Phone copy is transient insurance:** pruned as the laptop ACKs receipt; bounded ring
   otherwise.
5. **Storage cap = size OR time, whichever first** → prune oldest; configurable; default
   **150 MB / 20 min**; over-cap pruning of un-ACKed data becomes an observable permanent gap.
6. **Out of scope (YAGNI):** Mode C deep-sleep batched recording; `mediaPlayback`/`location`
   FGS type changes; low-latency live under M36-lock.

## 4. Architecture

```
                          ┌─► Live UDP sender (bounded, best-effort) ─► laptop live view (fast)
Phone: SensorEventSource ─┤
   onSample (lightweight)  └─► LocalRecorder (lossless .ssbin ring, every sample)
                                        ▲                       │ serves requested seq ranges
                                        └──── backfill ◄────────┘  over reliable WS control channel
Laptop: live receiver ─► authoritative recording  ◄─ merges live + backfill by (handle,seq)+timestamp
        gap tracker  ─────────── resend requests / ACKs ──────────►  phone
```

The live path is unchanged (no added latency). The laptop recording is the truth, made gap-free
by requesting only what the live path missed. Telemetry **wire format and golden-packet codec are
unchanged**; backfill adds control-channel JSON messages only.

## 5. Phone components

### 5.1 Acquisition fan-out
`SensorEventSource.onSample` publishes each sample to **two** consumers, both via non-blocking
`trySend` so the sensor callback stays lightweight:
- the existing network `Channel` (DROP_OLDEST, best-effort) — unchanged;
- a new **`LocalRecorder`** input channel (lossless). Its channel is generously sized; an IO
  coroutine drains it to disk. A `trySend` failure here (disk can't keep up — not expected at
  ~36 KB/s) increments `recorderDropped` (observable, should stay 0).

### 5.2 LocalRecorder (bounded `.ssbin` ring)
- Writes records as `.ssbin` frames using `BinaryPacketCodec` (byte-compatible with the laptop),
  into **time-segmented files** (e.g. 10 s per segment) under app-private storage.
- **Seq index:** maintain an in-memory (segment → per-handle min/max seq, offsets) index so a
  resend request can locate records by `(handle, seq range)` without scanning everything.
- **Retention/prune (whichever triggers first):**
  1. total size > `maxBytes` (default 150 MB), or
  2. oldest segment age > `maxMinutes` (default 20 min), or
  3. segment fully ACKed by the laptop (all its records' seqs ≤ the laptop's contiguous ACK).
  Prune deletes oldest whole segments. Pruning **un-ACKed** data (cases 1/2) increments
  `droppedOldestRecords` — this is the permanent-gap signal.
- Config in `SharedPreferences` (defaults above); a minimal Settings control can expose it
  (phone Settings UI is otherwise deferred).

### 5.3 Backfill responder
`WsControlClient` handles a `resend` message → `LocalRecorder` reads the requested
`(handle, from_seq..to_seq)` records → sends them back as `backfill` messages, **chunked** (bounded
batch size, e.g. ≤512 records/msg) to avoid flooding the WS. Ranges no longer in the ring →
`backfill_unavailable`.

## 6. Laptop components

### 6.1 Gap tracker
Extend the existing per-`(device,handle)` seq tracking (in `DashboardSink` or a dedicated tracker
fed by the receiver) to record **missing seq ranges**. After a short **grace window** (default
750 ms — long enough that a merely-late live packet isn't treated as a gap) still-missing ranges
are queued for a `resend` request over the control channel.

### 6.2 Resend requester + ACKs
- Sends `resend` requests (coalesced ranges, capped per message) over the WS to the phone.
- On receiving `backfill` records, writes them to the authoritative recording (§6.3) and updates
  the highest **contiguous** seq per handle; periodically sends `backfill_ack` so the phone can
  prune.
- On `backfill_unavailable`, marks those ranges as **permanent gaps** (counted, surfaced in
  diagnostics) and stops requesting them.

### 6.3 Authoritative recording — append + sort/dedup on read
- Both live and backfilled records are **appended** to the same session `.ssbin` (out-of-order is
  fine). No in-place insertion.
- `recordings.py` already reads all frames and buckets by `t_sensor_ns`; add **dedup by
  `(handle, seq)`** while reading so duplicates (a sample arriving both late-live and via backfill)
  collapse. Result: a coherent, gap-free, correctly-ordered timeline — the "timestamp-based
  recording."

## 7. Backfill protocol (new WS control messages)

All JSON over the existing reliable control channel (path `/phone`). Telemetry stays on UDP.

- Laptop → phone: `{"type":"resend","device_id":D,"ranges":[{"handle":H,"from":S0,"to":S1}, …]}`
- Phone → laptop: `{"type":"backfill","device_id":D,"records":[{"handle":H,"seq":S,"type":T,"t":Tns,"acc":A,"v":[…]}, …]}` (record shape mirrors the live `data` message; chunked)
- Phone → laptop: `{"type":"backfill_unavailable","device_id":D,"ranges":[{"handle":H,"from":S0,"to":S1}, …]}`
- Laptop → phone: `{"type":"backfill_ack","device_id":D,"upto":[{"handle":H,"seq":S}, …]}`

Constants (`protocol.py` + Kotlin): `MSG_RESEND`, `MSG_BACKFILL`, `MSG_BACKFILL_UNAVAILABLE`,
`MSG_BACKFILL_ACK`.

## 8. Error handling & edge cases

- **Reconnect mid-backfill:** control WS reconnects → laptop re-derives gaps from its seq state
  and re-requests; idempotent (dedup by `(handle,seq)`).
- **Long offline > cap:** ring prunes un-ACKed oldest → those ranges return `backfill_unavailable`
  → laptop records a permanent gap (never silent).
- **Huge gap after a long dropout:** backfill is chunked + flow-controlled so it doesn't starve
  the live stream or the WS; live UDP is independent and unaffected.
- **Disk full / write error:** LocalRecorder counts `recorderWriteErrors`; pruning frees space;
  surfaced in diagnostics.
- **Duplicate/reordered delivery:** resolved by dedup-on-read `(handle,seq)`.
- **Laptop entirely offline (no session to backfill into):** the phone ring buffers within the
  cap and backfills once a laptop session exists; data older than the cap when the laptop finally
  connects is a permanent gap. The laptop is authoritative only while it is running — the phone
  ring is the bounded fallback, not a permanent archive.

## 9. What we are NOT doing

- ❌ `location`/`mediaPlayback` FGS type changes or silent-audio keepalive to fight the M36 throttle.
- ❌ Chasing low-latency **live** delivery under M36-lock (OEM limit).
- ❌ Mode C deep-sleep hardware-batched recording (separate future feature).
- ❌ Unbounded phone storage; unbounded queues; network I/O in the sensor callback.
- ❌ Changing the telemetry binary wire format or the golden-packet codec.

## 10. Diagnostics (doc §8, scoped)

- **Phone:** acquisition rate, recorded count, ring size + oldest age, `droppedOldestRecords`,
  `recorderDropped`/`recorderWriteErrors`, network sent/dropped, backfill records served,
  FGS/wakelock/screen state.
- **Laptop:** received, live-detected gaps, backfilled count, remaining **permanent** gaps.
  (Extends the existing Diagnostics "Presentation" + "Raw pipeline" panels.)

## 11. Testing

- **Laptop (pytest):** gap tracker (range detection, grace window); resend coalescing;
  merge/dedup on read (duplicates + out-of-order → gap-free, ordered); `backfill_unavailable`
  → permanent-gap accounting.
- **Phone (unit):** ring segment prune by size, by age, and by ACK; seq-range lookup returns the
  right records; `unavailable` for pruned ranges.
- **End-to-end (adb, both devices):** Test 5 — stream, drop Wi-Fi, reconnect → the laptop
  recording is **gap-free** afterward; verify the ring prunes at the cap and marks permanent gaps
  when offline beyond it. Confirm the live UDP path's latency is unchanged by backfill activity.

## 12. Acceptance criteria

- [ ] Every acquired sample is written to the on-phone `.ssbin` (acquisition loss = 0 barring
      disk failure), independent of network state.
- [ ] After a Wi-Fi drop+reconnect, the laptop's session recording is **gap-free** (verified by
      contiguous per-handle seqs after merge).
- [ ] Live UDP latency/throughput is unaffected by backfill (measured with/without an induced gap).
- [ ] Phone storage never exceeds the size cap or the age cap; over-cap pruning is counted and
      the corresponding laptop gap is marked permanent (not silent).
- [ ] Phone copy is pruned as the laptop ACKs, so steady-state on-phone storage stays small.
- [ ] Diagnostics distinguish acquisition loss vs network loss vs permanent (un-backfillable) gaps.

## 13. Config defaults (adjust in review)

- `maxBytes` = 150 MB · `maxMinutes` = 20 min · segment = 10 s · grace window = 750 ms ·
  backfill batch ≤ 512 records/msg.
