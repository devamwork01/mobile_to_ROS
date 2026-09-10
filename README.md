# Sensor Stream — Real-Time Mobile Sensor Telemetry & Visualization

Turn an Android phone into a low-latency multi-sensor telemetry device and stream its
sensors to a laptop, with live values, real-time graphs, and a 3D view of the phone's
orientation. Built as a telemetry system: correctness → low latency → high-rate stability
→ synchronization → smooth visualization.

- **`android/`** — the phone app (Kotlin, Jetpack Compose). Enumerates sensors, streams
  them over UDP, control over WebSocket.
- **`laptop/`** — the receiver + browser dashboard (Python asyncio; Three.js/uPlot front-end).
  ROS-ready via a pluggable `OutputSink` (`laptop/sensorstream/sinks.py`).
- **`docs/`** — protocol/schema, build & run, coordinate systems, the side-by-side test plan,
  troubleshooting.

## Status
| Component | State |
|---|---|
| Wire protocol + Python codec | ✅ implemented, 14 tests passing |
| Laptop receiver + dashboard (Phase 1) | ✅ runs; `--selftest` verified end-to-end |
| Android app (Phase 1: accelerometer → UDP) | ✅ source complete; build in Android Studio |
| Phases 2–8 (multi-sensor, 3D, graphs, sync, logging, discovery…) | ⏳ planned — see `docs/test-plan.md` |

## Quick start
1. **Laptop:** `cd laptop && python -m venv .venv` → activate → `pip install -r requirements.txt`
   → `python -m sensorstream.app --selftest` → open <http://localhost:8080>.
2. **Phone:** install Android Studio (`winget install --id Google.AndroidStudio -e`), open
   `android/`, let Gradle sync, Run on the phone. Enter the laptop IP + port `5005`, Start.

Full instructions: [`docs/build.md`](docs/build.md) and [`docs/run.md`](docs/run.md).
Wire format: [`docs/protocol.md`](docs/protocol.md). Test plan: [`docs/test-plan.md`](docs/test-plan.md).
