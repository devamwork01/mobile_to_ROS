# Network protocol & message schema

Two channels:

- **Telemetry** — UDP, compact **little-endian binary**. High rate, lossy-tolerant, no ACKs.
  Implemented by `laptop/sensorstream/protocol.py` and `android/.../codec/BinaryPacketCodec.kt`.
- **Control** — WebSocket, **JSON**. Reliable, framed, low rate. (Phase 2.)

The two codecs are pinned together by a golden vector: `laptop/tests/golden_packet.bin`
(74 bytes) is asserted byte-for-byte by both the Python and Kotlin tests.

## Telemetry datagram (little-endian, standard sizes, no padding)

### Header — 10 bytes
| off | field | type | notes |
|--:|---|---|---|
| 0 | magic | u16 | `0x5353` ("SS") |
| 2 | version | u8 | `1` |
| 3 | flags | u8 | bit0 = per-record stage timestamps present |
| 4 | device_id | u32 | assigned by laptop at control handshake (0 until then) |
| 8 | record_count | u16 | number of records following |

### Record — 20 bytes fixed + payload
| field | type | notes |
|---|---|---|
| sensor_type | i32 | Android `Sensor.getType()` |
| sensor_handle | u16 | stable per-session index; metadata sent once over control |
| seq | u32 | **per-sensor** sequence number (loss detection); wraps mod 2³² |
| t_sensor_ns | i64 | Android `SensorEvent.timestamp` (monotonic) — carried verbatim |
| accuracy | i8 | Android accuracy status (−1..3) |
| n_values | u8 | count of float32 values that follow |
| values | f32 × n_values | the measurement |
| t_acquire_ns | i64 | only if flags bit0 |
| t_serialize_ns | i64 | only if flags bit0 |

Multiple records (possibly different sensors) may share one datagram. A datagram must contain
exactly `record_count` well-formed records and no trailing bytes, or it is rejected.

### Golden vector (canonical bytes)
Two records — accelerometer `[0.12, -9.81, 0.42]` (type 1, seq 12345, acc 3) and gyroscope
`[0.01, 0.02, -0.03]` (type 4, seq 7, acc 2), device_id `0x0A0B0C0D`:
```
535301000d0c0b0a0200
010000000000 3930000079df0d86487000000303 8fc2f53dc3f51cc13d0ad73e
040000000100 07000000df350f86487000000203 0ad7233c0ad7a33c8fc2f5bc
```

## Units (per sensor type)
accel / linear-accel / gravity `m/s²` · gyroscope `rad/s` · magnetometer `µT` · pressure `hPa` ·
ambient temperature `°C` · relative humidity `%` · light `lx` · proximity `cm` · rotation vector
= unitless quaternion. Source of truth: `android/.../core/Units.kt`.

## Control channel (WebSocket JSON) — Phase 2
One server, two paths: `/phone` (device control) and `/ui` (dashboard). Message `type`s:
`hello` (device info + full sensor catalog + clock base) · `hello_ack` (device_id, UDP port,
config) · `clock_ping`/`clock_pong` (NTP-like offset, t0..t3) · `configure` (per-sensor enable,
rate preset or period_us, maxReportLatencyUs, timestamp mode) · `config_state` (effective config +
measured Hz) · `heartbeat`/`heartbeat_ack` · `stats` · `error` · `start`/`stop`. Constants are
defined in `laptop/sensorstream/protocol.py` (`MSG_*`).
