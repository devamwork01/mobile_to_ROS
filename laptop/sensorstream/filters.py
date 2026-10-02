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


AXIS_NAMES = ("X", "Y", "Z")


def _plain(cfg) -> dict:
    return {"lowpass": (cfg or {}).get("lowpass") or None, "notches": list((cfg or {}).get("notches") or [])}


def normalise(config: dict) -> dict:
    """Stored/sent shape. A per-axis config keeps `axes` and mirrors axis X at the top level, so
    readers that predate per-axis filters still see a sensible (X) filter."""
    axes = config.get("axes") if isinstance(config, dict) else None
    if isinstance(axes, list) and axes:
        ax = [_plain(a) for a in axes]
        return {**ax[0], "axes": ax}
    return _plain(config)


def validate(config, fs: float) -> Tuple[bool, str]:
    if not isinstance(config, dict):
        return False, "Invalid filter configuration."
    axes = config.get("axes")
    if axes is not None:
        # Per-axis: every axis must be usable, or the whole filter is refused (never half-applied).
        if not isinstance(axes, list) or len(axes) != 3:
            return False, "Per-axis filters need X, Y and Z settings."
        for name, ax in zip(AXIS_NAMES, axes):
            ok, msg = (validate(ax, fs) if isinstance(ax, dict) and "axes" not in ax
                       else (False, "Invalid filter configuration."))
            if not ok:
                return False, f"{name}: {msg}"
        return True, "ok"
    nyq = 0.45 * fs
    lp = config.get("lowpass")
    notches = config.get("notches") or []
    if lp is not None and not isinstance(lp, dict):
        return False, "Invalid low-pass settings."
    if not isinstance(notches, list) or not all(isinstance(n, dict) for n in notches):
        return False, "Invalid notch settings."
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
        axes = config.get("axes")
        # One section list per axis: a per-axis config filters each axis with its own settings.
        self._secs = [sections(axes[a] if axes else config, fs) for a in range(self.n_axes)]
        self._z = [[[0.0, 0.0] for _ in self._secs[a]] for a in range(self.n_axes)]
        self._primed = [False] * self.n_axes

    def _prime(self, a: int, x: float) -> None:
        """Start axis `a` in steady state for input x, as if it had been x forever. A zeroed state
        would turn the first sample (e.g. 9.8 m/s^2 of gravity) into a step that rings broadband."""
        for i, (b0, b1, b2, a1, a2) in enumerate(self._secs[a]):
            y = x * (b0 + b1 + b2) / (1.0 + a1 + a2)
            z2 = b2 * x - a2 * y
            self._z[a][i] = [b1 * x - a1 * y + z2, z2]
            x = y

    def process(self, values) -> List[float]:
        out = []
        for a in range(self.n_axes):
            x = values[a] if a < len(values) else None
            if x is None or not math.isfinite(x):
                out.append(float("nan"))  # a bad sample must not poison the state
                continue
            if not self._primed[a]:
                self._prime(a, x)
                self._primed[a] = True
            z = self._z[a]
            for i, (b0, b1, b2, a1, a2) in enumerate(self._secs[a]):
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


def suggest_details(f, psd, fs: float) -> Tuple[dict, dict]:
    """suggest()'s configuration plus what it saw: the noise floor (PSD units, the median PSD in the
    top 30 % of the band) and each chosen notch's prominence (peak PSD / smoothed PSD), in the
    config's notch order."""
    f = np.asarray(f, dtype=np.float64)
    p = np.asarray(psd, dtype=np.float64)
    nyq = 0.45 * fs
    band = (f > 0) & (f <= nyq) & np.isfinite(p)
    f, p = f[band], p[band]
    if f.size < 8:
        return ({"lowpass": {"hz": round(min(max(0.5, 0.25 * fs), nyq), 2), "order": 4}, "notches": []},
                {"floor": None, "prominence": []})
    sm = _smooth(f, p)
    floor = float(np.median(p[f >= 0.7 * nyq])) if (f >= 0.7 * nyq).any() else float(np.median(p))
    above = f[sm > 2.0 * floor]
    cutoff = float(above.max()) if above.size else 0.5
    # Stay a little under the 0.45*fs limit (and round down): the server's rate estimate can differ
    # slightly from the one this spectrum came from, and the suggestion must always be applicable.
    cutoff = min(max(cutoff, 0.5), math.floor(0.43 * fs * 100) / 100)
    picked: List[Tuple[dict, float]] = []
    ratio = p / np.maximum(sm, 1e-300)
    cand = [i for i in range(1, f.size - 1)
            if ratio[i] > 10.0 and p[i] >= p[i - 1] and p[i] >= p[i + 1] and 0.5 <= f[i] < cutoff]
    for i in sorted(cand, key=lambda i: -ratio[i]):
        if all(abs(f[i] - nt["hz"]) >= 1.0 for nt, _ in picked):
            picked.append(({"hz": round(float(f[i]), 2), "q": 10.0}, float(ratio[i])))
        if len(picked) == MAX_NOTCHES:
            break
    picked.sort(key=lambda nr: nr[0]["hz"])
    return ({"lowpass": {"hz": cutoff, "order": 4}, "notches": [nt for nt, _ in picked]},
            {"floor": floor, "prominence": [r for _, r in picked]})


