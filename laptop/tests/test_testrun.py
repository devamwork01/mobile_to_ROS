import asyncio

from sensorstream import protocol as p
from sensorstream.logging_sink import Recorder
from sensorstream.testrun import TestRunManager


class Clock:
    def __init__(self):
        self.t = 0.0

    def __call__(self):
        return self.t


def dg(i, t0=1_000_000_000):
    return p.Datagram(device_id=1, records=[p.Record(4, 4, i, t0 + i * 10_000_000, 3, [0.0, 0.0, 0.0])])


def make(tmp_path, streaming=True, analyse=None):
    rec, clk, sent = Recorder(), Clock(), []
    calls = []

    def fake_analyse(ssbin, **kw):
        calls.append((ssbin, kw))
        return {"id": "x"}

    m = TestRunManager(rec, str(tmp_path), lambda: {"model": "P"}, lambda: streaming, sent.append,
                       clock=clk, analyse=analyse or fake_analyse)
    return m, rec, clk, sent, calls


def run(coro):
    return asyncio.run(coro)


def test_refuses_without_stream_or_while_recording(tmp_path):
    m, rec, *_ = make(tmp_path, streaming=False)
    assert m.start("still", 30, [4])[0] is False
    m2, rec2, *_ = make(tmp_path)
    rec2.start(str(tmp_path))
    assert m2.start("still", 30, [4])[0] is False
    rec2.stop()


def test_full_lifecycle_complete(tmp_path):
    m, rec, clk, sent, calls = make(tmp_path)
    ok, _ = m.start("still", 30, [4])
    assert ok and rec.is_recording and m.active
    assert m.start("still", 30, [4])[0] is False  # one at a time
    for i in range(3000):
        clk.t = i * 0.01
        m.on_datagram(dg(i))
        rec.on_datagram(dg(i), i)
    clk.t = 30.0
    run(m.tick())                     # -> settling (keeps recording for backfill)
    assert rec.is_recording and sent[-1]["phase"] == "settling"
    clk.t = 33.1
    run(m.tick())                     # -> stop + analyse + done
    assert not rec.is_recording and not m.active
    assert sent[-1]["phase"] == "done" and sent[-1]["complete"] is True
    ssbin, kw = calls[0]
    assert ssbin.endswith(".ssbin") and kw["preset"] == "still" and kw["seconds"] == 30
    assert kw["start_ns"] == 1_000_000_000 and kw["complete"] is True


def test_disconnect_marks_incomplete_and_stop_early(tmp_path):
    m, rec, clk, sent, calls = make(tmp_path)
    m.start("capture", 60, [4])
    m.on_datagram(dg(0))
    clk.t = 5.0
    run(m.tick())                     # no data for 5 s -> incomplete
    clk.t = 6.0
    m.stop_early()
    run(m.tick())
    clk.t = 9.5
    run(m.tick())
    assert sent[-1]["phase"] == "done" and sent[-1]["complete"] is False
    assert calls[0][1]["complete"] is False and calls[0][1]["requested_s"] == 60


def test_analysis_failure_reports_error(tmp_path):
    def boom(ssbin, **kw):
        raise RuntimeError("bad file")

    m, rec, clk, sent, _ = make(tmp_path, analyse=boom)
    m.start("still", 1, [4])
    m.on_datagram(dg(0))
    clk.t = 1.0
    run(m.tick())
    clk.t = 4.5
    run(m.tick())
    assert sent[-1]["phase"] == "error" and "bad file" in sent[-1]["message"] and not m.active


def test_status_for_reconnecting_browser(tmp_path):
    m, rec, clk, sent, _ = make(tmp_path)
    assert m.status() is None
    m.start("still", 30, [4])
    assert m.status()["phase"] == "running"
