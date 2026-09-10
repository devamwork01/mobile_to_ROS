# Side-by-side test plan ("test as you build")

Every phase pairs a **build** with tests run **before** moving on: automated (JVM/pytest),
on-device, and physical-motion checks. Devices under test: **Galaxy S25 Ultra** (barometer + rich
fused sensors) and **Galaxy M36 5G** (fewer sensors — verified by dynamic enumeration, never
assumed). Legend: ✅ done & verified here · 🔨 source ready, verify on device · ⏳ upcoming.

## Automated tests available now
```bash
# Laptop (from laptop/, venv active):
python -m pytest                              # 14 passing: protocol, receiver, pipeline
# Android (from android/):
gradlew.bat :app:testDebugUnitTest            # codec golden vector == golden_packet.bin
```

## Phase 0 — Environment ✅
- ✅ Python venv builds, `pytest` green. ✅ `app --selftest` serves dashboard end-to-end.
- ✅ Android Studio + SDK (JBR-21, android-35) installed; Gradle wrapper regenerated;
  `adb` lists the S25 Ultra (SM-S938B); `assembleDebug` + `testDebugUnitTest` pass.

## Phase 1 — Accelerometer → UDP → laptop live values
**Build:** enumerate + register accelerometer; binary codec; UDP sender; manual IP; Python
receiver + dashboard numeric values + Hz.
**Tests:**
- ✅ Codec round-trip + edge cases (truncation, bad magic, trailing bytes) — `test_protocol.py`.
- ✅ Cross-language golden vector (Python side); 🔨 Kotlin `BinaryPacketCodecTest` (run in Studio).
- ✅ UDP loopback + full UDP→sink→WebSocket→client + static page — `test_receiver.py`, `test_dashboard.py`.
- ✅ Kotlin `BinaryPacketCodecTest` passes via Gradle (byte-identical to `golden_packet.bin`).
- ✅ **On device (S25 Ultra, SM-S938B):** installs/launches; accelerometer streams to the dashboard,
  live values. **Coord Test 1 (flat table): `Az ≈ +9.81`** confirmed. (M36 still to run.)

## Phase 2 — Gyro/mag/rotation-vector/gravity/linear-accel · multi-stream · control channel ⏳
Full dynamic enumeration + metadata; per-sensor enable + rate presets (10/50/100/200/Max);
WebSocket control (hello/catalog/configure/heartbeat/stats); foreground service; ≥5 simultaneous
streams via one bounded channel.
- Instrumented enumeration on both phones; **record each device's catalog as a baseline**.
- Preset→period clamps to sensor min delay (never promise an impossible rate).
- **Coord Test 3 (gyro):** rotate about each axis → matching ωx/ωy/ωz, correct right-hand sign.
- **Coord Test 4 (mag):** rotate in Earth field → coherent change, `|B|` ≈ 25–65 µT.

## Phase 3 — 3D phone visualization ⏳
Three.js phone + labeled device axes (X right, Y top, Z out) + world ENU axes + sensor vector;
orientation from rotation-vector quaternion.
- **Coord Test 2:** flat → flat; rotate 90° about each axis → viz turns same axis/direction (no
  swap/mirror); yaw tracks heading; no gimbal flips. Mapping unit-tested (`docs/coordinate-systems.md`).

## Phase 4 — Real-time graphs ⏳
uPlot rolling plots (X/Y/Z + magnitude), bounded ring buffers, windows 1/5/10/30/60 s.
- Memory flat over 10 min at 100 Hz × 3; ring buffer never exceeds N; window switch correct.

## Phase 5 — Timestamp synchronization ⏳
Reorder by `t_sensor_ns`; per-sensor loss; NTP-like clock offset → latency; multi-rate align/interp.
- Synthetic 100/50/20 Hz with injected reorder+drops → exact loss count, correct order, timestamps
  preserved; offset estimator converges under asymmetric delay.

## Phase 6 — Logging & replay ⏳
CSV + binary sinks, start/stop independent of viz; `replay.py`.
- Binary record→read lossless; logging doesn't drop telemetry; replay reproduces 3D + graphs.

## Phase 7 — Optimize latency/throughput/memory ⏳
ByteBuffer pooling; datagram coalescing; bounded queues; stage-timestamp instrumentation.
- Benchmark p50/p95/p99 (sensor→net, net→laptop, laptop→render, total) on home Wi-Fi; 60-min soak
  at 100 Hz × 5 on both phones → stable rate, flat memory, no leaks.

## Phase 8 — Discovery, reconnection, error handling ⏳
mDNS + UDP beacon + QR + manual (MulticastLock); heartbeat/timeout/auto-reconnect; all section-26
error cases; connection monitor (latency, loss%, RX pps, data rate — measured).

## Coordinate-System Validation Suite (run on both phones)
In-app Calibration mode + dashboard expected-vs-actual table: Test 1 flat; Test 2 rotate 90°/axis;
Test 3 per-axis gyro; Test 4 magnetometer vs Earth field. Documented conventions are the source of
truth — see `docs/coordinate-systems.md` (added in Phase 3).
