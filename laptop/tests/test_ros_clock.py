"""ClockMapper: phone event time -> laptop monotonic time, jitter-free and jump-free."""

import random

from sensorstream.ros_clock import ClockMapper, StampGuard

P = 2_000_000          # 500 Hz
OFF = 7_000_000_000    # laptop clock - phone clock
S = 1_000_000_000


def feed(m, recv0, secs, d, period=P):
    """Samples arriving every `period` of laptop time from recv0, each with offset d (phone time =
    receive time - d). A phone clock change is a change of d with the laptop clock running on, as on
    a real reboot. Returns the next receive time."""
    recv = recv0
    for _ in range(int(secs * S // period)):
        m.observe(recv - d, recv)
        recv += period
    return recv


def test_unset_until_first_sample():
    m = ClockMapper()
    assert m.to_mono(5) is None and m.offset_ns is None
    m.observe(100, 1100)
    assert m.to_mono(200) == 1200


def test_stamps_follow_sensor_spacing_under_jitter():
    rng = random.Random(1)
    m = ClockMapper()
    stamps = []
    for k in range(5000):  # 10 s, arrival jitter up to 20 ms
        ts = k * P
        m.observe(ts, ts + OFF + 1_000_000 + rng.randrange(0, 20_000_000))
        if k >= 4000:
            stamps.append(m.to_mono(ts))
    gaps = [b - a for a, b in zip(stamps, stamps[1:])]
    assert all(abs(g - P) <= 25_000 for g in gaps)          # jitter (up to 20 ms) is gone
    assert abs(m.offset_ns - (OFF + 1_000_000)) < 200_000   # offset = min transit


def test_warmup_takes_a_new_minimum_immediately():
    m = ClockMapper()
    m.observe(0, OFF + 30_000_000)
    m.observe(100_000_000, 100_000_000 + OFF)
    assert m.offset_ns == OFF


def test_slews_at_most_1ms_per_second_after_warmup():
    m = ClockMapper()
    ts = feed(m, 0, 3, OFF)
    m.observe(ts - (OFF - 50_000_000), ts)      # one much faster packet (an inlier: < 1 s)
    assert abs(m.offset_ns - OFF) <= 10_000      # no jump
    feed(m, ts + P, 5, OFF)
    assert abs(m.offset_ns - (OFF - 5_000_000)) <= 50_000   # moved ~1 ms per s toward the new min


def test_tracks_clock_drift():
    m = ClockMapper()
    ts = 0
    for _ in range(30_000):  # 60 s, laptop clock gains 20 ppm on the phone
        d = OFF + ts * 20 // 1_000_000
        m.observe(ts, ts + d)
        ts += P
    assert abs(m.offset_ns - d) < 300_000


def test_batched_arrival_keeps_sensor_spacing():
    # Battery mode: the phone hands over 50 samples every 100 ms; each burst arrives at once.
    m = ClockMapper()
    stamps = []
    for k in range(5000):
        ts = k * P
        burst_end = (k // 50 + 1) * 50 * P
        m.observe(ts, burst_end + OFF)
        if k >= 4000:
            stamps.append(m.to_mono(ts))
    gaps = [b - a for a, b in zip(stamps, stamps[1:])]
    assert all(abs(g - P) <= 25_000 for g in gaps)
    assert m.resets == 0


def test_lone_outlier_is_ignored():
    m = ClockMapper()
    ts = feed(m, 0, 3, OFF)
    m.observe(ts - (OFF + 5 * S), ts)
    feed(m, ts + P, 1, OFF)
    assert m.resets == 0 and abs(m.offset_ns - OFF) <= 10_000


def test_short_outlier_burst_never_resets():
    m = ClockMapper()
    ts = feed(m, 0, 3, OFF)
    ts = feed(m, ts, 0.3, OFF - 100 * S)
    feed(m, ts, 1, OFF)
    assert m.resets == 0 and abs(m.offset_ns - OFF) <= 10_000


def test_late_arrivals_never_reset():
    # Within one device id the phone clock never jumps (a reboot reconnects as a new device), so
    # arrivals more than 1 s later than the mapping are transit delay: a stalled link, bufferbloat.
    m = ClockMapper()
    ts = feed(m, 0, 3, OFF)
    ts = feed(m, ts, 0.6, OFF + 1_200_000_000)   # review: 0.6 s of +1.2 s latency reset it twice
    ts = feed(m, ts, 20, OFF)
    ts = feed(m, ts, 20, OFF + 100 * S)
    feed(m, ts, 1, OFF)
    assert m.resets == 0 and abs(m.offset_ns - OFF) <= 10_000


def test_draining_queue_never_resets():
    # review: after a 3 s uplink stall the queue drains at 2x real time, d falling at 1 s/s
    m = ClockMapper()
    ts = feed(m, 0, 3, OFF)
    for k in range(1500):
        m.observe(ts - (OFF + max(0, 3 * S - k * P)), ts)
        ts += P
    feed(m, ts, 2, OFF)
    assert m.resets == 0 and abs(m.offset_ns - OFF) <= 10_000


def test_a_too_late_seed_is_corrected_exactly_once():
    # The first sample arrived 5 s late (queued at connect); arrivals > 1 s earlier than the mapping
    # prove it too late (transit cannot be negative): corrected once, after 0.5 s.
    m = ClockMapper()
    m.observe(0, OFF + 5 * S)
    feed(m, 5 * S + OFF, 20, OFF)
    assert m.resets == 1
    assert m.offset_ns == OFF


def test_no_second_reset_within_holdoff():
    m = ClockMapper()
    ts = feed(m, 0, 3, OFF)
    ts = feed(m, ts, 1, OFF - 100 * S)       # reset #1 after 0.5 s of this
    feed(m, ts, 5, OFF - 200 * S)            # another jump inside the 10 s hold-off
    assert m.resets == 1
    assert m.offset_ns == OFF - 100 * S


def test_inconsistent_outliers_never_reset():
    m = ClockMapper()
    ts = feed(m, 0, 3, OFF)
    for k in range(1000):  # 2 s of garbage alternating +5 s / -5 s
        m.observe(ts - (OFF + (5 * S if k % 2 else -5 * S)), ts)
        ts += P
    assert m.resets == 0 and abs(m.offset_ns - OFF) <= 10_000


def test_stamp_guard_never_goes_backwards_per_topic():
    g = StampGuard()
    assert g("/a", 100) == 100
    assert g("/a", 100) == 101
    assert g("/a", 50) == 102
    assert g("/b", 50) == 50
    assert g("/a", 500) == 500


def test_stamp_guard_accepts_a_large_backward_correction():
    # review: pinning to prev + 1 ns after a wrong mapping froze stamps for as long as the error lasted
    g = StampGuard()
    assert g("/a", 10 * S) == 10 * S
    assert g("/a", 8 * S) == 8 * S          # > 1 s back: a corrected mapping, accepted
    assert g("/a", 8 * S - 5) == 8 * S + 1  # small step back: still nudged
