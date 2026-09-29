"""Shared reorder/reset-tolerant loss counter, and the per-sensor dashboard feed that uses it."""

from sensorstream import protocol as p
from sensorstream.seqloss import SeqLoss
from sensorstream.sinks import DashboardSink


def test_seqloss_reordering_is_not_loss():
    s = SeqLoss()
    for q in [0, 1, 2, 4, 3, 6, 5, 7]:
        s.observe(q)
    assert s.lost == 0


def test_seqloss_counts_real_gap_and_survives_restart():
    s = SeqLoss()
    for q in [0, 1, 3]:              # seq 2 never arrives
        s.observe(q)
    for q in range(4, 10_000):       # long clean run
        s.observe(q)
    assert s.lost == 1
    for q in range(0, 100):          # sensor re-toggled: seq restarts at 0 (> reorder window back)
        s.observe(q)
    assert s.lost == 1               # restart is a new epoch, not loss; earlier loss kept


def test_dashboard_feed_reports_no_false_loss_under_reordering():
    out = []
    sink = DashboardSink(out.append, max_ui_hz=0)   # 0 = forward every record
    for q in [0, 1, 2, 4, 3, 6, 5]:
        sink.on_datagram(p.Datagram(device_id=1, records=[p.Record(1, 0, q, 100 + q, 3, [0.0])]), ("x", 0), 0)
    last = out[-1]["records"][-1]
    assert last["lost"] == 0


def test_dashboard_feed_reports_real_loss():
    out = []
    sink = DashboardSink(out.append, max_ui_hz=0)
    for q in [0, 1, 4]:          # 2 and 3 missing
        sink.on_datagram(p.Datagram(device_id=1, records=[p.Record(1, 0, q, 100 + q, 3, [0.0])]), ("x", 0), 0)
    assert out[-1]["records"][-1]["lost"] == 2
