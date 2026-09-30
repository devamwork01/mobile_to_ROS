import numpy as np

from sensorstream import protocol as p
from sensorstream.insights import InsightsSink


class Clock:
    def __init__(self):
        self.t = 100.0

    def __call__(self):
        return self.t


def feed(sink, handle, type_, n, fs, t0_ns=1_000_000_000, vals=None, seq0=0):
    for i in range(n):
        v = vals(i) if vals else [0.1 * np.sin(i / 10), 0.2, 9.8]
        dg = p.Datagram(device_id=1, records=[p.Record(type_, handle, seq0 + i, t0_ns + int(i * 1e9 / fs), 3, v)])
        sink.on_datagram(dg, ("x", 0), 0)


def test_stats_message_shape_and_values():
    clk = Clock()
    s = InsightsSink(clock=clk)
    feed(s, 3, 1, 1000, 100.0)
    msgs = s.tick()
    stats = [m for m in msgs if m["kind"] == "insights"][0]["stats"]
    assert len(stats) == 1 and stats[0]["handle"] == 3
    assert abs(stats[0]["rate_hz"] - 100.0) < 0.5
    assert len(stats[0]["axes"]) == 3 and stats[0]["axes"][2]["mean"] > 9.7


def test_psd_only_when_subscribed_and_expires():
    clk = Clock()
    s = InsightsSink(clock=clk)
    feed(s, 3, 1, 1000, 100.0)
    assert not [m for m in s.tick() + s.tick() if m["kind"] == "insights_psd"]
    s.subscribe_psd([3])
    got = [m for m in s.tick() + s.tick() if m["kind"] == "insights_psd"]
    assert got and got[0]["handle"] == 3 and len(got[0]["psd"]) == 3 and got[0]["f"][0] >= 0.1
    clk.t += 11  # subscription TTL 10 s
    feed(s, 3, 1, 200, 100.0, t0_ns=20_000_000_000, seq0=1000)
    assert not [m for m in s.tick() + s.tick() if m["kind"] == "insights_psd"]


def test_window_is_last_10_s_and_ring_is_bounded():
    clk = Clock()
    s = InsightsSink(clock=clk)
    feed(s, 3, 1, 40000, 500.0)  # 80 s at 500 Hz > ring capacity
    st = [m for m in s.tick() if m["kind"] == "insights"][0]["stats"][0]
    assert abs(st["rate_hz"] - 500.0) < 2
    assert s.ring_size(3) <= 30000


def test_dedups_and_sorts():
    clk = Clock()
    s = InsightsSink(clock=clk)
    feed(s, 3, 4, 500, 100.0)
    feed(s, 3, 4, 50, 100.0, t0_ns=1_000_000_000 + int(200 * 1e7), seq0=200)  # re-delivered samples
    st = [m for m in s.tick() if m["kind"] == "insights"][0]["stats"][0]
    assert st["gaps"] == 0 and abs(st["rate_hz"] - 100.0) < 0.5


def test_stale_sensors_are_skipped():
    clk = Clock()
    s = InsightsSink(clock=clk)
    feed(s, 3, 1, 500, 100.0)
    clk.t += 5
    msgs = s.tick()
    assert all(m["kind"] != "insights" or m["stats"] == [] for m in msgs)
