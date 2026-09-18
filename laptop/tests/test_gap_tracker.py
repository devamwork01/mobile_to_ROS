from sensorstream.backfill import GapTracker

C = "client-1"
S = 1_000_000_000  # 1s in ns


def test_no_gap_when_contiguous():
    g = GapTracker(grace_ns=S)
    for seq in range(5):
        g.observe(C, 0, seq, t_recv_ns=seq * S)
    assert g.due_ranges(now_ns=10 * S) == []
    assert g.contiguous_upto(C, 0) == 4


def test_detects_gap_after_grace():
    g = GapTracker(grace_ns=S)
    g.observe(C, 0, 0, t_recv_ns=0)
    g.observe(C, 0, 3, t_recv_ns=1 * S)   # missing 1,2
    # within grace: not yet due
    assert g.due_ranges(now_ns=1 * S) == []
    # after grace: due
    assert g.due_ranges(now_ns=1 * S + S + 1) == [(C, 0, 1, 2)]


def test_resolve_closes_gap():
    g = GapTracker(grace_ns=S)
    g.observe(C, 0, 0, 0)
    g.observe(C, 0, 3, 1 * S)
    g.resolve(C, 0, [1, 2])
    assert g.due_ranges(now_ns=100 * S) == []
    assert g.contiguous_upto(C, 0) == 3


def test_partial_resolve_leaves_remainder():
    g = GapTracker(grace_ns=S)
    g.observe(C, 0, 0, 0)
    g.observe(C, 0, 4, 1 * S)   # missing 1,2,3
    g.resolve(C, 0, [2])        # 1 and 3 still missing
    due = g.due_ranges(now_ns=100 * S)
    missing = set()
    for (_c, _h, a, b) in due:
        missing |= set(range(a, b + 1))
    assert missing == {1, 3}


def test_permanent_gap_not_re_requested():
    g = GapTracker(grace_ns=S)
    g.observe(C, 0, 0, 0)
    g.observe(C, 0, 3, 1 * S)
    g.mark_permanent(C, 0, 1, 2)
    assert g.due_ranges(now_ns=100 * S) == []
    assert g.stats()["permanent"] == 2


def test_two_clients_isolated():
    g = GapTracker(grace_ns=S)
    g.observe("a", 0, 0, 0); g.observe("a", 0, 2, S)   # a missing 1
    g.observe("b", 0, 0, 0); g.observe("b", 0, 1, S)   # b contiguous
    due = g.due_ranges(now_ns=100 * S)
    assert due == [("a", 0, 1, 1)]


def test_rogue_forward_seq_is_bounded():
    """A corrupted/huge seq must not materialize a giant `missing` set (memory safety).

    The old code did `for s in range(max_seq + 1, seq)`, so one rogue seq millions above the
    max tried to create millions of gap entries -- a CPU/memory blow-up. A jump beyond
    `_RESET_GAP` is treated as a counter reset/wrap, not as real loss.
    """
    g = GapTracker(grace_ns=S)
    g.observe(C, 0, 0, 0)
    g.observe(C, 0, 1, S)
    g.observe(C, 0, 200_000, 2 * S)   # ~200k above max (> _RESET_GAP): reset, not 200k gaps
    assert g.stats()["open_gaps"] < 100
    # the stream keeps detecting real gaps in the new epoch
    g.observe(C, 0, 200_001, 3 * S)
    g.observe(C, 0, 200_004, 4 * S)   # missing 200_002, 200_003
    assert g.due_ranges(now_ns=100 * S) == [(C, 0, 200_002, 200_003)]


def test_sensor_retoggle_resets_epoch():
    """Re-toggling a sensor restarts its per-handle seq at 0 (SensorEventSource). The tracker,
    keyed by the stable client_id, must start a fresh epoch instead of staying pinned at the
    old max_seq and going blind to new gaps for a whole seq cycle.
    """
    g = GapTracker(grace_ns=S)
    for seq in range(5000):            # a long clean run
        g.observe(C, 0, seq, seq * S)
    assert g.contiguous_upto(C, 0) == 4999
    # sensor toggled off/on -> seq restarts at 0, then a real drop (seq 2 missing)
    g.observe(C, 0, 0, 6000 * S)
    g.observe(C, 0, 1, 6001 * S)
    g.observe(C, 0, 3, 6002 * S)      # missing 2 in the NEW epoch
    assert g.due_ranges(now_ns=9000 * S) == [(C, 0, 2, 2)]


def test_small_backward_is_reorder_not_reset():
    """A late straggler a few seq below the max is normal reordering (fills the hole), not a
    reset -- guards against setting the reorder window too tight."""
    g = GapTracker(grace_ns=S)
    g.observe(C, 0, 0, 0)
    g.observe(C, 0, 2, S)       # missing 1
    g.observe(C, 0, 1, S + 1)   # late arrival of 1 fills the hole
    assert g.due_ranges(now_ns=100 * S) == []
    assert g.contiguous_upto(C, 0) == 2


def test_retoggle_preserves_permanent_count():
    """A restart must not silently zero the lifetime 'permanent gaps' figure (real loss that
    happened before the re-toggle is still real)."""
    g = GapTracker(grace_ns=S)
    g.observe(C, 0, 0, 0)
    g.observe(C, 0, 3, S)
    g.mark_permanent(C, 0, 1, 2)          # 2 irrecoverable in the old epoch
    assert g.stats()["permanent"] == 2
    for seq in range(5000):               # long run then a re-toggle (seq back to 0)
        g.observe(C, 0, 10 + seq, (10 + seq) * S)
    g.observe(C, 0, 0, 100_000 * S)       # restart
    assert g.stats()["permanent"] == 2    # old permanent loss still counted
