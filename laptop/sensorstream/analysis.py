"""Sensor statistics for SensorStream Insights.

Pure numpy, no I/O: shared by the live InsightsSink (rolling windows) and by offline test-run
reports (whole recordings). Everything is computed on the full-rate stream and is robust to
non-finite values and to too little data (fields become None rather than raising).
"""

from __future__ import annotations

import math
from typing import Dict, List, Optional

import numpy as np

from .seqloss import SeqLoss

GRAVITY = 9.80665
MIN_SAMPLES = 50
ALLAN_MIN_S = 120.0
PSD_MIN_RATE = 5.0
ACCEL_TYPES = {1, 35}          # accelerometer, accelerometer (uncalibrated)
GYRO_TYPES = {4, 16}           # gyroscope, gyroscope (uncalibrated)
ORIENTATION_TYPES = {11, 15, 20}
ON_CHANGE_TYPES = {5, 8, 17, 18, 19}  # light, proximity, significant motion, step detector/counter
AXES = ("x", "y", "z", "w")


def _num(x) -> Optional[float]:
    if x is None:
        return None
    x = float(x)
    return x if math.isfinite(x) else None


def _finite(v) -> np.ndarray:
    v = np.asarray(v, dtype=np.float64)
    return v[np.isfinite(v)]


def axis_stats(v) -> Dict[str, Optional[float]]:
    v = _finite(v)
    if v.size < 2:
        return {"mean": None, "std": None, "p2p": None}
    return {"mean": _num(v.mean()), "std": _num(v.std(ddof=1)), "p2p": _num(v.max() - v.min())}


def rate_stats(t_ns) -> Dict[str, Optional[float]]:
    t = np.asarray(t_ns, dtype=np.float64) / 1e9
    if t.size < 2:
        return {"rate_hz": None, "jitter_ms": None, "gaps": 0}
    dt = np.diff(t)
    dt = dt[dt > 0]
    span = t[-1] - t[0]
    if dt.size == 0 or span <= 0:
        return {"rate_hz": None, "jitter_ms": None, "gaps": 0}
    med = float(np.median(dt))
    return {
        "rate_hz": _num((t.size - 1) / span),
        "jitter_ms": _num(dt.std() * 1e3) if dt.size > 1 else 0.0,
        "gaps": int((dt > 3 * med).sum()),
    }


