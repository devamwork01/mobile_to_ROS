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
