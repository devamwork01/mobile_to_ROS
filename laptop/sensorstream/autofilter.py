"""--filter: per-axis filters for the accelerometer, gyroscope and magnetometer, tuned from each
axis's own spectrum, plus a report of what each spectrum showed.

Findings per axis come from the same Welch spectrum the suggestion used: the noise floor, the
low-pass and its delay, each notch with how far its peak stood out, and sigma before / after the
filter (integrated from the spectrum and the filter's frequency response).
"""

from __future__ import annotations

import math
from datetime import datetime, timezone
from typing import Dict, List

import numpy as np

from .filters import frequency_response

UNITS = {1: "m/s^2", 4: "rad/s", 2: "uT"}
GENERIC = {1: "Acceleration", 4: "Angular Velocity", 2: "Magnetic Field"}  # dashboard names (signals.js)
AUTO_TYPES = (1, 4, 2)
AXIS = ("X", "Y", "Z")
# Low-frequency group delay of a Butterworth low-pass = K / (2 pi fc) seconds.
DELAY_K = {2: 1.4142135623730951, 4: 2.613125929752753}


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _integrate(f: np.ndarray, y: np.ndarray) -> float:
    if f.size < 2:
        return 0.0
    return float(np.sum((y[1:] + y[:-1]) * np.diff(f)) / 2.0)


def axis_findings(f, psd, fs: float, config: dict, details: dict) -> dict:
    f = np.asarray(f, dtype=np.float64)
    p = np.asarray(psd, dtype=np.float64)
    m = (f > 0) & np.isfinite(p)
    f, p = f[m], p[m]
    h2 = frequency_response(config, fs, f) ** 2 if f.size else np.array([])
    lp = config.get("lowpass")
    floor = details.get("floor")
    return {
        "noise_density": math.sqrt(floor) if floor and floor > 0 else None,
        "cutoff_hz": lp["hz"] if lp else None,
        "order": lp["order"] if lp else None,
        "delay_ms": round(DELAY_K[lp["order"]] / (2 * math.pi * lp["hz"]) * 1000.0, 1) if lp else 0.0,
        "notches": [{"hz": n["hz"], "q": n["q"], "prominence": round(float(pr), 1)}
                    for n, pr in zip(config.get("notches") or [], details.get("prominence") or [])],
        "sigma_raw": math.sqrt(max(_integrate(f, p), 0.0)),
        "sigma_filtered": math.sqrt(max(_integrate(f, p * h2), 0.0)),
    }


def sensor_report(key: str, handle, type_: int, fs: float, f, per, configs, details, at: str) -> dict:
    return {
        "key": key,
        "name": key.split(":", 1)[1] if ":" in key else key,
        "type": type_,
        "handle": handle,
        "unit": UNITS.get(type_),
        "fs": round(float(fs), 2),
        "at": at,
        "axes": [axis_findings(f, per[i], fs, configs[i], details[i]) for i in range(len(configs))],
    }


def _g(x, fmt: str) -> str:
    return "-" if x is None else format(x, fmt)


def format_report(rec: dict) -> str:
    name = str(rec.get("name", "")).encode("ascii", "replace").decode()
    lines: List[str] = [f"[filter] {GENERIC.get(rec.get('type'), 'Sensor')} ({name}) @ {rec['fs']:.1f} Hz",
                        "   axis  noise/rtHz   cutoff   order  delay    notches              sigma raw -> filtered"]
    for ax, a in zip(AXIS, rec["axes"]):
        notches = ", ".join(f"{n['hz']:g} Hz (x{n['prominence']:.0f})" for n in a["notches"]) or "-"
        lines.append(f"   {ax:<4}  {_g(a['noise_density'], '.2e'):<11}  {_g(a['cutoff_hz'], '.2f'):<7}  "
                     f"{_g(a['order'], 'd'):<5}  {a['delay_ms']:>4.0f} ms  {notches:<20} "
                     f"{a['sigma_raw']:.4g} -> {a['sigma_filtered']:.4g}")
    return "\n".join(lines)


GAP_NS = 1_000_000_000      # a pause longer than this starts a new run
RATE_CHANGE = 0.05          # the short-term interval moving this far from the run's mean...
RATE_SUSTAIN = 20           # ...for this many samples in a row is a new sampling rate: a new run
_FAST = 0.2                 # EWMA weight of the short-term interval


class RunTracker:
    """Per handle, when its current unbroken run of samples started (sensor time). A gap > 1 s, or
    the sample interval moving > 5 % from the run's rate for 20 samples in a row, starts a new run.
    The run is what --filter waits on: 10 s of one run is 10 s of clean, single-rate data."""

    def __init__(self) -> None:
        # handle -> [start, last, n_intervals, mean_interval, fast_interval, off_count]
        self._s: Dict[int, list] = {}

    def reset(self) -> None:
        self._s.clear()

    def observe(self, handle: int, t_ns: int) -> None:
        s = self._s.get(handle)
        if s is None:
            self._s[handle] = [t_ns, t_ns, 0, 0.0, 0.0, 0]
            return
        start, last, n, mean, fast, off = s
        dt = t_ns - last
        if dt <= 0:          # duplicate or reordered sample
            return
        if dt > GAP_NS:
            self._s[handle] = [t_ns, t_ns, 0, 0.0, 0.0, 0]
            return
        if n == 0:
            mean = fast = float(dt)
        else:
            fast += _FAST * (dt - fast)
            if abs(fast - mean) > RATE_CHANGE * mean:
                off += 1
                if off >= RATE_SUSTAIN:
                    self._s[handle] = [t_ns, t_ns, 0, 0.0, 0.0, 0]
                    return
            else:
                off = 0
                mean += (dt - mean) / (n + 1)
        self._s[handle] = [start, t_ns, n + 1, mean, fast, off]

    def run_seconds(self, handle: int) -> float:
        s = self._s.get(handle)
        return 0.0 if s is None else (s[1] - s[0]) / 1e9
