# Troubleshooting

## Phone says "streaming" but the dashboard shows nothing
Most common on home Wi-Fi. Check in order:
1. **Right IP/port?** Re-read the laptop's `ip addr` / `ipconfig`; the app must point at the
   laptop's Wi-Fi IP and UDP port `5005`.
2. **Firewall.** Linux: `sudo ufw allow 5005/udp`. Windows dev box: allow the Defender prompt on
   Private networks.
3. **Router client/AP isolation.** Many home routers (and all "guest" networks) block device↔device
   traffic. Test reachability: from the laptop `ping <phone-ip>` — if it fails, isolation is on.
   Fix: disable "AP isolation"/"client isolation" in the router, **or** use the phone's Wi-Fi
   hotspot and join the laptop to it, **or** a small travel router. (Auto-discovery in Phase 8
   cannot cross isolation either — this is a network setting, not an app bug.)
4. **Same subnet?** Both must be on the same Wi-Fi (not one on Ethernet + a different VLAN).

## Values look wrong / axes seem swapped
Android accelerometer convention: flat & screen-up → `Az ≈ +9.81`, `Ax≈Ay≈0`. If yours differ,
note the phone's orientation — the app never silently remaps axes; the 3D view (Phase 3) documents
and validates every axis.

## Measured rate is below the requested rate
- The sensor HAL caps some sensors; the app shows the **actual** measured Hz, which is the truth.
- Rates **above 200 Hz** require the `HIGH_SAMPLING_RATE_SENSORS` permission (already declared) and
  are still subject to hardware limits. "Max" ≠ arbitrary.
- On the Windows `--selftest` generator only, timer granularity makes the synthetic rate look low
  (~60 Hz); this is a PC timer artifact, not the protocol — real phone timestamps are hardware-accurate.

## `adb devices` doesn't list the phone
Enable **USB debugging** (Settings → About → tap Build number ×7 → Developer options), replug,
accept the RSA prompt on the phone. Ensure `%LOCALAPPDATA%\Android\Sdk\platform-tools` is on PATH.
Samsung phones sometimes need the OEM USB driver; try a different cable/port (data, not charge-only).

## Gradle sync fails on first open
Needs internet for the first sync (downloads Gradle 8.9 + dependencies). If it complains about
AGP/JDK, use the Studio-bundled JBR (Settings → Build Tools → Gradle → Gradle JDK = jbr-17) and
accept any AGP upgrade suggestion.

## Dashboard opens but stays "disconnected"
The page reaches the WebSocket on port `8081` by default. If you changed `--ws-port`, open
`http://<host>:8080/?ws=<newport>`. Check nothing else is bound to 8080/8081.
