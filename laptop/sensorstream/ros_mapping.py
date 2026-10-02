"""Which ROS 2 topics a sensor sample goes to (pure; no rclpy, so it is testable anywhere).

`ImuPairer` builds the combined `/phone/imu/*` samples: each gyroscope sample paired with the newest
fresh accelerometer sample (and rotation vector, for `/data`).

Raw samples keep their existing topics. When the laptop's FilterBank has filtered the sample,
the same values also go to a parallel `*_filtered` topic of the same message type. Orientation
quaternions are never filtered.
"""

from __future__ import annotations

import math
from collections import deque
from dataclasses import dataclass
from typing import List, Optional, Sequence, Tuple

ACCEL, MAG, GYRO, ROTVEC = 1, 2, 4, 11
_VECTOR = {ACCEL: ("/phone/accelerometer", "imu_accel"), GYRO: ("/phone/gyroscope", "imu_gyro"),
           MAG: ("/phone/magnetic_field", "mag")}

Target = Tuple[str, str, List[float]]


def _finite3(vals: Optional[Sequence]) -> Optional[List[float]]:
    if not vals or len(vals) < 3:
        return None
    out = []
    for x in vals[:3]:
        if x is None or not math.isfinite(x):
            return None
        out.append(float(x))
    return out


def _quat(v: Sequence) -> Optional[List[float]]:
    """Android rotation vector -> [x, y, z, w]; w derived when the phone sends only x, y, z."""
    if len(v) < 3 or not all(math.isfinite(x) for x in v[:3]):
        return None
    x, y, z = float(v[0]), float(v[1]), float(v[2])
    if len(v) >= 4:
        if not math.isfinite(v[3]):
            return None
        return [x, y, z, float(v[3])]
    return [x, y, z, math.sqrt(max(0.0, 1.0 - x * x - y * y - z * z))]


def ros_targets(record, filtered: Optional[Sequence] = None) -> List[Target]:
    t, v = record.sensor_type, list(record.values)
    if t in _VECTOR:
        if len(v) < 3:
            return []
        topic, kind = _VECTOR[t]
        out: List[Target] = [(topic, kind, [float(x) for x in v[:3]])]
        fv = _finite3(filtered)
        if fv is not None:
            out.append((topic + "_filtered", kind, fv))
        return out
    if t == ROTVEC:
        q = _quat(v)
        return [("/phone/orientation", "quat", q)] if q is not None else []
    return []


STALE_PERIODS = 3
STALE_FALLBACK_NS = 100_000_000


class _Interval:
    """A sensor's sampling interval: median of its last 16 sample spacings."""

    def __init__(self) -> None:
        self._last: Optional[int] = None
        self._d: deque = deque(maxlen=16)

    def add(self, t: int) -> None:
        if self._last is not None and t > self._last:
            self._d.append(t - self._last)
        if self._last is None or t > self._last:
            self._last = t

    def max_age(self) -> int:
        if not self._d:
            return STALE_FALLBACK_NS
        s = sorted(self._d)
        return STALE_PERIODS * s[len(s) // 2]


@dataclass
class ImuSample:
    t_ns: int                          # the gyroscope sample's phone time
    gyro: List[float]
    accel: List[float]
    quat: Optional[List[float]] = None
    heading_acc: Optional[float] = None
    filtered: bool = False


class ImuPairer:
    """One device's combined IMU: every gyroscope sample + the newest fresh accelerometer sample.

    "Fresh" = no older than 3 of that sensor's sampling intervals (100 ms until known); a held sample
    newer than the gyroscope's counts as fresh. The rotation vector (for /phone/imu/data) follows the
    same rule. A filtered twin is produced when the gyroscope or the paired accel sample was filtered;
    each field takes its filtered value when there is one. Orientation is never filtered.
    """

    def __init__(self) -> None:
        self._accel: Optional[tuple] = None   # (t, raw, filtered | None)
        self._rv: Optional[tuple] = None      # (t, quat, heading_acc | None)
        self._ia = _Interval()
        self._ir = _Interval()

    def on_record(self, record, filtered: Optional[Sequence] = None) -> List[ImuSample]:
        t, v, ts = record.sensor_type, list(record.values), record.t_sensor_ns
        if t == ACCEL:
            raw = _finite3(v)
            if raw is not None:
                self._ia.add(ts)
                self._accel = (ts, raw, _finite3(filtered))
            return []
        if t == ROTVEC:
            q = _quat(v)
            if q is not None:
                self._ir.add(ts)
                h = v[4] if len(v) >= 5 and math.isfinite(v[4]) and v[4] > 0 else None
                self._rv = (ts, q, float(h) if h is not None else None)
            return []
        if t != GYRO:
            return []
        gyro = _finite3(v)
        if gyro is None or self._accel is None:
            return []
        ta, araw, afilt = self._accel
        if ts - ta > self._ia.max_age():
            return []
        quat = head = None
        if self._rv is not None and ts - self._rv[0] <= self._ir.max_age():
            quat, head = self._rv[1], self._rv[2]
        out = [ImuSample(ts, gyro, araw, quat, head)]
        gfilt = _finite3(filtered)
        if gfilt is not None or afilt is not None:
            out.append(ImuSample(ts, gfilt if gfilt is not None else gyro,
                                 afilt if afilt is not None else araw, quat, head, filtered=True))
        return out
