"""IMU / magnetometer covariance for ROS 2, from the newest Still test run of the same phone model.

A Still test run's report holds each axis's standard deviation; its square is the variance that goes
on the covariance diagonal. Sensor types are looked up independently (a newer report without a
gyroscope does not hide an older report's gyroscope), and never across phone models. Orientation:
yaw from the rotation vector's own heading accuracy, roll/pitch approximated from the accelerometer
noise (tilt comes from gravity): (mean(sigma_x, sigma_y) / 9.81)^2.
"""

from __future__ import annotations

import glob
import json
import math
import os
from typing import Dict, List, Optional

COV_TYPES = {1: "accel", 4: "gyro", 2: "mag"}
G = 9.81
UNKNOWN = [0.0] * 9
NOT_PROVIDED = [-1.0] + [0.0] * 8


def _std3(s: dict) -> Optional[List[float]]:
    a = s.get("axes") or {}
    out = []
    for name in ("x", "y", "z"):
        x = (a.get(name) or {}).get("std")
        if isinstance(x, bool) or not isinstance(x, (int, float)) or not math.isfinite(x):
            return None
        out.append(float(x))
    return out


def find_covariance(log_dir: str, model: str) -> Dict[int, dict]:
    """{sensor_type: {"var": [vx, vy, vz], "report": id}} from the newest usable Still report per type."""
    if not model:
        return {}
    reports = []
    for path in glob.glob(os.path.join(log_dir, "*.report.json")):
        try:
            with open(path, encoding="utf-8") as fh:
                r = json.load(fh)
        except (OSError, ValueError):
            continue
        if not isinstance(r, dict) or r.get("preset") != "still":
            continue
        if (r.get("device") or {}).get("model") != model:
            continue
        reports.append(r)
    reports.sort(key=lambda r: r.get("created") or "", reverse=True)
    out: Dict[int, dict] = {}
    for r in reports:
        sensors = [s for s in (r.get("sensors") or []) if isinstance(s, dict)]
        sensors.sort(key=lambda s: s.get("samples") or 0, reverse=True)
        for s in sensors:
            t = s.get("type")
            if t not in COV_TYPES or t in out or s.get("insufficient"):
                continue
            std = _std3(s)
            if std is not None:
                out[t] = {"var": [x * x for x in std], "report": r.get("id")}
    return out


def diag(var3: List[float]) -> List[float]:
    return [var3[0], 0, 0, 0, var3[1], 0, 0, 0, var3[2]]


def orientation_cov(accel_var: Optional[List[float]], heading_acc: Optional[float]) -> List[float]:
    tilt = 0.0
    if accel_var:
        s = (math.sqrt(accel_var[0]) + math.sqrt(accel_var[1])) / 2 / G
        tilt = s * s
    yaw = heading_acc * heading_acc if heading_acc else 0.0
    if not tilt and not yaw:
        return list(UNKNOWN)
    return diag([tilt, tilt, yaw])


def describe(cov: Dict[int, dict], model: str) -> str:
    if not cov:
        return f"ROS covariance: none for {model} - run a Still test run to fill it"
    parts = [f"{COV_TYPES[t]} from {cov[t]['report']}" for t in (1, 4, 2) if t in cov]
    return f"ROS covariance: {model} - " + ", ".join(parts)
