"""Which ROS 2 topics a sensor sample goes to (pure; no rclpy, so it is testable anywhere).

Raw samples keep their existing topics. When the laptop's FilterBank has filtered the sample,
the same values also go to a parallel `*_filtered` topic of the same message type. Orientation
quaternions are never filtered.
"""

from __future__ import annotations

import math
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
    if t == ROTVEC and len(v) >= 3:
        x, y, z = v[0], v[1], v[2]
        w = v[3] if len(v) >= 4 else math.sqrt(max(0.0, 1.0 - x * x - y * y - z * z))
        return [("/phone/orientation", "quat", [x, y, z, w])]
    return []
