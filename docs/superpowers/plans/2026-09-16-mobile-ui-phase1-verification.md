# Premium Mobile UI — Phase 1 verification

Device: Galaxy S25 Ultra (`RZGL31H3N4V`). Build: `main` after Phase 1 tasks 1–9. Date: 2026-09-16.

## Automated
- **28 JVM unit tests pass** (2 codec + 3 ClientId + 3 BackfillResponder + 9 LocalRecorder + 3 SignalCatalog + 3 Format + 1 Phone3DConfig + 4 Projection). `assembleDebug` SUCCESSFUL.
- No references to the retired `StreamScreen` remain; MainActivity hosts `AppScaffold`, preserving keep-screen-on + battery-exemption.

## On-device (dark theme)
- **Home:** premium header ("SensorStream" / "Real-Time Mobile Sensor Telemetry" + settings), hero **Canvas pseudo-3D phone** with projected **X=red / Y=green / Z=blue** axes + contact shadow, `● READY` status card (host `192.168.1.100`, latency —), metric cards (Active Sensors 4, Total Rate 400 Hz), active-sensor summary, Start Streaming button, bottom nav. Matches the reference composition/hierarchy.
- **Sensors:** categorized (MOTION / ORIENTATION / …) premium `SensorCard` rows — icon, human name, description, `100 Hz` rate badge + unit (m/s², rad/s), toggle; enabled sensors highlighted (accent). Human-readable names as primary labels. Real catalog data.

## Data / backend
- UI-only: all screens read `StreamViewModel` (`engineState`, `catalog`, `sel`, `live`, `toggle`, `periodOf`, `toggleStreaming`). No acquisition/network/telemetry code touched (Spec §§3, 51, 56). Start/Stop wires to the existing `toggleStreaming()` path (verified working in Phase 2B).

## Not verified this session (noted honestly)
- **Light theme on-device:** the light palette + theme wiring are symmetric with dark and follow `isSystemInDarkTheme()`; not screenshot-verified on-device this run (would require flipping the OS theme; the phone was mid video-call). The same theme-aware token approach was screenshot-verified on the laptop dashboard.
- **Full streaming functional pass with the hero phone tilting live:** not re-run this session (backend untouched by design). To confirm: connect to the laptop server, stream, and watch the hero phone track motion + the connection card show CONNECTED/latency.

## Next
Phase 2 (sensor-detail screens + config sheet) per the plan's roadmap.
