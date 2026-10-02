"""IMU / magnetometer covariance for ROS 2, from the newest Still test run of the same phone model.

A Still test run's report holds each axis's standard deviation; its square is the variance that goes
on the covariance diagonal. Sensor types are looked up independently (a newer report without a
gyroscope does not hide an older report's gyroscope), and never across phone models. Orientation:
yaw from the rotation vector's own heading accuracy, roll/pitch approximated from the accelerometer
noise (tilt comes from gravity): (mean(sigma_x, sigma_y) / 9.81)^2 - only when both are known, since a
single 0 on the diagonal reads as "exact" in ROS (all zeros = unknown). Every value is a float: rclpy's
double[9] setters reject ints. A report of an unexpected shape is skipped, never raised.
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


def _number(x) -> bool:
    return isinstance(x, (int, float)) and not isinstance(x, bool) and math.isfinite(x)


def _std3(s: dict) -> Optional[List[float]]:
    a = s.get("axes")
    if not isinstance(a, dict):
        return None
    out = []
    for name in ("x", "y", "z"):
        ax = a.get(name)
        x = ax.get("std") if isinstance(ax, dict) else None
        if not _number(x):
            return None
        out.append(float(x))
    return out


def _still_reports(log_dir: str, model: str) -> List[dict]:
    """Still test-run reports of this phone model, newest first (unreadable / foreign ones skipped)."""
    if not model:
        return []
    reports = []
    for path in glob.glob(os.path.join(log_dir, "*.report.json")):
        try:
            with open(path, encoding="utf-8") as fh:
                r = json.load(fh)
        except (OSError, ValueError):
            continue
        if not isinstance(r, dict) or r.get("preset") != "still":
            continue
        dev = r.get("device")
        if not isinstance(dev, dict) or dev.get("model") != model:
            continue
        reports.append(r)
    reports.sort(key=lambda r: r["created"] if isinstance(r.get("created"), str) else "", reverse=True)
    return reports


def find_covariance(log_dir: str, model: str) -> Dict[int, dict]:
    """{sensor_type: {"var": [vx, vy, vz], "report": id}} from the newest usable Still report per type."""
    out: Dict[int, dict] = {}
    for r in _still_reports(log_dir, model):
        sensors = r.get("sensors")
        sensors = [s for s in sensors if isinstance(s, dict)] if isinstance(sensors, list) else []
        sensors.sort(key=lambda s: s["samples"] if _number(s.get("samples")) else 0, reverse=True)
        for s in sensors:
            t = s.get("type")
            if type(t) is not int or t not in COV_TYPES or t in out or s.get("insufficient"):
                continue
            std = _std3(s)
            if std is not None:
                out[t] = {"var": [x * x for x in std], "report": r.get("id")}
    return out


def _nd3(s: dict) -> Optional[List[float]]:
    a = s.get("axes")
    if not isinstance(a, dict):
        return None
    out = []
    for name in ("x", "y", "z"):
        ax = a.get(name)
        x = ax.get("noise_density") if isinstance(ax, dict) else None
        if not _number(x) or x <= 0:
            return None
        out.append(float(x) * float(x))
    return out


def find_noise(log_dir: str, model: str) -> Dict[int, List[float]]:
    """{sensor_type: [noise PSD level x, y, z]} from the newest Still report per type (for --filter)."""
    out: Dict[int, List[float]] = {}
    for r in _still_reports(log_dir, model):
        sensors = r.get("sensors")
        for s in (sensors if isinstance(sensors, list) else []):
            t = s.get("type") if isinstance(s, dict) else None
            if type(t) is not int or t not in COV_TYPES or t in out or s.get("insufficient"):
                continue
            n = _nd3(s)
            if n is not None:
                out[t] = n
    return out


def diag(var3: List[float]) -> List[float]:
    return [float(var3[0]), 0.0, 0.0, 0.0, float(var3[1]), 0.0, 0.0, 0.0, float(var3[2])]


def orientation_cov(accel_var: Optional[List[float]], heading_acc: Optional[float]) -> List[float]:
    if not accel_var or not heading_acc:
        return list(UNKNOWN)
    s = (math.sqrt(accel_var[0]) + math.sqrt(accel_var[1])) / 2 / G
    return diag([s * s, s * s, heading_acc * heading_acc])


def describe(cov: Dict[int, dict], model: str) -> str:
    if not cov:
        return f"ROS covariance: none for {model} - run a Still test run to fill it"
    parts = [f"{COV_TYPES[t]} from {cov[t]['report']}" for t in (1, 4, 2) if t in cov]
    return f"ROS covariance: {model} - " + ", ".join(parts)
