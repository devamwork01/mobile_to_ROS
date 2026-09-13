# Sensor Stream — Real-Time Mobile Sensor Telemetry & Visualization

Turn an Android phone into a low-latency multi-sensor telemetry device and stream its
sensors to a laptop, with live values, real-time graphs, and a 3D view of the phone's
orientation. Built as a telemetry system: correctness → low latency → high-rate stability
→ synchronization → smooth visualization.

- **`android/`** — the phone app (Kotlin, Jetpack Compose). Enumerates sensors, streams
  them over UDP, control over WebSocket.
- **`laptop/`** — the receiver + browser dashboard (Python asyncio backend; React +
  Three.js + uPlot front-end in `laptop/webapp/`). ROS-ready via a pluggable `OutputSink`
  (`laptop/sensorstream/sinks.py`).
- **`docs/`** — protocol/schema, build & run, coordinate systems, the side-by-side test
  plan, troubleshooting.

## Status

| Component | State |
|---|---|
| Wire protocol + Python codec | ✅ implemented, golden-vector pinned, unit tests passing |
| Laptop receiver + async pipeline (Phases 1–8) | ✅ multi-sensor, sync, logging/replay, discovery |
| Android app | ✅ enumeration, multi-sensor streaming, discovery, auto-reconnect, screen-off power fix — **verified on Galaxy S25 Ultra** |
| Dashboard redesign — "SensorStream Pro" (React) | ✅ L1/L2: design system, trimmed nav, live cards, real-time graphs, sensor details modal |
| Premium 3D device view | ✅ rounded titanium + glass phone, image-based lighting, contact shadow (Three.js) |

The redesigned dashboard has been verified end-to-end with the built-in `--selftest`
synthetic stream. **Re-verification against a real phone, the light theme, and the on-phone
3D/redesign are still pending — see [Pending / not yet verified](#pending--not-yet-verified).**

## Prerequisites

- **Python 3.11+** (laptop backend)
- **Node.js 18+ / npm** (to build the React dashboard)
- **Android Studio** (to build/run the phone app) — the phone and laptop must be on the same Wi-Fi/LAN.

## Setup & run — laptop dashboard

```bash
# 1. Python backend
cd laptop
python -m venv .venv
# Windows (PowerShell):  .venv\Scripts\Activate.ps1
# Windows (Git Bash):    source .venv/Scripts/activate
# Linux / macOS:         source .venv/bin/activate
pip install -r requirements.txt

# 2. Build the React dashboard (dist/ is git-ignored, so this must run at least once).
#    The server serves laptop/webapp/dist/ when present, else falls back to legacy laptop/web/.
cd webapp
npm install
npm run build
cd ..

# 3. Run with synthetic data (no phone needed) and open the dashboard
python -m sensorstream.app --selftest
#   → http://localhost:8080
```

To stream from a real phone, run `python -m sensorstream.app` (no `--selftest`) and connect
from the app (see below).

### Dashboard UI development (hot reload)

```bash
cd laptop/webapp
npm run dev        # Vite dev server for fast iteration
```

Keep the Python server running alongside it for live WebSocket data. For a production build,
re-run `npm run build` so the Python server picks up the new `dist/`.

### Useful server flags

| Flag | Default | Purpose |
|---|---|---|
| `--selftest` | off | Emit a synthetic 4-sensor stream (no phone needed) |
| `--selftest-hz` | `100` | Synthetic sample rate |
| `--record` | off | Record the session from start (to `--log-dir`, default `./recordings`) |
| `--http-port` | `8080` | Dashboard HTTP port |
| `--ws-port` | `8081` | Control WebSocket port |
| `--udp-port` | `5005` | Telemetry UDP port |
| `--ui-hz` | `30` | Max per-sensor update rate pushed to the browser |
| `--no-discovery` | off | Disable mDNS + UDP beacon advertising |

## Setup & run — Android app

1. Install Android Studio (`winget install --id Google.AndroidStudio -e`), open the
   `android/` folder, and let Gradle sync.
2. Run on a physical device (USB debugging enabled).
3. In the app: use auto-discovery, or enter the laptop's LAN IP + port `5005`, pick sensors,
   and **Start**.

Full instructions: [`docs/build.md`](docs/build.md) and [`docs/run.md`](docs/run.md).

## Running the tests

```bash
# Python (from laptop/, venv active)
cd laptop
python -m pytest

# Android (from android/) — use Gradle, not the IDE JUnit runner
./gradlew test        # Windows: gradlew.bat test
```

## Pending / not yet verified

Tracked so the "test-as-you-build" checks don't get lost. Nothing below is confirmed on real
hardware with the redesigned UI yet.

### A. Redesigned dashboard on a real phone (Galaxy S25 Ultra)
- [ ] Device connects → header/nav show real model, Android version, IP
- [ ] Multi-sensor stream → every sensor selected on the phone shows a live card
- [ ] 3D orientation tracks real motion — verify `androidToThree` mapping (body axes vs ENU world)
- [ ] Graphs plot real sensor data at real rates without stalling
- [ ] Sensor-details modal **Hardware** section populates from the real phone catalog
      (vendor / resolution / max range / power / max rate / wake-up)
- [ ] Humanized names resolve for all sensor types the S25 reports (unmapped types fall back gracefully)
- [ ] Record start/stop writes rows; Diagnostics counters (latency p50/p95, jitter, phone-latency,
      loss, throughput) read sane
- [ ] Reconnect after Wi-Fi drop; screen-off streaming (power fix) still works with the new UI

### B. Not-yet-built UI (tests follow the build)
- [ ] Light theme toggle
- [ ] Responsive / phone-width layout (~400px)
- [ ] Recordings browser + Settings views (currently placeholders)

### C. Premium 3D
- [x] Rounded/metallic/glass phone renders in both frames; toggles work; no perf regression;
      WebGL cleanup on unmount — verified via `--selftest`

### D. Deferred system items
- [ ] Galaxy M36 5G validation
- [ ] Live sensor reconfig (add/remove sensors without restart; dashboard plots update dynamically)
- [ ] ROS2 bridge (`Ros2Sink` behind the `OutputSink` seam)
- [ ] Soak test (long-run stability)

## Reference

Wire format: [`docs/protocol.md`](docs/protocol.md) ·
Coordinate systems: [`docs/coordinate-systems.md`](docs/coordinate-systems.md) ·
Test plan: [`docs/test-plan.md`](docs/test-plan.md) ·
Troubleshooting: [`docs/troubleshooting.md`](docs/troubleshooting.md)
