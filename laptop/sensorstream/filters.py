"""Real-time filters for SensorStream, guided by the spectrum.

Biquad (second-order) sections from the RBJ audio-EQ cookbook: a Butterworth low-pass of order 2
or 4 (cascaded sections with the Butterworth Q values) plus up to 3 notches. Filters are causal
and stateful so they run sample-by-sample on the live stream exactly as a robot stack would.
`suggest()` proposes a configuration from a Welch PSD. Pure python/numpy - no SciPy.
"""

from __future__ import annotations

import json
import math
import os
import time
from typing import Dict, List, Optional, Tuple

import numpy as np

BUTTER_Q = {2: [0.7071067811865476], 4: [0.5411961001461970, 1.3065629648763766]}
MAX_NOTCHES = 3
EXCLUDED_TYPES = {11, 15, 20, 5, 8, 17, 18, 19}  # orientation quaternions + on-change sensors

Section = Tuple[float, float, float, float, float]  # b0, b1, b2, a1, a2 (a0 normalised to 1)


def _rbj(kind: str, hz: float, q: float, fs: float) -> Section:
    w0 = 2.0 * math.pi * hz / fs
    c, s = math.cos(w0), math.sin(w0)
    alpha = s / (2.0 * q)
    if kind == "lowpass":
        b0, b1, b2 = (1 - c) / 2, 1 - c, (1 - c) / 2
    else:  # notch
        b0, b1, b2 = 1.0, -2.0 * c, 1.0
    a0, a1, a2 = 1 + alpha, -2.0 * c, 1 - alpha
    return (b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)


def lowpass_sections(hz: float, order: int, fs: float) -> List[Section]:
    return [_rbj("lowpass", hz, q, fs) for q in BUTTER_Q[order]]


def notch_section(hz: float, q: float, fs: float) -> Section:
    return _rbj("notch", hz, q, fs)


def sections(config: dict, fs: float) -> List[Section]:
    out: List[Section] = []
    lp = config.get("lowpass")
    if lp:
        out += lowpass_sections(float(lp["hz"]), int(lp["order"]), fs)
    for nt in config.get("notches") or []:
        out.append(notch_section(float(nt["hz"]), float(nt["q"]), fs))
    return out


def _num(x) -> Optional[float]:
    try:
        x = float(x)
    except (TypeError, ValueError):
        return None
    return x if math.isfinite(x) else None


def validate(config, fs: float) -> Tuple[bool, str]:
    if not isinstance(config, dict):
        return False, "Invalid filter configuration."
    nyq = 0.45 * fs
    lp = config.get("lowpass")
    notches = config.get("notches") or []
    if not lp and not notches:
        return False, "Enable a low-pass or add a notch."
    if lp:
        hz, order = _num(lp.get("hz")), lp.get("order")
        if hz is None or order not in (2, 4):
            return False, "Low-pass needs a cutoff and order 2 or 4."
        if not 0.1 <= hz <= nyq:
            return False, f"Cutoff must be between 0.1 and {nyq:.1f} Hz at {fs:.0f} Hz sampling."
    if not isinstance(notches, list) or len(notches) > MAX_NOTCHES:
        return False, f"At most {MAX_NOTCHES} notches."
    for nt in notches:
        hz, q = _num((nt or {}).get("hz")), _num((nt or {}).get("q"))
        if hz is None or q is None:
            return False, "Each notch needs a frequency and Q."
        if not 0.2 <= hz <= nyq:
            return False, f"Notch must be between 0.2 and {nyq:.1f} Hz at {fs:.0f} Hz sampling."
        if not 1.0 <= q <= 50.0:
            return False, "Notch Q must be between 1 and 50."
    return True, "ok"


class FilterChain:
    """Cascade of biquads applied per axis (direct form II transposed), state kept between calls."""

    def __init__(self, config: dict, fs: float, n_axes: int):
        self.config = config
        self.fs = float(fs)
        self.n_axes = int(n_axes)
        self._sec = sections(config, fs)
        self._z = [[[0.0, 0.0] for _ in self._sec] for _ in range(self.n_axes)]

    def process(self, values) -> List[float]:
        out = []
        for a in range(self.n_axes):
            x = values[a] if a < len(values) else None
            if x is None or not math.isfinite(x):
                out.append(float("nan"))  # a bad sample must not poison the state
                continue
            z = self._z[a]
            for i, (b0, b1, b2, a1, a2) in enumerate(self._sec):
                zi = z[i]
                y = b0 * x + zi[0]
                zi[0] = b1 * x - a1 * y + zi[1]
                zi[1] = b2 * x - a2 * y
                x = y
            out.append(x)
        return out

    def process_block(self, arr) -> np.ndarray:
        arr = np.asarray(arr, dtype=np.float64)
        return np.array([self.process(list(row)) for row in arr]).reshape(arr.shape[0], self.n_axes)


def frequency_response(config: dict, fs: float, freqs) -> np.ndarray:
    w = 2.0 * np.pi * np.asarray(freqs, dtype=np.float64) / fs
    z1 = np.exp(-1j * w)
    z2 = z1 * z1
    h = np.ones_like(z1)
    for b0, b1, b2, a1, a2 in sections(config, fs):
        h *= (b0 + b1 * z1 + b2 * z2) / (1.0 + a1 * z1 + a2 * z2)
    return np.abs(h)


def _smooth(f: np.ndarray, p: np.ndarray) -> np.ndarray:
    """Median over +-10 % in frequency (at least +-4 bins): follows the broad shape, ignores peaks."""
    out = np.empty_like(p)
    df = f[1] - f[0] if f.size > 1 else 1.0
    for i, fi in enumerate(f):
        half = max(0.1 * fi, 4 * df)
        m = (f >= fi - half) & (f <= fi + half)
        out[i] = np.median(p[m])
    return out


def suggest(f, psd, fs: float) -> dict:
    f = np.asarray(f, dtype=np.float64)
    p = np.asarray(psd, dtype=np.float64)
    nyq = 0.45 * fs
    band = (f > 0) & (f <= nyq) & np.isfinite(p)
    f, p = f[band], p[band]
    if f.size < 8:
        return {"lowpass": {"hz": round(min(max(0.5, 0.25 * fs), nyq), 2), "order": 4}, "notches": []}
    sm = _smooth(f, p)
    floor = float(np.median(p[f >= 0.7 * nyq])) if (f >= 0.7 * nyq).any() else float(np.median(p))
    above = f[sm > 2.0 * floor]
    cutoff = float(above.max()) if above.size else 0.5
    cutoff = min(max(cutoff, 0.5), nyq)
    notches: List[dict] = []
    ratio = p / np.maximum(sm, 1e-300)
    cand = [i for i in range(1, f.size - 1)
            if ratio[i] > 10.0 and p[i] >= p[i - 1] and p[i] >= p[i + 1] and 0.5 <= f[i] < cutoff]
    for i in sorted(cand, key=lambda i: -ratio[i]):
        if all(abs(f[i] - nt["hz"]) >= 1.0 for nt in notches):
            notches.append({"hz": round(float(f[i]), 2), "q": 10.0})
        if len(notches) == MAX_NOTCHES:
            break
    return {"lowpass": {"hz": round(cutoff, 2), "order": 4}, "notches": sorted(notches, key=lambda n: n["hz"])}