def welch_psd(v, fs: float):
    """One-sided Welch PSD (Hann, 50 % overlap, per-segment mean removed), units^2/Hz."""
    v = _finite(v)
    n = v.size
    if n < 16 or not fs or fs <= 0:
        return np.array([]), np.array([])
    seg = min(n, 2 ** int(math.floor(math.log2(max(fs * 4.0, 16.0)))))
    step = max(1, seg // 2)
    win = np.hanning(seg)
    scale = fs * float((win ** 2).sum())
    acc = np.zeros(seg // 2 + 1)
    k = 0
    for s in range(0, n - seg + 1, step):
        x = v[s:s + seg]
        acc += np.abs(np.fft.rfft((x - x.mean()) * win)) ** 2 / scale
        k += 1
    psd = acc / max(k, 1)
    if seg % 2 == 0:
        psd[1:-1] *= 2.0
    else:
        psd[1:] *= 2.0
    return np.fft.rfftfreq(seg, 1.0 / fs), psd


def noise_density(f, psd, fs: float) -> Optional[float]:
    """sqrt(median one-sided PSD) over [1 Hz, 0.4*fs] - the flat white-noise band."""
    f = np.asarray(f)
    psd = np.asarray(psd)
    if f.size == 0 or not fs:
        return None
    band = (f >= 1.0) & (f <= 0.4 * fs)
    if not band.any():
        return None
    return _num(math.sqrt(float(np.median(psd[band]))))


def psd_points(f, psd, n: int = 200) -> Dict[str, List[float]]:
    """Log-spaced bins (f >= 0.1 Hz), mean PSD per bin, for compact transmission/plotting."""
    f = np.asarray(f)
    psd = np.asarray(psd)
    keep = f >= 0.1
    f, psd = f[keep], psd[keep]
    if f.size == 0:
        return {"f": [], "psd": []}
    if f.size <= n:
        return {"f": [float(x) for x in f], "psd": [float(x) for x in psd]}
    edges = np.logspace(math.log10(f[0]), math.log10(f[-1]) + 1e-9, n + 1)
    idx = np.digitize(f, edges) - 1
    of, op = [], []
    for b in range(n):
        m = idx == b
        if m.any():
            of.append(float(f[m].mean()))
            op.append(float(psd[m].mean()))
    return {"f": of, "psd": op}


def drift_per_min(t_ns, v) -> Optional[float]:
    t = np.asarray(t_ns, dtype=np.float64)
    v = np.asarray(v, dtype=np.float64)
    m = np.isfinite(v)
    t, v = t[m], v[m]
    if v.size < 2 or t[-1] == t[0]:
        return None
    slope = np.polyfit((t - t[0]) / 1e9, v, 1)[0]
    return _num(slope * 60.0)


def allan_deviation(v, fs: float, n_taus: int = 40) -> List[List[float]]:
    """Overlapping Allan deviation at ~n_taus log-spaced tau from 1/fs to T/9."""
    v = _finite(v)
    n = v.size
    if n < 18 or not fs or fs <= 0:
        return []
    tau0 = 1.0 / fs
    m_max = n // 9
    if m_max < 1:
        return []
    ms = np.unique(np.logspace(0, math.log10(m_max), n_taus).astype(np.int64))
    theta = np.concatenate([[0.0], np.cumsum(v)]) * tau0
    out = []
    for m in ms:
        d = theta[2 * m:] - 2.0 * theta[m:-m] + theta[:-2 * m]
        tau = m * tau0
        avar = float((d ** 2).sum()) / (2.0 * tau * tau * d.size)
        out.append([float(tau), math.sqrt(avar)])
    return out


def adev_points(adev) -> Dict[str, Optional[float]]:
    """Random walk = sigma at tau = 1 s (log-log interpolation); bias instability = curve minimum."""
    if not adev:
        return {"random_walk": None, "bias_instability": None, "bi_tau": None}
    taus = np.array([p[0] for p in adev])
    sig = np.array([p[1] for p in adev])
    rw = None
    if taus[0] <= 1.0 <= taus[-1]:
        rw = float(10 ** np.interp(0.0, np.log10(taus), np.log10(sig)))
    i = int(np.argmin(sig))
    return {"random_walk": _num(rw), "bias_instability": _num(sig[i]), "bi_tau": _num(taus[i])}


def _axes_count(type_: int, k: int) -> int:
    if k <= 1:
        return 1
    return min(k, 4) if type_ in ORIENTATION_TYPES else min(k, 3)


def _lost(seq) -> int:
    loss = SeqLoss()
    for s in np.asarray(seq, dtype=np.int64):
        loss.observe(int(s))
    return int(loss.lost)


def _axis_report(t, v, fs, spectral: bool, bias) -> dict:
    s = axis_stats(v)
    nd = None
    psd = {"f": [], "psd": []}
    if spectral:
        f, p = welch_psd(v, fs)
        nd = noise_density(f, p, fs)
        psd = psd_points(f, p)
    return {**s, "bias": _num(bias), "noise_density": nd, "drift_per_min": drift_per_min(t, v), "psd": psd}


def analyse_sensor(sensor: dict, preset: str, duration_s: float) -> dict:
    """Full report for one sensor. `sensor["t"]` must be sorted and deduplicated."""
    type_ = int(sensor["type"])
    t = np.asarray(sensor["t"], dtype=np.int64)
    vals = np.asarray(sensor["v"], dtype=np.float64)
    if vals.ndim == 1:
        vals = vals[:, None]
    n = int(t.size)
    k = _axes_count(type_, vals.shape[1] if vals.size else 1)
    names = AXES[:k]
    base = {"handle": sensor["handle"], "type": type_, "name": sensor.get("name"),
            "unit": sensor.get("unit"), "samples": n}
    if n < MIN_SAMPLES:
        empty = {"mean": None, "std": None, "p2p": None, "bias": None, "noise_density": None,
                 "drift_per_min": None, "psd": {"f": [], "psd": []}}
        rep = {**base, "insufficient": True, "axes": {a: dict(empty) for a in names},
               "rate": {"rate_hz": None, "jitter_ms": None, "gaps": 0, "lost": 0}}
        if k == 3:
            rep["magnitude"] = dict(empty)
        return rep

    rate = rate_stats(t)
    fs = rate["rate_hz"] or 0.0
    spectral = type_ not in ON_CHANGE_TYPES and fs >= PSD_MIN_RATE
    still = preset == "still"
    axes = {}
    for i, name in enumerate(names):
        v = vals[:, i]
        bias = float(np.nanmean(v)) if (still and type_ in GYRO_TYPES) else None
        axes[name] = _axis_report(t, v, fs, spectral, bias)
    rep = {**base, "insufficient": False, "axes": axes, "rate": {**rate, "lost": _lost(sensor["seq"])}}
    if k == 3:
        mag = np.linalg.norm(vals[:, :3], axis=1)
        mbias = float(np.nanmean(mag)) - GRAVITY if (still and type_ in ACCEL_TYPES) else None
        rep["magnitude"] = _axis_report(t, mag, fs, spectral, mbias)
    if spectral and duration_s >= ALLAN_MIN_S and type_ in (ACCEL_TYPES | GYRO_TYPES):
        rep["adev"] = {name: allan_deviation(vals[:, i], fs) for i, name in enumerate(names)}
        rep["adev_points"] = {name: adev_points(rep["adev"][name]) for name in names}
    return rep
