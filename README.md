# SensorStream — Real-Time Mobile Sensor Telemetry & Visualization

Turn an Android phone into a low-latency, multi-sensor telemetry device and stream its
sensors to a laptop — with live values, real-time graphs, and a 3D view of the phone's
orientation. Built as a proper telemetry system: correctness → low latency → high-rate
stability → synchronization → smooth visualization, with lossless recording and an
optional ROS 2 bridge.

> **No phone? No Android build tools? You can still try everything** — see
> [Try it without a phone](#try-it-without-a-phone-30-seconds). A prebuilt APK is also
> published under [Releases](../../releases) so you can test on a real device without
> building the app.

![License: MIT](https://img.shields.io/badge/license-MIT-blue)

## What's in the box

- **`android/`** — the phone app (Kotlin, Jetpack Compose). Enumerates sensors, streams
  them over UDP, control over WebSocket, with on-phone recording for screen-off integrity.
- **`laptop/`** — the receiver + browser dashboard (Python `asyncio` backend; React +
  Three.js + uPlot front-end in `laptop/webapp/`). Pluggable outputs via an `OutputSink`
  seam (`laptop/sensorstream/sinks.py`) — dashboard, lossless logger, and an optional
  **ROS 2** sink.
- **`laptop/tools/`** — `fake_phone.py` (a synthetic phone, no hardware needed) and
  `soak.py` (long-run stability test).
- **`docs/`** — protocol/schema, build & run, coordinate systems, the test plan, and
  troubleshooting.

## Features

| Area | What you get |
|---|---|
| Wire protocol | Compact little-endian binary over UDP; golden-vector-pinned codec (Python + Kotlin) |
| Receiver pipeline | Multi-sensor, per-sensor rate/loss metrics, latency without a clock handshake (min-filter), decode-error accounting |
| Reliability | On-phone bounded recording + laptop **backfill** (gap detect → resend → dedup) for lossless capture across Wi-Fi drops / screen-off |
| Dashboard | Live cards, real-time graphs (uPlot), 3D orientation (Three.js) with recenter/reset, recordings browser with server-side LOD, light/dark themes |
| Phone app | Categorized sensors, per-sensor detail + sampling-rate config, on-device pseudo-3D orientation, diagnostics, background streaming |
| Discovery | mDNS + UDP beacon so the phone can auto-find the laptop |
| ROS 2 | Optional `--ros` sink publishing `sensor_msgs/Imu`, `MagneticField`, `QuaternionStamped` |

## Try it without a phone (30 seconds)

The receiver ships with a synthetic mode and a synthetic phone, so you can exercise the
entire pipeline — receiver, sync, dashboard, recording, ROS — with zero hardware.

```bash
cd laptop
python -m venv .venv
source .venv/bin/activate          # Windows: .venv\Scripts\activate
pip install -r requirements.txt

# Build the dashboard once (or skip it — a no-build fallback dashboard is served if you don't).
cd webapp && npm install && npm run build && cd ..

# Option A — built-in synthetic stream:
python -m sensorstream.app --selftest
#   → open http://localhost:8080

# Option B — a synthetic "phone" that connects like the real app (shows a live, tumbling 3D):
python -m sensorstream.app          # terminal 1
python tools/fake_phone.py          # terminal 2  → open http://localhost:8080
```

`fake_phone.py` speaks the real control + telemetry protocol: it appears on the dashboard
as a connected device with named sensors, and streams physically-consistent
accelerometer / gyroscope / magnetometer / orientation data (the accelerometer is gravity
in the device frame, so `|a| ≈ 9.81` and the 3D view tumbles smoothly). Flags: `--host`,
`--ws-port`, `--hz`, `--duration`.

## Prerequisites

- **Python 3.11+** — laptop backend.
- **Node.js 18+ / npm** — to build the React dashboard (optional; a legacy static dashboard
  is served as a fallback if `laptop/webapp/dist/` isn't built).
- **Android Studio** — only if you want to **build** the phone app. To just test on a real
  phone, install the prebuilt APK from [Releases](../../releases) instead.

## Run — laptop dashboard

```bash
cd laptop
python -m venv .venv
source .venv/bin/activate           # Windows: .venv\Scripts\activate
pip install -r requirements.txt

cd webapp && npm install && npm run build && cd ..    # build the dashboard once

python -m sensorstream.app          # add --selftest to demo without a phone
#   → http://localhost:8080
```

### Dashboard UI development (hot reload)

```bash
cd laptop/webapp
npm run dev        # Vite dev server; keep the Python server running alongside for live data
```

### Server flags

| Flag | Default | Purpose |
|---|---|---|
| `--selftest` | off | Emit a synthetic 4-sensor stream (no phone needed) |
| `--selftest-hz` | `100` | Synthetic sample rate |
| `--ros` | off | Publish decoded sensors to ROS 2 topics (requires a sourced ROS 2 environment) |
| `--record` | off | Record the session from start (to `--log-dir`, default `./recordings`) |
| `--http-port` | `8080` | Dashboard HTTP port |
| `--ws-port` | `8081` | Control WebSocket port |
| `--udp-port` | `5005` | Telemetry UDP port |
| `--ui-hz` | `60` | Max per-sensor update rate pushed to the browser (raw logging stays full-rate) |
| `--no-discovery` | off | Disable mDNS + UDP beacon advertising |

## Run — Android app

**Easiest:** download the APK from [Releases](../../releases), enable
*Settings → Developer options → Install unknown apps* (or `adb install app.apk`), and open
**SensorStream**. No Android Studio needed.

**From source:**

1. Open the `android/` folder in Android Studio and let Gradle sync (uses JBR 21).
2. Run on a physical device with USB debugging enabled — see [`docs/run.md`](docs/run.md)
   for the ADB build/install commands.
3. In the app: open **Connection**, tap **Find Laptop Automatically** (or enter the
   laptop's LAN IP + control port `8081`), pick sensors on the **Sensors** tab, and
   **Connect & Stream**.

The phone and laptop must be on the **same Wi-Fi/LAN**. Streaming continues in the
background via a foreground service; tap its notification to return to the app.

## ROS 2 bridge (optional)

With a ROS 2 environment sourced, `--ros` publishes decoded sensors as standard messages:

| Topic | Message | Source sensor |
|---|---|---|
| `/phone/accelerometer` | `sensor_msgs/Imu` | accelerometer |
| `/phone/gyroscope` | `sensor_msgs/Imu` | gyroscope |
| `/phone/magnetic_field` | `sensor_msgs/MagneticField` | magnetometer |
| `/phone/orientation` | `geometry_msgs/QuaternionStamped` | rotation vector |

```bash
# with ROS 2 sourced:
python -m sensorstream.app --ros        # or add --selftest / use fake_phone.py
ros2 topic echo /phone/accelerometer
```

Without ROS sourced, `--ros` prints a warning and the server runs normally.

## Network priority on a busy Wi-Fi

On a shared network, bandwidth is arbitrated by the router/AP, not an app — an app can only
*hint*. SensorStream tags telemetry with **DSCP EF** and control with **DSCP AF41**, and
the receiver marks its socket to match; on APs that honor DSCP→WMM (most do) this gives the
stream preferential airtime under contention. It's best-effort — browsers can't be tagged,
so the dashboard's own traffic isn't prioritized. For a guaranteed result with many devices:
give phone + laptop their own link (phone hotspot, or a separate 5 GHz SSID), or set router
QoS on the laptop's IP / the ports (UDP 5005, WS 8081).

## Tests

```bash
# Python (from laptop/, venv active)
cd laptop && python -m pytest

# Android (from android/) — use Gradle, not the IDE JUnit runner
./gradlew test        # Windows: gradlew.bat test
```

**Soak test** — `python tools/soak.py --minutes 2` launches the server on isolated ports,
blasts a wire-identical multi-sensor load, samples memory/throughput/latency, and prints a
PASS/FAIL report (no phone needed). It fails on decode errors, permanent gaps, throughput
drift >10%, RSS growth like a leak, or a crash.

## Roadmap

- [x] Wire protocol + codecs, receiver pipeline, discovery, auto-reconnect
- [x] On-phone recording + laptop backfill (lossless across drops)
- [x] Dashboard (cards, graphs, 3D, recordings browser, light/dark)
- [x] Phone app redesign (Compose) with on-device 3D orientation
- [x] ROS 2 output sink (`--ros`)
- [ ] Verify screen-off streaming end-to-end against the redesigned dashboard UI
- [ ] Second-device / light-theme streaming pass on more hardware
- [ ] Recordings pagination once a session's list grows large
- [ ] Real-phone long-run soak (synthetic soak already passes)

## Contributing & reporting bugs

Contributions and bug reports are welcome. Please open an issue using the **Bug report**
template — include your OS, Python/Node versions, phone model + Android version (if used),
and steps to reproduce. If you can reproduce with `--selftest` or `tools/fake_phone.py`,
say so; that makes triage much faster. PRs should keep `python -m pytest` and
`./gradlew test` green.

## Reference

Wire format: [`docs/protocol.md`](docs/protocol.md) ·
Build: [`docs/build.md`](docs/build.md) ·
Run: [`docs/run.md`](docs/run.md) ·
Coordinate systems: [`docs/coordinate-systems.md`](docs/coordinate-systems.md) ·
Test plan: [`docs/test-plan.md`](docs/test-plan.md) ·
Dashboard performance (ADR-001): [`docs/adr-001-dashboard-performance.md`](docs/adr-001-dashboard-performance.md) ·
Troubleshooting: [`docs/troubleshooting.md`](docs/troubleshooting.md)

## License

[MIT](LICENSE) © 2026 Devam
