"""Read-only query surface over recorded ``.ssbin`` sessions (ADR-001).

The browser stays thin: instead of downloading a whole recording, it asks the
server to LIST recordings and to return **aggregated buckets** for a signal over a
time range. Aggregation keeps ``min``/``max``/``first``/``last``/``avg`` per bucket
so transient spikes survive downsampling (never a bare average).

Pure filesystem + decode work — safe to call from the dashboard HTTP worker threads
(it never touches the asyncio loop's mutable state).
"""

from __future__ import annotations

import glob
import json
import os
from typing import List, Optional

from . import protocol as p
from .logging_sink import FRAME, LOG_MAGIC, read_frames

_SSBIN = ".ssbin"


def _base_of(ssbin_path: str) -> str:
    return ssbin_path[: -len(_SSBIN)]


def _load_meta(base: str) -> Optional[dict]:
    path = base + ".meta.json"
    if not os.path.isfile(path):
        return None
    try:
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return None


def _scan_frames(path: str):
    """Fast header-only pass: (n_frames, first_t_recv_ns, last_t_recv_ns). Seeks past payloads."""
    n = 0
    first = last = None
    with open(path, "rb") as fh:
        if fh.read(len(LOG_MAGIC)) != LOG_MAGIC:
            raise ValueError("not an SSLOG file")
        while True:
            hdr = fh.read(FRAME.size)
            if len(hdr) < FRAME.size:
                break
            t_recv, length = FRAME.unpack(hdr)
            if first is None:
                first = t_recv
            last = t_recv
            n += 1
            fh.seek(length, 1)
    return n, first, last


def _peek_sensors(path: str, meta: Optional[dict], max_frames: int = 1000) -> List[dict]:
    """Distinct sensors recorded, named from the meta catalog when available.

    The phone sends one record per datagram, so a single datagram shows only one sensor;
    scan a bounded window of datagrams (cheap) to enumerate the full active set.
    """
    catalog = {}
    if meta:
        for s in meta.get("sensors", []) or []:
            catalog[s.get("handle")] = s
    found: dict = {}
    n = 0
    for _t_recv, data in read_frames(path):
        try:
            dg = p.decode_datagram(data)
        except p.ProtocolError:
            break
        for r in dg.records:
            if r.sensor_handle not in found:
                info = catalog.get(r.sensor_handle, {})
                found[r.sensor_handle] = {
                    "handle": r.sensor_handle,
                    "type": r.sensor_type,
                    "name": info.get("name"),
                    "unit": info.get("units"),
                    "ncomp": len(r.values),
                }
        n += 1
        if n >= max_frames:
            break
    return sorted(found.values(), key=lambda s: s["handle"])


def list_recordings(log_dir: str) -> List[dict]:
    """Metadata for every recording in ``log_dir`` (newest first). Cheap: stat + header scan."""
    if not os.path.isdir(log_dir):
        return []
    out: List[dict] = []
    for ssbin in sorted(glob.glob(os.path.join(log_dir, "*" + _SSBIN)), reverse=True):
        base = _base_of(ssbin)
        try:
            st = os.stat(ssbin)
            meta = _load_meta(base)
            n_frames, first, last = _scan_frames(ssbin)
            duration_ms = (last - first) / 1e6 if (first is not None and last is not None) else None
            out.append(
                {
                    "id": os.path.basename(base),
                    "sizeBytes": st.st_size,
                    "modified": int(st.st_mtime * 1000),
                    "frames": n_frames,
                    "durationMs": duration_ms,
                    "model": (meta or {}).get("model"),
                    "android": (meta or {}).get("android"),
                    "sensors": _peek_sensors(ssbin, meta),
                }
            )
        except (OSError, ValueError):
            continue
    return out


def query_signal(
    log_dir: str,
    rec_id: str,
    handle: int,
    start_ns: Optional[int] = None,
    end_ns: Optional[int] = None,
    buckets: int = 1000,
) -> Optional[dict]:
    """Aggregated buckets for one signal over a time range (LOD, ADR-001 §5-§7).

    Returns column-oriented series for uPlot: ``t`` (seconds from the range start) plus
    per-component ``avg`` / ``min`` / ``max``. When the requested range holds fewer raw
    samples than ``buckets``, each bucket is a single sample (i.e. raw resolution).
    """
    handle = int(handle)
    path = os.path.join(log_dir, os.path.basename(rec_id) + _SSBIN)
    if not os.path.isfile(path):
        return None

    ts: List[int] = []
    vals: List[list] = []
    stype: Optional[int] = None
    ncomp = 0
    for _t_recv, data in read_frames(path):
        try:
            dg = p.decode_datagram(data)
        except p.ProtocolError:
            continue
        for r in dg.records:
            if r.sensor_handle != handle:
                continue
            t = r.t_sensor_ns
            if start_ns is not None and t < start_ns:
                continue
            if end_ns is not None and t > end_ns:
                continue
            ts.append(t)
            vals.append(r.values)
            if stype is None:
                stype = r.sensor_type
                ncomp = len(r.values)

    if not ts:
        return {"handle": handle, "type": stype, "ncomp": 0, "raw": 0, "buckets": 0,
                "start": None, "end": None, "t": [], "avg": [], "min": [], "max": []}

    t0, t1 = ts[0], ts[-1]  # file order is chronological
    span = max(1, t1 - t0)
    nb = max(1, min(int(buckets), len(ts)))
    width = span / nb

    agg = [None] * nb  # per-bucket accumulator
    for t, v in zip(ts, vals):
        bi = min(nb - 1, int((t - t0) / width))
        b = agg[bi]
        if b is None:
            b = {"t": t, "n": 0, "min": [float("inf")] * ncomp, "max": [float("-inf")] * ncomp, "sum": [0.0] * ncomp}
            agg[bi] = b
        b["n"] += 1
        b["t"] = t  # last timestamp in bucket (monotonic within bucket)
        for c in range(ncomp):
            x = v[c] if c < len(v) else 0.0
            if x < b["min"][c]:
                b["min"][c] = x
            if x > b["max"][c]:
                b["max"][c] = x
            b["sum"][c] += x

    t_col: List[float] = []
    avg_cols = [[] for _ in range(ncomp)]
    min_cols = [[] for _ in range(ncomp)]
    max_cols = [[] for _ in range(ncomp)]
    for b in agg:
        if b is None:
            continue
        t_col.append(round((b["t"] - t0) / 1e9, 6))
        for c in range(ncomp):
            avg_cols[c].append(round(b["sum"][c] / b["n"], 6))
            min_cols[c].append(round(b["min"][c], 6))
            max_cols[c].append(round(b["max"][c], 6))

    return {
        "handle": handle,
        "type": stype,
        "ncomp": ncomp,
        "raw": len(ts),
        "buckets": len(t_col),
        "start": t0,
        "end": t1,
        "t": t_col,
        "avg": avg_cols,
        "min": min_cols,
        "max": max_cols,
    }
