"""Guided tuning: the capture window, its checks, the spectra and the result summary."""

import numpy as np

from sensorstream import protocol as p
from sensorstream.autofilter import capture_problem, spectra, summary
from sensorstream.insights import InsightsSink

S = 1_000_000_000


def sink_with(ts, handle=0):
    ins = InsightsSink(clock=lambda: 0.0)
    for k, t in enumerate(ts):
        ins.on_datagram(p.Datagram(device_id=1, records=[p.Record(1, handle, k, int(t), 3, [float(k), 0.0, 9.8])]), None, 0)
    return ins


def test_samples_between_is_half_open_sorted_and_deduplicated():
    ins = sink_with([5 * S, 1 * S, 2 * S, 3 * S, 4 * S])
    ins.on_datagram(p.Datagram(device_id=1, records=[p.Record(1, 0, 3, 3 * S, 3, [3.0, 0.0, 9.8])]), None, 0)  # duplicate
    t, v = ins.samples_between(0, 2 * S, 4 * S)
    assert t.tolist() == [3 * S, 4 * S] and v.shape == (2, 3)
    t, v = ins.samples_between(9, 0, 10 * S)
    assert t.size == 0


def test_capture_problem():
    ok = np.arange(0, 10 * S, S // 100)
    assert capture_problem(ok) is None
    assert capture_problem(ok[: len(ok) // 2]) == "not enough data in the capture"
    gap = np.concatenate([ok[:300], ok[450:]])
    assert capture_problem(gap) == "data gap during the capture"
    assert capture_problem(np.array([], dtype=np.int64)) == "not enough data in the capture"


def test_spectra_rate_and_three_axes():
    t = np.arange(0, 10 * S, S // 100, dtype=np.int64)
    v = np.random.default_rng(1).normal(0, 1, (t.size, 3))
    f, fs, per = spectra(t, v)
    assert abs(fs - 100.0) < 0.5 and len(per) == 3 and len(f) == len(per[0]) > 8


def test_summary_groups_equal_axes():
    a = {"lowpass": {"hz": 0.5, "order": 4}, "notches": []}
    z = {"lowpass": {"hz": 5.0, "order": 4}, "notches": [{"hz": 8.0, "q": 10.0}]}
    assert summary([a, a, z]) == "X/Y LP 0.5 Hz | Z LP 5 Hz + notch 8 Hz"
    assert summary([z, z, z]) == "X/Y/Z LP 5 Hz + notch 8 Hz"
    assert summary([{"lowpass": None, "notches": [{"hz": 8.0, "q": 10.0}]}] * 3) == "X/Y/Z notch 8 Hz"
