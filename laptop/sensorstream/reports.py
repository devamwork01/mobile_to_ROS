"""Test-run / recording reports: analyse a lossless .ssbin into <base>.report.json.

Reports are derived data: any recording can be (re)analysed, so a report is reproducible from
its recording. Samples are deduplicated by (handle, seq) - a sample can be in the file twice
when backfill resent it - and time-sorted before analysis.
"""

from __future__ import annotations

import glob
import json
import os
from array import array
from datetime import datetime, timezone
from typing import Dict, List, Optional

import numpy as np

from . import analysis
from . import protocol as p
from .logging_sink import read_frames
from .recordings import _load_meta

_SSBIN = ".ssbin"
_REPORT = ".report.json"
_MAX_VALUES = 4  # analysis uses at most x, y, z, w


def valid_id(rid: str) -> bool:
    return bool(rid) and rid == os.path.basename(rid) and rid not in (".", "..") and "/" not in rid and "\\" not in rid


def load_samples(ssbin: str) -> Dict[int, dict]:
    """Per-handle samples, time-sorted, with true duplicates removed.

    A duplicate is the same (seq, t_sensor_ns) pair - what backfill produces when it resends a
    sample the live stream also delivered. seq alone is not enough: the phone restarts it at 0
    when a sensor is re-toggled or the app reconnects. Decoded into compact typed arrays (not
    per-sample Python objects) so a 30-minute multi-sensor recording stays a few hundred MB.
    """
    raw: Dict[int, dict] = {}
    pad = [float("nan")] * _MAX_VALUES
    for _t_recv, data in read_frames(ssbin):
        try:
            dg = p.decode_datagram(data)
        except p.ProtocolError:
            continue
        for r in dg.records:
            s = raw.get(r.sensor_handle)
            if s is None:
                s = raw[r.sensor_handle] = {"type": r.sensor_type, "t": array("q"), "seq": array("q"),
                                            "v": array("d"), "k": 1}
            vals = list(r.values[:_MAX_VALUES])
            s["t"].append(r.t_sensor_ns)
            s["seq"].append(r.seq)
            s["v"].extend(vals + pad[len(vals):])
            if len(vals) > s["k"]:
                s["k"] = len(vals)
    out = {}
    for h, s in raw.items():
        t = np.frombuffer(s["t"], dtype=np.int64)
        seq = np.frombuffer(s["seq"], dtype=np.int64)
        v = np.frombuffer(s["v"], dtype=np.float64).reshape(-1, _MAX_VALUES)[:, : s["k"]]
        _, first = np.unique(np.stack([seq, t], axis=1), axis=0, return_index=True)
        first = first[np.argsort(t[first], kind="stable")]
        out[h] = {"type": s["type"], "t": t[first].copy(), "v": v[first].copy(), "seq": seq[first].copy()}
    return out


def analyse_recording(ssbin: str, preset: str = "capture", start_ns: Optional[int] = None,
                      seconds: Optional[float] = None, complete: bool = True,
                      requested_s: Optional[float] = None) -> dict:
    base = ssbin[: -len(_SSBIN)]
    meta = _load_meta(base) or {}
    catalog = {s.get("handle"): s for s in (meta.get("sensors") or [])}
    samples = load_samples(ssbin)
    sensors: List[dict] = []
    duration = 0.0
    for h in sorted(samples):
        s = samples[h]
        t, v, seq = s["t"], s["v"], s["seq"]
        if start_ns is not None:
            end = start_ns + int((seconds or 0) * 1e9) if seconds else None
            m = t >= start_ns
            if end is not None:
                m &= t <= end
            t, v, seq = t[m], v[m], seq[m]
        if t.size == 0:
            continue
        span = (t[-1] - t[0]) / 1e9
        duration = max(duration, span)
        info = catalog.get(h, {})
        sensors.append(analysis.analyse_sensor(
            {"handle": h, "type": s["type"], "name": info.get("name"), "unit": info.get("units"),
             "t": t, "v": v, "seq": seq}, preset, span))
    report = {
        "id": os.path.basename(base),
        "created": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "preset": preset,
        "duration_s": round(duration, 3),
        "requested_s": requested_s,
        "complete": bool(complete),
        "device": {"model": meta.get("model"), "android": meta.get("android")},
        "sensors": sensors,
    }
    with open(base + _REPORT, "w", encoding="utf-8") as fh:
        json.dump(report, fh)
    return report


def list_reports(log_dir: str) -> List[dict]:
    out = []
    for path in glob.glob(os.path.join(log_dir, "*" + _REPORT)):
        try:
            with open(path, encoding="utf-8") as fh:
                r = json.load(fh)
        except (OSError, ValueError):
            continue
        out.append({k: r.get(k) for k in ("id", "created", "preset", "duration_s", "requested_s", "complete", "device")}
                   | {"sensor_count": len(r.get("sensors") or [])})
    out.sort(key=lambda r: r.get("created") or "", reverse=True)
    return out


def load_report(log_dir: str, rid: str) -> Optional[dict]:
    if not valid_id(rid):
        return None
    path = os.path.join(log_dir, rid + _REPORT)
    try:
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return None
