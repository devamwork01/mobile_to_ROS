"""--filter: per-axis filters for the accelerometer, gyroscope and magnetometer, tuned from each
axis's own spectrum, plus a report of what each spectrum showed.

Findings per axis come from the same Welch spectrum the suggestion used: the noise floor, the
low-pass and its delay, each notch with how far its peak stood out, and sigma before / after the
filter (integrated from the spectrum and the filter's frequency response).
"""

from __future__ import annotations

import json
import math
import os
import time
from datetime import datetime, timezone
from typing import Callable, Dict, List, Optional

import numpy as np

from . import analysis as an
from .filters import frequency_response, suggest_details

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


CAPTURE_MIN_S = 9.0  # a guided capture needs this much data (the phone marks 10 s)


def capture_problem(t) -> Optional[str]:
    """Why a capture window cannot be tuned from, or None if it can."""
    t = np.asarray(t, dtype=np.int64)
    if t.size < 2 or (t[-1] - t[0]) / 1e9 < CAPTURE_MIN_S:
        return "not enough data in the capture"
    if int(np.diff(t).max()) > 1_000_000_000:
        return "data gap during the capture"
    return None


def spectra(t, v):
    """(f, fs, [psd_x, psd_y, psd_z]) of a capture: the same Welch PSDs the live suggestion uses."""
    fs = an.rate_stats(t)["rate_hz"]
    f, per = None, []
    for i in range(3):
        ff, pp = an.welch_psd(np.asarray(v)[:, i], fs)
        f = ff
        per.append(pp)
    return f, fs, per


def _cfg_text(c: dict) -> str:
    parts = []
    lp = c.get("lowpass")
    if lp:
        parts.append(f"LP {lp['hz']:g} Hz")
    parts += [f"notch {n['hz']:g} Hz" for n in c.get("notches") or []]
    return " + ".join(parts) or "none"


def summary(cfgs) -> str:
    """Per-axis config text, axes with the same filter grouped: 'X/Y LP 0.5 Hz | Z LP 5 Hz + notch 8 Hz'."""
    groups: Dict[str, List[str]] = {}
    for ax, c in zip(AXIS, cfgs):
        groups.setdefault(_cfg_text(c), []).append(ax)
    return " | ".join(f"{'/'.join(axes)} {text}" for text, axes in groups.items())


GAP_NS = 1_000_000_000      # a pause longer than this starts a new run
RATE_CHANGE = 0.05          # the short-term interval moving this far from the run's mean...
RATE_SUSTAIN = 20           # ...for this many samples in a row is a new sampling rate: a new run
_FAST = 0.2                 # EWMA weight of the short-term interval
HOLE = 3.0                  # an interval > 3x the run's mean is lost samples (a hole), not a new rate...


class RunTracker:
    """Per handle, when its current unbroken run of samples started (sensor time). A gap > 1 s, or
    the sample interval moving > 5 % from the run's rate for 20 samples in a row, starts a new run.
    A single hole of lost samples (Wi-Fi) is not a rate change; 20 holes in a row are (a big slowdown).
    The run is what --filter waits on: 10 s of one run is 10 s of clean, single-rate data."""

    def __init__(self) -> None:
        # handle -> [start, last, n_intervals, mean_interval, fast_interval, off_count, hole_count]
        self._s: Dict[int, list] = {}

    def reset(self) -> None:
        self._s.clear()

    def observe(self, handle: int, t_ns: int) -> None:
        s = self._s.get(handle)
        if s is None:
            self._s[handle] = [t_ns, t_ns, 0, 0.0, 0.0, 0, 0]
            return
        start, last, n, mean, fast, off, holes = s
        dt = t_ns - last
        if dt <= 0:          # duplicate or reordered sample
            return
        if dt > GAP_NS:
            self._s[handle] = [t_ns, t_ns, 0, 0.0, 0.0, 0, 0]
            return
        if n > 0 and dt > HOLE * mean:
            holes += 1
            if holes >= RATE_SUSTAIN:
                self._s[handle] = [t_ns, t_ns, 0, 0.0, 0.0, 0, 0]
            else:
                self._s[handle] = [start, t_ns, n, mean, fast, off, holes]
            return
        holes = 0
        if n == 0:
            mean = fast = float(dt)
        else:
            fast += _FAST * (dt - fast)
            if abs(fast - mean) > RATE_CHANGE * mean:
                off += 1
                if off >= RATE_SUSTAIN:
                    self._s[handle] = [t_ns, t_ns, 0, 0.0, 0.0, 0, 0]
                    return
            else:
                off = 0
                mean += (dt - mean) / (n + 1)
        self._s[handle] = [start, t_ns, n + 1, mean, fast, off, holes]

    def run_seconds(self, handle: int) -> float:
        s = self._s.get(handle)
        return 0.0 if s is None else (s[1] - s[0]) / 1e9


