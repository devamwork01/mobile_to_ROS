# Phase 2B — verification status (Task 7)

Date: 2026-09-15. Device: Galaxy S25 Ultra (`RZGL31H3N4V`). Branch `feat/onphone-backfill-2a` @ Task 6.

## Automated tests — PASS
- **Phone unit:** 17 tests green (2 codec + 3 ClientId + 3 BackfillResponder + 9 LocalRecorder); `assembleDebug` SUCCESSFUL.
- **Laptop:** 41 pytest green, including the full backfill logic:
  - `test_gap_tracker.py` (6) — missing-seq detection, grace window, resolve, permanent, per-client isolation.
  - `test_recordings_dedup.py` (1) — out-of-order + duplicate `.ssbin` frames read back deduped by `(handle,seq)` and time-ordered.
  - `test_reconciler.py` (4) — **full round trip**: detect gap → emit resend → merge backfill → resolve; no double-request while in-flight; unavailable→permanent; **client survives device_id change** (gap tracked under a client is requested from its new device_id after reconnect).
  - `test_backfill_protocol.py` (3) — message constants + base64 frame round-trip.

## On-device — live path healthy, backfill trigger NOT yet demonstrated
- Installed the 2B build, streamed 4 sensors @116 Hz with `--record`. Live streaming, on-phone recording, and the reconciler wiring ran with **no crashes/regressions**; server started clean with all backfill wiring active.
- Recording read back **gap-free** (per-handle seq `0..N`, MISSING=0 across ~39k samples/handle).
- **BUT** the authoritative server counter showed **`backfilled: 0`** — i.e. no gap was ever detected, so the backfill request/serve/merge path was **not exercised end-to-end**. The recording was gap-free because no receiver-visible loss occurred, not because backfill repaired it.

### Why loss could not be induced here
- Firewall block of UDP 5005 (the clean way to drop telemetry while keeping the WS control channel up) **requires administrator elevation**, which this session does not have.
- `adb shell cmd wifi set-wifi-enabled disabled` did **not** interrupt delivery: the server's packet count kept climbing during "Wi-Fi off" (225k→232k), so no UDP was actually lost and no gap formed.

## Outstanding — the one remaining acceptance step (needs the user / a real network drop)
Demonstrate an actual backfill on-device by inducing genuine UDP loss while the WS stays up, then confirm `backfilled > 0`, `permanent_gaps == 0`, phone status shows `backfill served N`, and the recording is gap-free over the loss window. Reliable ways:
1. **Admin firewall block** (recommended, deterministic): in an elevated shell, while streaming with `--record`:
   `netsh advfirewall firewall add rule name="ss_block_udp5005" dir=in action=block protocol=UDP localport=5005`
   wait ~15 s, then `netsh advfirewall firewall delete rule name="ss_block_udp5005"`. The control WS (TCP 8081) stays open, so the phone doesn't reconnect; the laptop sees seq gaps and requests backfill.
2. **Physical range drop:** carry the phone out of Wi-Fi range (or into a faraday-ish dead spot) for ~15 s and back, keeping the app foreground.

The code path this exercises is fully unit- and integration-tested (the reconciler round-trip above); this step is the on-hardware confirmation.
