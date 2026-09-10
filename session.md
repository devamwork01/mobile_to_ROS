# Session handoff — Sensor Stream

Living status/handoff doc. Project: real-time phone→laptop multi-sensor telemetry +
visualization ("mobile_to_ROS_app"). Repo: **github.com/devamwork01/mobile_to_ROS** (branch `main`).
Plan/tests: `docs/test-plan.md`; wire format: `docs/protocol.md`; coordinate math: `docs/coordinate-systems.md`.

## Where we are

| Phase | State |
|---|---|
| 1 — Accelerometer → UDP → live values | ✅ verified on S25 Ultra |
| 2 — Multi-sensor + WebSocket control channel + foreground service | ✅ verified on device (incl. **background + screen-off** streaming) |
| 3 — 3D phone orientation (Three.js) | ✅ verified on device |
| 4 — Real-time rolling graphs (uPlot) | ✅ verified on device |
| 5 — Sync: latency (min-filter) + loss% + reorder | ✅ built + tested; shown on dashboard |
| 6 — Logging (CSV+binary) + replay + Record button | ✅ built + tested end-to-end |
| 7 — Perf/latency instrumentation + soak | ⏳ **TODO (next)** |
| 8 — Discovery (mDNS + UDP beacon) + auto-reconnect | ✅ laptop verified + tested; Android compiles (**on-device pending**) |

**Tests:** laptop `pytest` = **26 passing**; JS `node --check` + `node webtests/orient.test.js`;
Android `gradlew :app:testDebugUnitTest` (codec golden) passes; app builds/installs/launches clean.

## Immediate: verify Phase 8 on device (phone was unplugged at session end)
1. Reconnect the phone; reinstall: `gradlew :app:assembleDebug` then `adb install -r` the debug APK.
2. Start the laptop receiver (`python -m sensorstream.app`) on the same Wi-Fi.
3. On the phone tap **"Find laptop automatically"** → IP + control port should auto-fill (via mDNS or
   the UDP beacon). Then **Connect & Stream**.
4. **Reconnect test:** while streaming, Ctrl+C the laptop app and restart it → the phone should
   reconnect on its own (backoff 0.5–8 s) and resume streaming.

## Environment / key facts (this Windows PC)
- Android build: `JAVA_HOME=C:\Users\devam\.jdks\jbr-21.0.11`; `adb` at
  `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`; SDK android-35; Gradle 8.9 wrapper.
- Laptop: Python venv `laptop\.venv`; deps `requirements.txt`. Runtime target OS = Linux (home Wi-Fi).
- Phone: **S25 Ultra (SM-S938B)** verified; **M36 5G not yet tested**. App connects to **control port
  8081**; UDP 5005 auto-negotiated. Discovery beacon port **5006**.
- Git: pushed over SSH (ed25519 key at `~/.ssh/id_ed25519`, no passphrase) — `git push` just works.
  `laptop.7z` (18 MB backup at repo root) is gitignored.

## Build & run
```bash
# Laptop tests + app
laptop\.venv\Scripts\python.exe -m pytest                       # 26 tests
laptop\.venv\Scripts\python.exe -m sensorstream.app             # normal (mDNS+beacon on)
laptop\.venv\Scripts\python.exe -m sensorstream.app --selftest  # no phone
laptop\.venv\Scripts\python.exe -m sensorstream.app --record    # record from start
laptop\.venv\Scripts\python.exe -m sensorstream.replay recordings\<file>.ssbin
node --check laptop\web\*.js ; node laptop\webtests\orient.test.js
# Android (bash; set JAVA_HOME)
JAVA_HOME=C:/Users/devam/.jdks/jbr-21.0.11 cmd //c "C:\mobile_to_ROS_app\android\gradlew.bat -p C:\mobile_to_ROS_app\android :app:assembleDebug --offline --console=plain"
<adb> install -r android\app\build\outputs\apk\debug\app-debug.apk
```
Dashboard: <http://localhost:8080> (hard-refresh `Ctrl+Shift+R` after web edits — served live).

## Next TODO (priority)
1. **Verify Phase 8** on device (discovery auto-fill + reconnect — steps above).
2. **Phase 7 — Perf/latency**: wire the protocol's stage timestamps (`t_acquire_ns`, `t_serialize_ns`,
   `FLAG_STAGE_TS`) end-to-end; benchmark p50/p95/p99; 60-min soak on the phone (rate/memory/leaks);
   then ByteBuffer pooling + datagram coalescing.
3. **Error handling (§26)** hardening; **M36 5G** validation (enumerate + coord tests 1–4).
4. Developer/Debug panel (§29) on the phone (requested-vs-actual rate, queue depth).
5. Deferred by design: iOS client, **ROS2 bridge** (`Ros2Sink` behind the `OutputSink` seam in
   `laptop/sensorstream/sinks.py`), Protobuf option.

## Gotchas (don't rediscover)
- Android unit tests run via **Gradle**, not the IntelliJ runner (NoClassDefFoundError for main classes).
- Windows console is **cp1252** — keep `app.py` output ASCII-only.
- **uPlot**: `opts.series` MUST be set or no lines draw.
- WebGL/uPlot canvases: `renderer.setSize(w,h)` + `position:absolute` + grid `min-width:0`, or the page
  gets infinite horizontal scroll.
- OkHttp WebSocket URL must be `http://` (it upgrades itself).
- FGS (Android 14/15): `foregroundServiceType="dataSync"` + `FOREGROUND_SERVICE_DATA_SYNC`; can only be
  started from the app's foreground (adb `shell` start is denied — expected).
- `ActivityResultContracts` is in `androidx.activity.result.contract` (singular).
- Discovery: NsdManager + UDP beacon (port 5006); needs `CHANGE_WIFI_MULTICAST_STATE` + a MulticastLock.