RUN_S = 10.0  # seconds of one unbroken run before a sensor is tuned (= the insights window)
RATE_AGREE = 0.05  # the spectrum's rate and FilterBank's must agree this well before tuning
WAIT_NOTICE_S = 40.0  # a sensor streaming this long without being tuned gets one "still waiting" line
# Guided tuning (a phone with the "tune" capability): prompt, phone-marked capture, result.
GUIDE_MIN_RUN_S = 2.0   # a sensor is in the prompt once it has streamed this long
FALLBACK_S = 60.0       # silent tuning once a phone has connected (old app, abandoned capture)
LEAD_S, SETTLE_S, CAPTURE_S = 3, 0.5, 10
PROMPT_SLACK_S = 20.0   # a prompt without a capture this long after its end is abandoned
STREAMING_S = 2.0       # a sensor seen within this many seconds is streaming


class ReportStore:
    """Latest spectrum findings per sensor key. `records` (applied and merely suggested) are replayed
    to dashboards that connect later; `applied` ones are written to filter_report.json."""

    def __init__(self, path: Optional[str]) -> None:
        self.path = path
        self.records: Dict[str, dict] = {}
        self.device: Optional[dict] = None
        self.applied: Dict[str, dict] = self._load()

    def _load(self) -> Dict[str, dict]:
        if not self.path:
            return {}
        try:
            with open(self.path, encoding="utf-8") as fh:
                doc = json.load(fh)
        except (OSError, ValueError):
            return {}
        sensors = doc.get("sensors") if isinstance(doc, dict) else None
        if not isinstance(sensors, dict):
            return {}
        return {k: v for k, v in sensors.items() if isinstance(v, dict)}

    def add(self, record: dict, persist: bool) -> None:
        self.records[record["key"]] = record
        if persist:
            self.applied[record["key"]] = record
            self._write()

    def messages(self) -> List[dict]:
        return [{"kind": "filter_report", "key": k, "report": r} for k, r in self.records.items()]

    def _write(self) -> None:
        if not self.path:
            return
        d = os.path.dirname(self.path)
        if d:
            os.makedirs(d, exist_ok=True)
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump({"updated": now_iso(), "device": self.device, "sensors": self.applied}, fh, indent=2)
        os.replace(tmp, self.path)


