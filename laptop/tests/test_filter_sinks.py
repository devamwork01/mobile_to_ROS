import numpy as np

from sensorstream import protocol as p
from sensorstream.insights import InsightsSink
from sensorstream.sinks import DashboardSink


class Clock:
    t = 100.0

    def __call__(self):
        return self.t


def dg(i, fs=100.0):
    v = [np.sin(i / 5.0) + 0.1 * np.sin(i * 2.9), 0.0, 9.8]
    return p.Datagram(device_id=1, records=[p.Record(1, 3, i, 1_000_000_000 + int(i * 1e9 / fs), 3, v)])


def test_dashboard_records_get_vf_only_when_filtered():
    sent = []
    sink = DashboardSink(sent.append, max_ui_hz=0)  # no rate cap (Windows monotonic clock is ~15 ms coarse)
    sink.on_datagram(dg(0), ("x", 0), 0)
    sink.on_datagram(dg(1), ("x", 0), 0, filtered={(3, 1): [0.5, 0.0, 9.8]})
    recs = [r for m in sent for r in m["records"]]
    assert "vf" not in recs[0]
    assert recs[1]["vf"] == [0.5, 0.0, 9.8]


def test_insights_fstd_and_psd_f_only_with_filtered_data():
    clk = Clock()
    s = InsightsSink(clock=clk)
    for i in range(800):
        s.on_datagram(dg(i), ("x", 0), 0)
    st = [m for m in s.tick() if m["kind"] == "insights"][0]["stats"][0]
    assert "fstd" not in st["axes"][0]
    s.subscribe_psd([3])
    for i in range(800, 1600):
        s.on_datagram(dg(i), ("x", 0), 0, filtered={(3, i): [0.5 * np.sin(i / 5.0), 0.0, 9.8]})
    msgs = s.tick() + s.tick()
    st = [m for m in msgs if m["kind"] == "insights"][0]["stats"][0]
    assert st["axes"][0]["fstd"] < st["axes"][0]["std"]
    psd = [m for m in msgs if m["kind"] == "insights_psd"][0]
    assert len(psd["psd_f"]) == 3 and len(psd["psd_f"][0]) == len(psd["f"])


def test_suggest_source_spectrum():
    clk = Clock()
    s = InsightsSink(clock=clk)
    for i in range(1000):
        s.on_datagram(dg(i), ("x", 0), 0)
    f, psd, fs, per = s.suggest(3)
    assert len(per) >= 1
    assert abs(fs - 100.0) < 1 and len(f) == len(psd) and f[-1] <= 50.0
    assert s.suggest(99) is None
