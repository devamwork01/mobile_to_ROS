"""Synchronization / connection-quality metric tests."""

from __future__ import annotations

from sensorstream import protocol as p
from sensorstream.sync import SyncTracker


def _dg(records):
    return p.Datagram(device_id=1, records=records)


def test_loss_counting_exact():
    st = SyncTracker()
    # seq 0,1,3,7 -> gaps of 1 (missing 2) and 3 (missing 4,5,6) = 4 lost
    for seq in (0, 1, 3, 7):
        st.observe(_dg([p.Record(1, 0, seq, 1000 + seq, 3, [0.0])]), t_recv_ns=2000 + seq)
    snap = st.snapshot()
    assert st.total_lost == 4
    assert snap["received"] == 4
    assert snap["loss_pct"] == round(100 * 4 / 8, 3)


def test_reorder_detection():
    st = SyncTracker()
    for i, t in enumerate([100, 200, 150, 300]):  # 150 < 200 -> one reorder
        st.observe(_dg([p.Record(1, 0, i, t, 3, [0.0])]), t_recv_ns=1000 + i)
    assert st.total_reordered == 1


def test_latency_min_filter():
    st = SyncTracker()
    offset = 1_000_000_000  # constant clock-epoch offset (ns)
    jitters_ms = [0, 5, 10, 2, 20]
    for i, jm in enumerate(jitters_ms):
        t_sensor = 1_000_000 + i * 10_000_000
        t_recv = t_sensor + offset + int(jm * 1e6)
        st.observe(_dg([p.Record(1, 0, i, t_sensor, 3, [0.0])]), t_recv_ns=t_recv)
    snap = st.snapshot()
    assert snap["offset_ns"] == offset               # min delta == offset (jitter 0 sample)
    assert snap["latency_ms_p50"] == 5.0             # median of [0,2,5,10,20]
    assert snap["latency_ms_p95"] == 20.0
    assert snap["jitter_ms"] == 15.0


def test_phone_latency():
    st = SyncTracker()
    for i, ms in enumerate([1, 2, 3, 4, 5]):  # on-phone acq->send latencies
        acq = 1_000_000 + i * 10_000_000
        ser = acq + int(ms * 1e6)
        st.observe(
            _dg([p.Record(1, 0, i, acq, 3, [0.0], t_acquire_ns=acq, t_serialize_ns=ser)]),
            t_recv_ns=acq + 1_000_000_000,
        )
    snap = st.snapshot()
    assert snap["phone_latency_ms_p50"] == 3.0  # median of [1,2,3,4,5]
    assert snap["phone_latency_ms_p95"] == 5.0


def test_multi_sensor_independent_loss():
    st = SyncTracker()
    # accel (handle 0) clean; gyro (handle 1) drops one
    st.observe(_dg([p.Record(1, 0, 0, 10, 3, [0.0]), p.Record(4, 1, 0, 10, 3, [0.0])]), 100)
    st.observe(_dg([p.Record(1, 0, 1, 20, 3, [0.0]), p.Record(4, 1, 2, 20, 3, [0.0])]), 110)
    snap = st.snapshot()
    assert snap["active_sensors"] == 2
    assert st.total_lost == 1  # gyro skipped seq 1
    assert snap["received"] == 4


def test_reordering_is_not_loss():
    """UDP delivers datagrams out of order; every seq still arrives, just not in order.

    The old strictly-increasing gap counter fabricated loss on the forward hops and never
    took it back when the late packet showed up, so loss climbed forever with zero real
    data missing. Loss must be immune to pure reordering.
    """
    st = SyncTracker()
    order = [0, 1, 2, 4, 3, 6, 5, 7, 9, 8, 10]  # all present 0..10, several swaps
    for i, seq in enumerate(order):
        st.observe(_dg([p.Record(1, 0, seq, 1000 + i, 3, [0.0])]), t_recv_ns=2000 + i)
    snap = st.snapshot()
    assert snap["received"] == len(order)
    assert st.total_lost == 0
    assert snap["loss_pct"] == 0.0


def test_real_loss_survives_reordering():
    """A genuinely missing seq is still counted even when the rest arrives out of order."""
    st = SyncTracker()
    order = [0, 2, 1, 4, 6, 5]  # seq 3 never arrives; others reordered
    for i, seq in enumerate(order):
        st.observe(_dg([p.Record(1, 0, seq, 1000 + i, 3, [0.0])]), t_recv_ns=2000 + i)
    snap = st.snapshot()
    assert snap["received"] == 6
    assert st.total_lost == 1  # only seq 3 is missing (span 0..6 = 7, received 6)


def test_seq_reset_is_not_loss():
    """Toggling a sensor off/on restarts its per-handle seq at 0 (SensorEventSource).

    A shared, long-lived SyncTracker must treat that restart as a new epoch, not as a
    ~5000-sample loss spike.
    """
    st = SyncTracker()
    for seq in range(5000):  # clean run
        st.observe(_dg([p.Record(1, 0, seq, seq, 3, [0.0])]), t_recv_ns=seq)
    for seq in range(100):  # phone re-toggled the sensor; seq restarts at 0
        st.observe(_dg([p.Record(1, 0, seq, 10_000_000 + seq, 3, [0.0])]), t_recv_ns=10_000_000 + seq)
    snap = st.snapshot()
    assert snap["received"] == 5100
    assert st.total_lost == 0