class AutoFilter:
    """Tunes each streaming accelerometer / gyroscope / magnetometer once per phone connection, as
    soon as its current unbroken run is 10 s old: one filter per axis from that axis's spectrum,
    applied through FilterBank (overwriting a saved filter) exactly like a dashboard Apply."""

    def __init__(self, bank, insights, reports: ReportStore, broadcast: Callable[[dict], None],
                 on_change: Optional[Callable[[], None]] = None, print_fn: Callable[[str], None] = print,
                 now: Callable[[], str] = now_iso, send_phone: Optional[Callable[[dict], None]] = None,
                 clock: Callable[[], float] = time.monotonic) -> None:
        self._bank, self._insights, self._reports = bank, insights, reports
        self._broadcast, self._on_change, self._print, self._now = broadcast, on_change, print_fn, now
        self._send_phone, self._clock = send_phone, clock
        self._runs = RunTracker()
        self._types: Dict[int, int] = {}
        self._tuned: set = set()
        self._failed: set = set()
        self._span: Dict[int, list] = {}   # handle -> [first, last] sensor time seen this connection
        self._why: Dict[int, str] = {}     # handle -> why the last tuning attempt waited
        self._noticed: set = set()
        self._phone = False          # a phone said hello (then silent tuning waits FALLBACK_S)
        self._guided = False         # ...and it runs guided captures
        self._pending: Optional[dict] = None  # the prompt in flight: id, handles, deadline
        self._next_id = 1
        self._prompted = False       # a prompt was sent this connection (later ones are "new_sensor")
        self._attempted: set = set() # handles whose guided capture was analysed (no silent tuning after)
        self._abandoned: set = set() # handles of an abandoned/cancelled capture (silent tuning at 60 s)
        self._retune = False
        self._seen: Dict[int, float] = {}

    def on_datagram(self, dg) -> None:
        for r in dg.records:
            if r.sensor_type in AUTO_TYPES:
                self._types[r.sensor_handle] = r.sensor_type
                self._runs.observe(r.sensor_handle, r.t_sensor_ns)
                self._seen[r.sensor_handle] = self._clock()
                sp = self._span.setdefault(r.sensor_handle, [r.t_sensor_ns, r.t_sensor_ns])
                sp[1] = max(sp[1], r.t_sensor_ns)

    def on_phone_connected(self, model: Optional[str] = None, android: Optional[str] = None, caps=()) -> None:
        """A (re)connect re-tunes everything from fresh data."""
        self._tuned.clear()
        self._failed.clear()
        self._runs.reset()
        self._types.clear()
        self._span.clear()
        self._why.clear()
        self._noticed.clear()
        self._reports.device = {"model": model, "android": android} if model else None
        self._phone = True
        self._guided = "tune" in (caps or ()) and self._send_phone is not None
        self._pending = None
        self._prompted = False
        self._attempted.clear()
        self._abandoned.clear()
        self._retune = False
        self._seen.clear()

    def tick(self) -> int:
        n = 0
        if self._guided:
            self._guided_tick()
        wait = FALLBACK_S if self._phone else RUN_S
        pending = set(self._pending["handles"]) if self._pending else set()
        for h, ty in list(self._types.items()):
            if (h in self._tuned or h in self._failed or h in self._attempted or h in pending
                    or self._runs.run_seconds(h) < wait):
                continue
            if self._tune(h, ty):
                n += 1
        if not self._guided:
            self._notice_waiting()
        return n

    def _name(self, h: int) -> str:
        key = self._bank.key_of(h) or f"{self._types.get(h, 0)}:{GENERIC.get(self._types.get(h), 'Sensor')}"
        return key.split(":", 1)[1] if ":" in key else key

    def _send(self, obj: dict) -> None:
        if self._send_phone is not None:
            self._send_phone(obj)

    def _guided_tick(self) -> None:
        now = self._clock()
        p = self._pending
        if p is not None:
            if now <= p["deadline"]:
                return
            self._abandoned.update(p["handles"])
            self._pending = None
            self._print("[filter] guided tuning: no capture from the phone - those sensors tune automatically at 60 s")
            return
        streaming = [h for h in self._types
                     if now - self._seen.get(h, -1e18) < STREAMING_S and self._runs.run_seconds(h) >= GUIDE_MIN_RUN_S]
        if self._retune:
            handles, reason = streaming, "retune"
        else:
            handles = [h for h in streaming if h not in self._tuned and h not in self._failed
                       and h not in self._attempted and h not in self._abandoned]
            reason = "new_sensor" if self._prompted else "connect"
        self._retune = False
        if not handles:
            return
        self._prompted = True
        pid, self._next_id = self._next_id, self._next_id + 1
        self._pending = {"id": pid, "handles": handles,
                         "deadline": now + LEAD_S + SETTLE_S + CAPTURE_S + PROMPT_SLACK_S}
        names = [self._name(h) for h in handles]
        self._send({"type": "tune_prompt", "id": pid, "reason": reason, "attention": reason == "new_sensor",
                    "lead_s": LEAD_S, "capture_s": CAPTURE_S,
                    "sensors": [{"handle": h, "name": nm} for h, nm in zip(handles, names)]})
        self._print(f"[filter] guided tuning ({reason}): prompted {', '.join(names)}".encode("ascii", "replace").decode())

    def on_tune_request(self) -> None:
        """The phone's Re-tune button: the next tick prompts every streaming sensor again."""
        if self._guided:
            self._retune = True

    def on_tune_window(self, msg: dict) -> None:
        p = self._pending
        if p is None or msg.get("id") != p["id"]:
            return  # late, duplicated or from before a reconnect
        self._pending = None
        if msg.get("cancelled"):
            self._abandoned.update(p["handles"])
            self._print("[filter] guided tuning cancelled on the phone - those sensors tune automatically at 60 s")
            return
        windows = {}
        for w in msg.get("windows") or []:
            try:
                windows[int(w["handle"])] = (int(w["from_ns"]), int(w["to_ns"]))
            except (TypeError, ValueError, KeyError):
                continue
        results = []
        for h in p["handles"]:
            ty, cfgs = self._types.get(h), None
            if h not in windows:
                why = "no capture from the phone"
            else:
                t, v = self._insights.samples_between(h, *windows[h])
                why = capture_problem(t)
                if why is None and v.shape[1] < 3:
                    why = "not a 3-axis sensor"
                if why is None:
                    f, fs, per = spectra(t, v)
                    why, cfgs, _ = self._apply(h, ty, f, fs, per)
            self._attempted.add(h)
            results.append({"handle": h, "name": self._name(h), "ok": why is None,
                            "summary": summary(cfgs) if cfgs else None, "message": why})
        self._send({"type": "tune_result", "id": p["id"], "sensors": results})

    def _tune(self, h: int, ty: int) -> bool:
        src = self._insights.suggest(h)
        if src is None or len(src) < 4 or len(src[3]) < 3:
            self._why[h] = "not enough live data yet"
            return False  # retried on the next tick
        f, _, fs, per = src
        why, _, refused = self._apply(h, ty, f, fs, list(per)[:3])
        if refused:
            self._failed.add(h)
        elif why:
            self._why[h] = why
        return why is None

    def _apply(self, h: int, ty: int, f, fs: float, per):
        """Tune handle h from per-axis PSDs: (why_not | None, configs | None, refused_by_FilterBank)."""
        bank_fs = self._bank.fs(h)
        if bank_fs and abs(bank_fs - fs) > RATE_AGREE * bank_fs:
            return f"the rate estimates disagree (spectrum {fs:.1f} Hz, filter {bank_fs:.1f} Hz)", None, False
        found = [suggest_details(f, p, fs) for p in per]
        cfgs = [c for c, _ in found]
        key = self._bank.key_of(h) or f"{ty}:{GENERIC[ty]}"
        ok, why = self._bank.set(key, {**cfgs[0], "axes": cfgs}, handle=h)
        if not ok:
            self._print(f"[filter] {key}: not applied - {why}".encode("ascii", "replace").decode())
            self._broadcast({"kind": "filter_error", "key": key, "message": why, "running": key in self._bank.configs})
            return why, None, True
        self._tuned.add(h)
        self._broadcast(self._bank.snapshot())
        if self._on_change:
            self._on_change()
        rec = sensor_report(key, h, ty, fs, f, per, cfgs, [d for _, d in found], self._now())
        self._reports.add(rec, persist=True)
        self._broadcast({"kind": "filter_report", "key": key, "report": rec})
        self._print(format_report(rec))
        return None, cfgs, False

    def _notice_waiting(self) -> None:
        """One line per sensor that has streamed WAIT_NOTICE_S without being tuned, saying why."""
        for h, ty in self._types.items():
            if h in self._tuned or h in self._failed or h in self._noticed:
                continue
            first, last = self._span.get(h, (0, 0))
            if (last - first) / 1e9 < WAIT_NOTICE_S:
                continue
            self._noticed.add(h)
            why = (self._why.get(h) if self._runs.run_seconds(h) >= RUN_S
                   else "no unbroken 10 s run of data yet (gaps or irregular timestamps)")
            key = self._bank.key_of(h) or f"{ty}:{GENERIC[ty]}"
            self._print(f"[filter] {key}: still waiting - {why}".encode("ascii", "replace").decode())