def suggest(f, psd, fs: float) -> dict:
    return suggest_details(f, psd, fs)[0]


RATE_READY_S = 1.0
RATE_DRIFT = 0.05
DRIFT_SAMPLES = 200  # the rate must stay off by > RATE_DRIFT this long before the chain is redesigned


def _shape_ok(config) -> bool:
    """Structural check without a sample rate (used when loading and before the rate is known)."""
    ok, _ = validate(config, 1e9)
    return ok


class _Rate:
    """Sample rate from sensor timestamps (EWMA of intervals). An isolated long interval is a pause
    in the stream, not a rate change, and is ignored; 3 long intervals in a row are a real change
    (e.g. the sampling period was changed on the phone) and reset the estimate."""

    __slots__ = ("first", "last", "ewma", "n", "long_run")

    def __init__(self):
        self.first = self.last = None
        self.ewma = None
        self.n = 0
        self.long_run = 0

    def observe(self, t_ns: int) -> None:
        if self.last is not None and t_ns < self.last - 1_000_000_000:
            # Sensor clock went back (phone rebooted, app restarted): start the estimate afresh.
            self.first = self.last = t_ns
            self.ewma = None
            self.n = 1
            self.long_run = 0
            return
        if self.last is not None and t_ns > self.last:
            dt = (t_ns - self.last) / 1e9
            if self.ewma is not None and dt > 5.0 * self.ewma:
                self.long_run += 1
                if self.long_run < 3:
                    self.last = t_ns
                    self.n += 1
                    return
                self.ewma = dt  # sustained: adopt the new interval
            else:
                self.long_run = 0
            self.ewma = dt if self.ewma is None else 0.98 * self.ewma + 0.02 * dt
        if self.first is None:
            self.first = t_ns
        self.last = max(t_ns, self.last or t_ns)
        self.n += 1

    def fs(self) -> Optional[float]:
        if self.ewma is None or self.n < 10 or (self.last - self.first) / 1e9 < RATE_READY_S:
            return None
        return 1.0 / self.ewma


