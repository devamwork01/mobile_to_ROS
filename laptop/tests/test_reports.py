import json
import os

import numpy as np

from sensorstream import protocol as p
from sensorstream import reports
from sensorstream.logging_sink import Recorder

RNG = np.random.default_rng(3)


def make_recording(tmp_path, n=3000, fs=100.0, dup=True, meta=None):
    rec = Recorder()
    base = rec.start(str(tmp_path), meta or {"model": "Pixel", "android": "15",
                     "sensors": [{"handle": 4, "name": "Gyro X1", "type": 4, "units": "rad/s"}]})
    for i in range(n):
        v = list(RNG.normal(0.001, 0.01, 3))
        r = p.Record(4, 4, i, 1_000_000_000 + int(i * 1e9 / fs), 3, v)
        rec.on_datagram(p.Datagram(device_id=1, records=[r]), i)
        if dup and i % 500 == 0:
            rec.on_datagram(p.Datagram(device_id=1, records=[r]), i)  # backfill duplicate
    rec.stop()
    return base


def test_load_samples_dedups_and_sorts(tmp_path):
    base = make_recording(tmp_path)
    s = reports.load_samples(base + ".ssbin")
    assert list(s) == [4]
    assert len(s[4]["t"]) == 3000
    assert np.all(np.diff(s[4]["t"]) > 0)


def test_analyse_recording_writes_report_with_names(tmp_path):
    base = make_recording(tmp_path)
    rep = reports.analyse_recording(base + ".ssbin", preset="still", requested_s=30)
    assert os.path.isfile(base + ".report.json")
    assert rep["id"] == os.path.basename(base)
    assert rep["device"]["model"] == "Pixel"
    g = rep["sensors"][0]
    assert g["name"] == "Gyro X1" and g["unit"] == "rad/s"
    assert abs(g["axes"]["x"]["bias"] - 0.001) < 0.002
    assert json.load(open(base + ".report.json"))["preset"] == "still"


def test_analyse_recording_window_trims(tmp_path):
    base = make_recording(tmp_path, n=6000)
    rep = reports.analyse_recording(base + ".ssbin", start_ns=1_000_000_000 + 10 * 10**9, seconds=20)
    assert 1990 <= rep["sensors"][0]["samples"] <= 2010
    assert abs(rep["duration_s"] - 20) < 0.2


def test_run_with_no_data(tmp_path):
    rec = Recorder()
    base = rec.start(str(tmp_path), {"model": "P"})
    rec.stop()
    rep = reports.analyse_recording(base + ".ssbin", preset="still", complete=False)
    assert rep["sensors"] == [] and rep["complete"] is False


def test_list_and_load_reports(tmp_path):
    base = make_recording(tmp_path)
    reports.analyse_recording(base + ".ssbin", preset="capture")
    lst = reports.list_reports(str(tmp_path))
    assert lst[0]["id"] == os.path.basename(base) and lst[0]["preset"] == "capture"
    assert "sensors" not in lst[0] and lst[0]["sensor_count"] == 1
    assert reports.load_report(str(tmp_path), os.path.basename(base))["preset"] == "capture"


def test_rejects_traversal_ids(tmp_path):
    assert reports.load_report(str(tmp_path), "../secret") is None
    assert reports.load_report(str(tmp_path), "a/b") is None
    assert not reports.valid_id("..")


def test_seq_restart_keeps_both_halves(tmp_path):
    # The phone restarts its per-handle seq at 0 when a sensor is re-toggled or the app
    # reconnects: same seq, different time = different sample, both must be kept.
    rec = Recorder()
    base = rec.start(str(tmp_path), {"model": "P"})
    for half in range(2):
        for i in range(300):
            t = 1_000_000_000 + half * 10_000_000_000 + i * 10_000_000
            rec.on_datagram(p.Datagram(device_id=1, records=[p.Record(4, 4, i, t, 3, [0.0, 0.0, 0.0])]), i)
    rec.on_datagram(p.Datagram(device_id=1, records=[p.Record(4, 4, 5, 1_050_000_000, 3, [0.0, 0.0, 0.0])]), 0)  # true dup
    rec.stop()
    s = reports.load_samples(base + ".ssbin")
    assert len(s[4]["t"]) == 600
