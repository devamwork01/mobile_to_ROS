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
| On-phone recording + laptop backfill (screen-off data integrity) | ✅ Phase 2A+2B implemented; lossless across drops, gap detect + resend/dedup — **verified on S25 Ultra + Galaxy M36 5G**, merged to main |
| Dashboard redesign — "SensorStream Pro" (React) | ✅ design system, trimmed nav, live cards, real-time graphs, sensor details modal, light/dark theme |
| Premium 3D device view | ✅ rounded titanium + glass phone, image-based lighting, contact shadow (Three.js) |
| Mobile app UI — premium redesign (Jetpack Compose) | ✅ Home / Sensors / Sensor Detail / Orientation / Connection / Settings / Diagnostics; light+dark theme override; on-device pseudo-3D orientation matching the laptop — **verified on S25 Ultra** |
| Branding | ✅ adaptive app icon + dashboard favicon (shared 3-axis mark) |

Both the dashboard and the phone app have been verified against a real phone (S25 Ultra),
in light and dark themes. A few larger items remain — see
[Pending / not yet verified](#pending--not-yet-verified).

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
3. In the app: open **Connection**, use **Find Laptop Automatically** (or enter the laptop's
   LAN IP + control port `8081`), pick sensors on the **Sensors** tab, and **Connect & Stream**.

The phone app has a premium redesigned UI (Home with a live pseudo-3D orientation view,
per-sensor detail screens, a Diagnostics screen, and a Settings screen with a light/dark theme
override). Streaming continues in the background via a foreground service; tap its notification
to return to the app.

**Quick reinstall (Windows):** with a device plugged in, run `install-phone.bat` from the repo
root — it builds the debug APK (JBR 21) and installs it on every connected device.

Full instructions: [`docs/build.md`](docs/build.md) and [`docs/run.md`](docs/run.md).

### Network priority on a busy Wi-Fi

On a shared network, bandwidth is arbitrated by the router/access point, not by an
app — an app can only *hint*. SensorStream does the hint automatically: the phone
tags its telemetry with **DSCP EF** and its control channel with **DSCP AF41**, and
the laptop receiver marks its socket to match. On access points that honor DSCP→WMM
(most do), this gives the stream preferential airtime under contention. It is
best-effort — never a guarantee — and browsers can't be tagged, so the dashboard's
own traffic isn't prioritized.

For a **reliable** result with several devices on the network, in order of ease:

1. **Give phone + laptop their own link** — run the phone's Wi-Fi hotspot and connect
   the laptop to it (or put both on a separate 5 GHz SSID). Nothing else competes.
2. **Router QoS** — if you can reach the router admin, prioritize the laptop's IP/MAC
   or the ports (UDP 5005, WS 8081). This truly guarantees priority, but is per-network
   manual setup.

## Running the tests

```bash
# Python (from laptop/, venv active)
cd laptop
python -m pytest

# Android (from android/) — use Gradle, not the IDE JUnit runner
./gradlew test        # Windows: gradlew.bat test
```

### Soak test (long-run stability)

`tools/soak.py` launches the server on isolated ports, blasts a wire-identical
multi-sensor UDP load, samples memory/throughput/latency, and prints a PASS/FAIL
report (no phone needed). Artifacts land in `laptop/soak-out/` (git-ignored).

```bash
cd laptop
python tools/soak.py --minutes 30      # default; use --minutes 2 for a quick smoke
```

It fails the run if decode errors or permanent gaps appear, throughput drifts
>10% from target, RSS grows like a leak, or the server crashes.

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

### B. Dashboard UI
- [x] Light theme toggle — theme-aware CSS-variable tokens (dark default + light palette),
      System/Light/Dark toggle in Settings, persisted; verified in-browser (dark + light)
- [x] Recordings browser (server-side list + LOD history chart + zoom) — built earlier
- [x] Settings view — Appearance (theme) + read-only connection info (replaces the placeholder)
- [~] Responsive / phone-width layout (~400px) — NavRail collapses to an icon rail below `md`,
      content grids stack; desktop verified, true ~400px visual check pending a device/emulator

### C. Premium 3D
- [x] Rounded/metallic/glass phone renders in both frames; toggles work; no perf regression;
      WebGL cleanup on unmount — verified via `--selftest`

### D. System items
- [x] Live sensor reconfig — toggle a sensor on the phone while streaming adds/removes its
      dashboard card + graph tab live, no reconnect (phone `updateSelections` + `active` control msg)
- [~] Galaxy M36 5G validation — app installs, enumerates 34 sensors, streams at 122 Hz.
      **Diagnosed lag:** locking the phone spikes network latency ~7 ms → ~155 ms with bursty
      delivery, so the dashboard plots stutter. It is **OEM screen-off/background power
      management**, not an app bug — verified the app holds a `PARTIAL_WAKE_LOCK` +
      `FULL_LOW_LATENCY` WifiLock, is battery-optimization exempt (deviceidle whitelist), not
      frozen; sensors are non-wake-up with no HW batching and the M36 has no wake-up IMU
      variants. S25 Ultra's power management doesn't impose this.
- [x] **Keep screen on while streaming** (`FLAG_KEEP_SCREEN_ON` in MainActivity) — prevents the
      screen-lock that triggers the M36 throttle; verified latency back to ~10 ms. Note: manually
      switching apps / opening recent apps still backgrounds the app (same OEM throttle) and
      recovers on return — no in-app fix for that; keep the app foreground while streaming.
- [x] On-phone lossless recording + laptop backfill (screen-off data integrity; laptop stays
      authoritative, live path untouched) — **implemented, verified on S25 Ultra + Galaxy M36 5G,
      merged to main.** Spec:
      [`docs/superpowers/specs/2026-09-14-onphone-recording-backfill-design.md`](docs/superpowers/specs/2026-09-14-onphone-recording-backfill-design.md)
    - [x] **Phase 1 — on-phone recording**: `LocalRecorder` writes a bounded `.ssbin` ring
          (byte-compatible with `logging_sink.py`), fanned out from acquisition off the sensor
          callback (non-blocking `trySend` → IO drain), size/age prune, `(handle,seq)` index for
          resend, on-device size/buffered/dropped in the status card. Acquisition keeps running and
          the recorder keeps writing across a WS drop (session/sender split). Verified on-device.
    - [x] **Phase 2 — laptop gap detection + backfill request/serve + merge/dedup on read**:
          `GapTracker` (keyed by `client_id` so gaps survive device-id rotation), `Reconciler`,
          `MSG_RESEND`/`BACKFILL` protocol, phone `BackfillResponder` (verbatim datagram resend),
          recordings dedup by `(handle,seq)`. Verified end-to-end (firewall-induced loss →
          backfilled, recording gap-free). 17 phone + 41 laptop tests green.
- [ ] ROS2 bridge (`Ros2Sink` behind the `OutputSink` seam)
- [ ] Soak test (long-run stability)

### F. Mobile app UI — premium redesign (Jetpack Compose) — verified on S25 Ultra
UI-only redesign over the existing `StreamViewModel`; the sensor/fusion/network/wire pipeline is
untouched. Axis colors are fixed (X=red, Y=green, Z=blue) in both themes.
- [x] Design system (theme-aware tokens, typography, dimens) + bottom-nav shell + back stack
- [x] Home — live pseudo-3D orientation hero (Canvas, matches the laptop's `androidToThree` frame),
      status card (tap to open Connection), active-sensor summary, start/stop
- [x] Sensors — categorized list; Sensor Detail — live values/magnitude, mini graph, sampling-rate
      config; Orientation — world frame + body/world/labels toggles + Euler/Quaternion
- [x] Environmental hero (gauge for pressure, big value for temp/humidity/light)
- [x] Connection — editable IP/port, Find Laptop, success/not-found discovery feedback
- [x] Settings — System/Light/Dark theme override (persisted + applied), 3D defaults, about
- [x] Diagnostics — latency/throughput, reliability counters, on-phone buffer
- [x] Polish — pulsing status dot, tappable foreground-service notification (returns to app)
- [ ] Reinstall the current build on the Galaxy M36 (it is on an older build)
- [ ] Light theme + full streaming pass on a second device

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
    - [x] Zoom→resolution: drag-select re-queries the server at ~1 bucket/px (verified ~5× finer on a 20% window)
    - [x] Delivery-semantics/health metrics in Diagnostics (coalesced vs lost; UI-rate cap; raw = lossless)
    - [x] Recordings list bug: `_peek_sensors` now scans a window so real-phone recordings list all sensors
    - [ ] Recordings pagination (cursor) — only once the list grows large

## Reference

Wire format: [`docs/protocol.md`](docs/protocol.md) ·
Coordinate systems: [`docs/coordinate-systems.md`](docs/coordinate-systems.md) ·
Test plan: [`docs/test-plan.md`](docs/test-plan.md) ·
Troubleshooting: [`docs/troubleshooting.md`](docs/troubleshooting.md)
