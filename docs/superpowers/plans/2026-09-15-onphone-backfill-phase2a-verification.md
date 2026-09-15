# Phase 2A — on-device verification (Task 4)

Device: Galaxy S25 Ultra (`SM-S938B`, serial `RZGL31H3N4V`). Build: `feat/onphone-backfill-2a` @ `3e0a22d`.
Laptop server on `10.163.35.38`; phone auto-discovered it. Date: 2026-09-15.

## Method
Streamed 4 sensors (accel/mag/gyro + one) at ~116 Hz; let ~8 segments accumulate; **killed the laptop
server for ~15 s** (control-channel outage) then restarted it; confirmed the phone auto-reconnected;
pulled the full `.ssbin` set and decoded with the laptop `protocol.decode_datagram`.

## Results — both acceptance criteria PASS

1. **Recording has no hole across the outage.** Segment wall-clock timestamps stayed continuous at
   ~10 s spacing straight through the server-down window (…`_7`@…078788 → `_8`@…088790 → `_9`@…098790…).
   Segment `_8` was written *while the server was dead* — in Phase 1 acquisition stopped on the WS drop,
   so no segment would have appeared during the outage. Recorder salt series stayed monotonic (reused,
   not rebuilt — Phase 1 property retained).

2. **seq is continuous across the reconnect (zero loss).** Decoding every segment end-to-end:

   | handle | records | seq range | non-unit steps | strictly increasing |
   |---|---|---|---|---|
   | 0 | 25043 | 0..25042 | **0** | yes |
   | 1 | 21511 | 0..21510 | **0** | yes |
   | 2 | 25042 | 0..25041 | **0** | yes |
   | 7 | 21509 | 0..21508 | **0** | yes |

   Every per-handle sequence is a perfectly contiguous run `0..N` (step exactly 1) spanning the outage
   and reconnect — no reset to 0, no gaps, no duplicates. Post-reconnect first seq was 13973 (not 0),
   proving acquisition never restarted. This is lossless on-phone capture through a full control-channel
   outage — the precondition Plan 2B backfill relies on.

Status card after reconnect: `Streaming · RTT 6ms · packets 88170 · dropped 0 · on-phone rec 5.4 MB ·
buffered 234s · pruned 0`.

## Not separately asserted
`client_id` presence in `hello` was implemented + JVM-tested (Task 1); the server doesn't log hello
fields, so it was not re-observed on the wire here. Plan 2B (laptop side) will consume and surface it,
which is where an on-the-wire check naturally lands.
