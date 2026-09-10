"""Session recording — independent of visualization.

Writes two files per session under the log dir:
  * ``<base>.ssbin`` — lossless binary log: every telemetry datagram, length- and
    receive-time-prefixed, exactly as received (re-encoded from the decoded
    datagram, which is byte-identical for our fixed float32 format). Used by
    ``replay.py``.
  * ``<base>.csv``   — one row per sensor record, for pandas / MATLAB analysis.
  * ``<base>.meta.json`` — device info + sensor catalog (if known at start), so
    handles can be mapped back to names.

Recording is toggled at runtime and never blocks the live pipeline beyond a file
write; start/stop is independent of the dashboard.
"""

from __future__ import annotations

import json
import os
import struct
import time
from datetime import datetime
from typing import BinaryIO, Optional, TextIO

from . import protocol as p

LOG_MAGIC = b"SSLOG1\n"                 # 7-byte file header
FRAME = struct.Struct("<qI")            # t_recv_ns (i64), datagram length (u32)
CSV_HEADER = "t_sensor_ns,sensor_type,handle,seq,accuracy,v0,v1,v2,v3,v4,v5\n"


class Recorder:
    def __init__(self) -> None:
        self._bin: Optional[BinaryIO] = None
        self._csv: Optional[TextIO] = None
        self.path_base: Optional[str] = None
        self.rows = 0
        self.frames = 0
        self.started_at = 0.0

    @property
    def is_recording(self) -> bool:
        return self._bin is not None

    def start(self, log_dir: str, meta: Optional[dict] = None) -> str:
        self.stop()
        os.makedirs(log_dir, exist_ok=True)
        base = datetime.now().strftime("session_%Y%m%d_%H%M%S_%f")[:-3]
        self.path_base = os.path.join(log_dir, base)
        self._bin = open(self.path_base + ".ssbin", "wb")
        self._bin.write(LOG_MAGIC)
        self._csv = open(self.path_base + ".csv", "w", encoding="utf-8", newline="")
        self._csv.write(CSV_HEADER)
        if meta is not None:
            with open(self.path_base + ".meta.json", "w", encoding="utf-8") as fh:
                json.dump(meta, fh, indent=2)
        self.rows = 0
        self.frames = 0
        self.started_at = time.monotonic()
        return self.path_base

    def on_datagram(self, dg: p.Datagram, t_recv_ns: int) -> None:
        if self._bin is None:
            return
        raw = p.encode_datagram(dg)
        self._bin.write(FRAME.pack(t_recv_ns, len(raw)))
        self._bin.write(raw)
        self.frames += 1
        if self._csv is not None:
            for r in dg.records:
                vals = [repr(v) for v in r.values[:6]]
                vals += [""] * (6 - len(vals))
                self._csv.write(
                    f"{r.t_sensor_ns},{r.sensor_type},{r.sensor_handle},{r.seq},{r.accuracy},"
                    + ",".join(vals)
                    + "\n"
                )
                self.rows += 1

    def stop(self) -> Optional[str]:
        base = self.path_base
        if self._bin is not None:
            self._bin.close()
            self._bin = None
        if self._csv is not None:
            self._csv.close()
            self._csv = None
        self.path_base = None
        return base


def read_frames(path: str):
    """Yield ``(t_recv_ns, datagram_bytes)`` from a ``.ssbin`` log, in order."""
    with open(path, "rb") as fh:
        magic = fh.read(len(LOG_MAGIC))
        if magic != LOG_MAGIC:
            raise ValueError(f"{path}: not an SSLOG file")
        while True:
            hdr = fh.read(FRAME.size)
            if len(hdr) < FRAME.size:
                break
            t_recv_ns, length = FRAME.unpack(hdr)
            data = fh.read(length)
            if len(data) < length:
                break  # truncated tail (e.g. crash during recording) — stop cleanly
            yield t_recv_ns, data
