# Session handoff — Sensor Stream

Living status/handoff doc. Project: real-time phone→laptop multi-sensor telemetry +
visualization ("mobile_to_ROS_app"). Full plan/tests in `docs/test-plan.md`; wire format in
`docs/protocol.md`; coordinate math in `docs/coordinate-systems.md`.

## Where we are (2026-09-11)

| Phase | State |
|---|---|
| 1 — Accelerometer → UDP → live values | ✅ verified on S25 Ultra |
| 2 — Multi-sensor + WebSocket control channel + **foreground service** | ✅ built/verified (FGS: compiles+launches; background-survival = user to confirm) |
| 3 — 3D phone orientation (Three.js) | ✅ verified on device (rotates correctly) |
| 4 — Real-time rolling graphs (uPlot) | ✅ verified on device |
| 5 — Sync layer: latency (min-filter) + loss% + reorder | ✅ built + tested (dashboard shows it) |
| 6 — Logging (CSV+binary) + replay + Record button | ✅ built + tested end-to-end |
| 7 — Perf/latency instrumentation + soak | ⏳ TODO |
| 8 — Discovery (mDNS/beacon) + auto-reconnect | ⏳ TODO |

**Tests:** laptop `pytest` = **23 passing**; JS via `node --check` + `node webtests/orient.test.js`;
Android `gradlew :app:testDebugUnitTest` (codec golden vector) passes. Android app builds, installs,
launches clean on the S25 Ultra (Android 15).

## Environment / key facts (this Windows PC)
- **Android build:** `JAVA_HOME=C:\Users\devam\.jdks\jbr-21.0.11`; `adb` at
  `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`; SDK android-35; Gradle 8.9 wrapper (regenerated).
- **Laptop side:** Python venv at `laptop\.venv`; deps in `requirements.txt` (websockets, zeroconf,
  numpy, pytest). Runtime target OS = **Linux** (home Wi-Fi); code is cross-platform.
- **Phone:** Galaxy **S25 Ultra (SM-S938B)** verified; **M36 5G not yet tested**. Phone reaches this
  PC over Wi-Fi (IP like `10.11.146.38`, changes). App connects to **control port 8081**; UDP 5005 is
  negotiated automatically.
- **Ports:** dashboard HTTP `8080`, control+dashboard WS `8081`, telemetry UDP `5005`.

## Build & run commands
```bash
# Laptop tests (from laptop\, or with PYTHONPATH=laptop):
laptop\.venv\Scripts\python.exe -m pytest            # 23 tests
node --check laptop\web\*.js ; node laptop\webtests\orient.test.js

# Laptop app:
laptop\.venv\Scripts\python.exe -m sensorstream.app                 # normal
laptop\.venv\Scripts\python.exe -m sensorstream.app --selftest      # no phone (synthetic)
laptop\.venv\Scripts\python.exe -m sensorstream.app --record        # record from start
laptop\.venv\Scripts\python.exe -m sensorstream.replay recordings\<file>.ssbin

# Android (bash; set JAVA_HOME first):
JAVA_HOME=C:/Users/devam/.jdks/jbr-21.0.11 \
  cmd //c "C:\mobile_to_ROS_app\android\gradlew.bat -p C:\mobile_to_ROS_app\android :app:assembleDebug --offline --console=plain"
<adb> install -r android\app\build\outputs\apk\debug\app-debug.apk
<adb> shell am start -n com.sensorstream/.MainActivity
```
Dashboard: <http://localhost:8080> (hard-refresh `Ctrl+Shift+R` after web edits — static served live).

## Immediate: verify the foreground service (on device)
1. Start the laptop receiver; on the phone enter Laptop IP + **Ctrl port 8081**, select sensors, tap
   **Connect & Stream** (grant the notification permission prompt).
2. A persistent "Streaming sensors" notification should appear.
3. **Press Home / turn the screen off** → the dashboard should keep receiving data (streaming survives
   backgrounding). Tap **Stop** to end; notification clears.

## Next session TODO (priority order)
1. **Confirm FGS background streaming** on the S25 Ultra (above); also try screen-off + a few minutes.
2. **Phase 8 — Discovery + reconnect** (removes manual IP): laptop advertises via `zeroconf` (mDNS)
   + a periodic UDP broadcast beacon; Android discovers via `NsdManager` + a beacon listener, with a
   manual-IP fallback (+ optional QR). Auto-reconnect in `WsControlClient` (backoff on close/failure).
   Needs `CHANGE_WIFI_MULTICAST_STATE` + a `MulticastLock`.
3. **Phase 7 — Perf/latency**: wire the stage timestamps already in the protocol (`t_acquire_ns`,
   `t_serialize_ns`, FLAG_STAGE_TS) end-to-end; a benchmark script (p50/p95/p99); 60-min soak on the
   phone (rate stability, flat memory, no leaks); then ByteBuffer pooling + datagram coalescing.
4. **Error handling (§26)** hardening: sensor unavailable, socket failure, Wi-Fi switch, unsupported-rate
   clamp messaging; graceful reconnect UI.
5. **M36 5G validation**: enumerate its sensors (record catalog), run coord tests 1–4.
6. **Developer/Debug panel (§29)** on the phone: requested vs actual rate, samples tx, queue depth
   (phone already shows RTT + sent/dropped; add the rest).
7. Deferred by design: iOS client, **ROS2 bridge** (add `Ros2Sink` behind the existing `OutputSink`
   seam in `laptop/sensorstream/sinks.py`), Protobuf option.

## Gotchas learned (don't rediscover)
- Android unit tests: run via **Gradle**, not the IntelliJ runner (NoClassDefFoundError for main classes).
- Windows console is **cp1252** — keep `app.py` banner ASCII-only (box-drawing chars crash it).
- **uPlot**: `opts.series` MUST be set or no lines draw (only axes).
- WebGL/uPlot canvases: constrain with `renderer.setSize(w,h)` + `position:absolute` + grid `min-width:0`,
  else the page gets infinite horizontal scroll.
- OkHttp WebSocket URL must be `http://` (not `ws://`) — it upgrades itself.
- FGS on Android 14/15: needs `foregroundServiceType="dataSync"` + `FOREGROUND_SERVICE_DATA_SYNC`;
  can only be started from the app's **foreground** (adb `shell` start is denied — that's expected).
- `ActivityResultContracts` is in package `androidx.activity.result.contract` (singular).
