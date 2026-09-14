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
| `--ui-hz` | `60` | Max per-sensor update rate pushed to the browser (raw logging always full-rate) |
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

Tracked so the "test-as-you-build" checks don't get lost.

### A. Redesigned dashboard on a real phone (Galaxy S25 Ultra) — verified
- [x] Device connects → header/nav show real model, Android version, IP (incl. reload-after-connect via snapshot)
- [x] Multi-sensor stream → every sensor selected on the phone shows a live card
- [x] 3D orientation tracks real motion — `androidToThree` mapping confirmed (body axes vs ENU world)
- [x] Graphs plot real sensor data at real rates without stalling
- [x] Sensor-details modal **Hardware** section populates from the real phone catalog
      (vendor / resolution / max range / power / max rate / wake-up)
- [x] Humanized names resolve for the sensor types the S25 reports (unmapped types fall back gracefully)
- [x] Record start/stop writes rows; Diagnostics counters (latency p50/p95, jitter, phone-latency,
      loss, throughput) read sane
- [x] Reconnect after Wi-Fi drop / server restart
- [ ] Screen-off streaming (power fix) still works with the new UI (A8 — not yet verified)

### B. Not-yet-built UI (tests follow the build)
- [ ] Light theme toggle
- [ ] Responsive / phone-width layout (~400px)
- [ ] Recordings browser + Settings views (currently placeholders)

### C. Premium 3D
- [x] Rounded/metallic/glass phone renders in both frames; toggles work; no perf regression;
      WebGL cleanup on unmount — verified via `--selftest`

### D. System items
- [x] Live sensor reconfig — toggle a sensor on the phone while streaming adds/removes its
      dashboard card + graph tab live, no reconnect (phone `updateSelections` + `active` control msg)
- [~] Galaxy M36 5G validation — app installs and enumerates 34 sensors; **known issue:** after
      ~2–3 min the app grows laggy (streaming and UI interactions); S25 Ultra unaffected. Under investigation.
- [ ] ROS2 bridge (`Ros2Sink` behind the `OutputSink` seam)
- [ ] Soak test (long-run stability)

### E. Dashboard performance
- [x] 3D canvas lag on large screens / many sensors — layout was stretching the canvas to
      ~3.7 MP; bounded panel + capped drawing buffer (~0.52 MP) + 30 fps throttle + lighter shadows
- [x] Scroll-freeze in the live sensor panel — throttle per-card re-renders to ~10 Hz +
      `content-visibility:auto` so off-screen cards skip paint
- [x] Initial load — code-split three.js/uPlot (initial JS 716 KB → 174 KB)
- [x] Visualization scheduler (`renderBudget.js`) — per-viz frame budget with a cheap shared
      scroll signal. Validated at **constant 60 fps** (3D + plots) — scrolls smoothly with no
      degradation on the test hardware; the degrade-during-scroll path stays one line away for
      weaker GPUs. Off-screen pause; dev HUD via `?perf`. (`max_ui_hz` default raised 30 → 60.)
- [~] **Performance-architecture roadmap** — decided (thin browser + server-side LOD; no
      speculative workers). Design: [`docs/adr-001-dashboard-performance.md`](docs/adr-001-dashboard-performance.md),
      directions: [`High-Frequency Sensor Streaming — Performance Architecture Directions.md`](High-Frequency%20Sensor%20Streaming%20—%20Performance%20Architecture%20Directions.md)
    - [x] Recordings list API + view (server-side listing; `.meta.json` written on record start)
    - [x] Server-side LOD range/aggregation queries (min/max/first/last/avg buckets) + history chart
    - [ ] Zoom→resolution: re-query the server for higher-res on zoom (native visual zoom works now)
    - [ ] Recordings pagination (cursor) when the list grows; delivery-semantics/health metrics in Diagnostics

## Reference

Wire format: [`docs/protocol.md`](docs/protocol.md) ·
Coordinate systems: [`docs/coordinate-systems.md`](docs/coordinate-systems.md) ·
Test plan: [`docs/test-plan.md`](docs/test-plan.md) ·
Troubleshooting: [`docs/troubleshooting.md`](docs/troubleshooting.md)
