"""Test runs: a timed, tagged recording analysed into a report.

Lifecycle: start (refused unless a phone streams and nothing is recording) -> running (progress
every tick) -> settling (keep recording GRACE_S so late backfill lands in the file) -> analyse
the run window off the event loop -> done/error. A >2 s data gap during running marks the run
incomplete. Only one run at a time; the Recorder is shared with the Record button, which the
app disables while a run is active.
"""

from __future__ import annotations

import asyncio
import time
from datetime import datetime, timezone
from typing import Callable, List, Optional, Tuple

from . import reports

GRACE_S = 3.0
DISCONNECT_S = 2.0
PRESETS = ("still", "capture")


class TestRunManager:
    __test__ = False  # not a pytest test class

    def __init__(self, recorder, log_dir: str, meta_fn: Callable[[], Optional[dict]],
                 streaming_fn: Callable[[], bool], broadcast: Callable[[dict], None],
                 clock: Callable[[], float] = time.monotonic, analyse=reports.analyse_recording):
        self._rec = recorder
        self._log_dir = log_dir
        self._meta_fn = meta_fn
        self._streaming = streaming_fn
        self._broadcast = broadcast
        self._clock = clock
        self._analyse = analyse
        self._run: Optional[dict] = None

    @property
    def active(self) -> bool:
        return self._run is not None

    def start(self, preset: str, seconds: float, handles: List[int]) -> Tuple[bool, str]:
        if self._run is not None:
            return False, "A test run is already in progress."
        if self._rec.is_recording:
            return False, "Stop the current recording first."
        if not self._streaming():
            return False, "No phone is streaming."
        if preset not in PRESETS or not seconds or seconds <= 0:
            return False, "Invalid test run settings."
        meta = dict(self._meta_fn() or {})
        meta["test_run"] = {"preset": preset, "seconds": seconds, "handles": list(handles),
                            "started": datetime.now(timezone.utc).isoformat(timespec="seconds")}
        base = self._rec.start(self._log_dir, meta)
        now = self._clock()
        self._run = {"base": base, "preset": preset, "seconds": float(seconds), "requested": float(seconds),
                     "handles": list(handles), "started": now, "last_data": now, "start_ns": None,
                     "phase": "running", "complete": True, "settle_until": None}
        self._emit()
        return True, "started"

    def stop_early(self) -> None:
        r = self._run
        if r and r["phase"] == "running":
            r["seconds"] = max(0.0, self._clock() - r["started"])

    def on_datagram(self, dg) -> None:
        r = self._run
        if not r or r["phase"] != "running":
            return
        r["last_data"] = self._clock()
        if r["start_ns"] is None and dg.records:
            r["start_ns"] = min(rec.t_sensor_ns for rec in dg.records)

    def status(self) -> Optional[dict]:
        return self._message() if self._run else None

    def _message(self, **extra) -> dict:
        r = self._run
        return {"kind": "testrun", "phase": r["phase"], "id": r["base"].replace("\\", "/").rsplit("/", 1)[-1],
                "preset": r["preset"], "seconds": r["requested"],
                "elapsed": round(min(self._clock() - r["started"], r["seconds"]), 1),
                "handles": r["handles"], "complete": r["complete"], **extra}

    def _emit(self, **extra) -> None:
        self._broadcast(self._message(**extra))

    async def tick(self) -> None:
        r = self._run
        if not r:
            return
        now = self._clock()
        if r["phase"] == "running":
            if now - r["last_data"] > DISCONNECT_S:
                r["complete"] = False
            if now - r["started"] >= r["seconds"]:
                r["phase"] = "settling"
                r["settle_until"] = now + GRACE_S
            self._emit()
            return
        if r["phase"] == "settling" and now >= r["settle_until"]:
            self._rec.stop()
            r["phase"] = "analysing"
            self._emit()
            try:
                await asyncio.to_thread(
                    self._analyse, r["base"] + ".ssbin", preset=r["preset"], start_ns=r["start_ns"],
                    seconds=r["seconds"], complete=r["complete"], requested_s=r["requested"])
                r["phase"] = "done"
                self._emit()
            except Exception as exc:  # the recording is kept; the user can Analyse it later
                r["phase"] = "error"
                self._emit(message=str(exc))
            self._run = None