class FilterBank:
    """Per-sensor filters on the live stream. Configs are keyed by sensor identity
    ("<type>:<catalogName>", like dashboard pins) so they survive reconnects; chains are per handle."""

    def __init__(self, path: Optional[str] = None):
        self._path = path
        self.configs: Dict[str, dict] = self._load()
        self._key_of: Dict[int, str] = {}
        self._type_of: Dict[int, int] = {}
        self._rates: Dict[int, _Rate] = {}
        self._chains: Dict[int, FilterChain] = {}
        self._pending_errors: List[Tuple[str, str]] = []
        self._bad: Dict[int, str] = {}                  # handle -> "key|config" already reported invalid
        self._drift: Dict[int, int] = {}                # handle -> consecutive samples off-rate

    # --- configuration -------------------------------------------------------------------------
    def _load(self) -> Dict[str, dict]:
        if not self._path or not os.path.isfile(self._path):
            return {}
        try:
            with open(self._path, encoding="utf-8") as fh:
                raw = json.load(fh)
        except (OSError, ValueError):
            return {}
        if not isinstance(raw, dict):
            return {}
        out = {}
        for k, v in raw.items():  # a hand-edited file must never stop the server from starting
            try:
                if isinstance(k, str) and _shape_ok(v):
                    out[k] = normalise(v)  # top level mirrors X, junk keys dropped (as set() stores)
            except Exception:
                continue
        return out

    def _save(self) -> None:
        if not self._path:
            return
        tmp = self._path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump(self.configs, fh, indent=2)
        os.replace(tmp, self._path)

    def set_catalog(self, sensors) -> None:
        for s in sensors or []:
            h, ty, name = s.get("handle"), s.get("type"), s.get("name")
            if h is None or ty is None or not name:
                continue
            h = int(h)
            self._key_of[h] = f"{ty}:{name}"
            self._type_of[h] = int(ty)
            # A (re)connecting phone may run at a different rate or reuse the handle for another
            # sensor: forget everything learned about this handle.
            for d in (self._chains, self._rates, self._drift, self._bad):
                d.pop(h, None)

    def bind(self, handle: int, key: str) -> None:
        if self._key_of.get(int(handle)) != key:
            self._key_of[int(handle)] = key
            self._chains.pop(int(handle), None)

    def key_of(self, handle: int) -> Optional[str]:
        return self._key_of.get(int(handle))

    def _type_from_key(self, key: str) -> Optional[int]:
        try:
            return int(key.split(":", 1)[0])
        except ValueError:
            return None

    def set(self, key: str, config: dict, handle: Optional[int] = None) -> Tuple[bool, str]:
        ty = self._type_from_key(key)
        if ty is None or ty in EXCLUDED_TYPES:
            return False, "This sensor can't be filtered (orientation and on-change sensors are excluded)."
        if handle is not None:
            self.bind(handle, key)
        fs = None
        for h, k in self._key_of.items():
            if k == key and self.fs(h):
                fs = self.fs(h)
        ok, msg = validate(config, fs) if fs else (_shape_ok(config), "Invalid filter configuration.")
        if not ok:
            return False, msg
        self.configs[key] = normalise(config)
        for h, k in list(self._key_of.items()):
            if k == key:
                self._chains.pop(h, None)
                self._bad.pop(h, None)
        self._save()
        return True, "ok"

    def clear(self, key: str) -> None:
        if self.configs.pop(key, None) is not None:
            self._save()
        for h, k in list(self._key_of.items()):
            if k == key:
                self._chains.pop(h, None)
                self._bad.pop(h, None)

    def fs(self, handle: int) -> Optional[float]:
        r = self._rates.get(int(handle))
        return r.fs() if r else None

    def errors(self) -> List[Tuple[str, str]]:
        out, self._pending_errors = self._pending_errors, []
        return out

    def snapshot(self) -> dict:
        return {"kind": "filters", "configs": dict(self.configs)}

    # --- processing ----------------------------------------------------------------------------
    def _chain_for(self, handle: int, n_axes: int) -> Optional[FilterChain]:
        key = self._key_of.get(handle)
        cfg = self.configs.get(key) if key else None
        fs = self.fs(handle)
        if cfg is None or fs is None or self._type_of.get(handle, self._type_from_key(key)) in EXCLUDED_TYPES:
            return None
        ch = self._chains.get(handle)
        if ch is not None and ch.n_axes == n_axes:
            # Timestamp jitter moves the estimate around; only a sustained change redesigns the
            # filter (a rebuild restarts its state).
            if abs(fs - ch.fs) / ch.fs <= RATE_DRIFT:
                self._drift[handle] = 0
                return ch
            self._drift[handle] = self._drift.get(handle, 0) + 1
            if self._drift[handle] < DRIFT_SAMPLES:
                return ch
        self._drift[handle] = 0
        ok, msg = validate(cfg, fs)
        if not ok:
            stamp = f"{key}|{json.dumps(cfg, sort_keys=True)}"
            if self._bad.get(handle) != stamp:  # once per config, however the rate wanders
                self._bad[handle] = stamp
                self._pending_errors.append((key, msg))
            self._chains.pop(handle, None)
            return None
        self._bad.pop(handle, None)
        ch = self._chains[handle] = FilterChain(cfg, fs, n_axes)
        return ch

    def process(self, dg) -> Dict[Tuple[int, int], List[float]]:
        out: Dict[Tuple[int, int], List[float]] = {}
        for r in dg.records:
            h = r.sensor_handle
            self._type_of.setdefault(h, r.sensor_type)
            rate = self._rates.get(h)
            if rate is None:
                rate = self._rates[h] = _Rate()
            rate.observe(r.t_sensor_ns)
            if not self.configs:
                continue
            n_axes = min(len(r.values), 3)
            ch = self._chain_for(h, n_axes)
            if ch is not None:
                out[(h, r.seq)] = ch.process(list(r.values[:n_axes]))
        return out
