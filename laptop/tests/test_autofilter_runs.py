"""RunTracker: when each sensor's current unbroken run of samples started."""

import random

from sensorstream.autofilter import RunTracker

S = 1_000_000_000


def feed(rt, h, t0_s, secs, hz, jitter=0.0, seed=0):
    rng = random.Random(seed)
    t, period = int(t0_s * S), S / hz
    end = int((t0_s + secs) * S)
    while t < end:
        rt.observe(h, t)
        t += int(period * (1 + rng.uniform(-jitter, jitter)))
    return t


def test_unknown_handle_has_no_run():
    assert RunTracker().run_seconds(5) == 0.0


def test_steady_stream_is_one_run():
    rt = RunTracker()
    feed(rt, 1, 0, 12, 100)
    assert 11.98 <= rt.run_seconds(1) <= 12.0


def test_gap_starts_a_new_run():
    rt = RunTracker()
    feed(rt, 1, 0, 3, 100)
    feed(rt, 1, 5, 4, 100)          # 2 s gap
    assert 3.98 <= rt.run_seconds(1) <= 4.0


def test_rate_change_starts_a_new_run():
    rt = RunTracker()
    feed(rt, 1, 0, 5, 100)
    feed(rt, 1, 5, 6, 50)
    assert 4.0 < rt.run_seconds(1) < 6.0


def test_jitter_does_not_restart_the_run():
    rt = RunTracker()
    feed(rt, 1, 0, 30, 116.5, jitter=0.10, seed=3)
    assert rt.run_seconds(1) > 29.9


def test_duplicates_and_reordered_samples_are_ignored_and_handles_are_separate():
    rt = RunTracker()
    for k in range(1100):
        rt.observe(1, k * 10_000_000)
        rt.observe(1, k * 10_000_000)          # duplicate
        if k > 2:
            rt.observe(1, (k - 2) * 10_000_000)  # late, reordered
    rt.observe(2, 0)
    assert rt.run_seconds(1) > 10.9 and rt.run_seconds(2) == 0.0


def test_reset_forgets_everything():
    rt = RunTracker()
    feed(rt, 1, 0, 12, 100)
    rt.reset()
    assert rt.run_seconds(1) == 0.0


def test_lost_packet_bursts_do_not_restart_the_run():
    # review M1: one Wi-Fi stall dropping ~18 samples (155 ms) restarted the run through the rate rule
    rt = RunTracker()
    t, period = 0, int(S / 116.5)
    for k in range(int(30 * 116.5)):
        if k % 580 not in range(560, 580):   # every ~5 s, 20 samples never arrive
            rt.observe(1, t)
        t += period
    assert rt.run_seconds(1) > 29.5


def test_a_large_sustained_rate_drop_still_starts_a_new_run():
    rt = RunTracker()
    feed(rt, 1, 0, 5, 100)
    feed(rt, 1, 5, 6, 20)        # 5x slower: every interval looks like a hole, but it lasts
    assert 4.0 < rt.run_seconds(1) < 6.0
