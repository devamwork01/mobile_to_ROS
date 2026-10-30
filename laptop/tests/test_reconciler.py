import asyncio

from sensorstream import protocol as p
from sensorstream.backfill import GapTracker
from sensorstream.reconcile import Reconciler

S = 1_000_000_000


def _dg(handle, seq, t_sensor, val, device_id=1):
    return p.Datagram(device_id=device_id, records=[p.Record(1, handle, seq, t_sensor, 3, [val])])


def test_requests_gap_then_merges_backfill():
    sent = []
    merged = []
    tr = Reconciler(GapTracker(grace_ns=S),
                    send_resend=lambda d, h, a, b: sent.append((d, h, a, b)),
                    on_backfilled=lambda dg, t: merged.append(dg))
    tr.set_device_client(1, "client-1")
    tr.on_live(1, _dg(0, 0, 100, 0.0), t_recv_ns=0)
    tr.on_live(1, _dg(0, 2, 300, 2.0), t_recv_ns=S)   # missing seq 1

    asyncio.run(tr.tick(now_ns=2 * S + 1))
    assert sent == [(1, 0, 1, 1)]                      # requested the gap

    # phone replies with backfill for seq 1
    frame = p.encode_frame_b64(p.encode_datagram(_dg(0, 1, 200, 1.0)))
    tr.on_backfill(1, 0, [frame])
    assert len(merged) == 1 and merged[0].records[0].seq == 1
    assert tr.stats()["backfilled"] == 1

    # gap now closed: no further requests
    sent.clear()
    asyncio.run(tr.tick(now_ns=100 * S))
    assert sent == []


def test_does_not_double_request_inflight():
    sent = []
    tr = Reconciler(GapTracker(grace_ns=S),
                    send_resend=lambda d, h, a, b: sent.append((d, h, a, b)),
                    on_backfilled=lambda dg, t: None)
    tr.set_device_client(1, "c")
    tr.on_live(1, _dg(0, 0, 100, 0.0), 0)
    tr.on_live(1, _dg(0, 2, 300, 2.0), S)
    asyncio.run(tr.tick(now_ns=2 * S + 1))
    asyncio.run(tr.tick(now_ns=3 * S))   # still missing, but already in-flight
    assert sent == [(1, 0, 1, 1)]        # requested once, not twice


def test_unavailable_marks_permanent():
    tr = Reconciler(GapTracker(grace_ns=S), send_resend=lambda *a: None, on_backfilled=lambda *a: None)
    tr.set_device_client(1, "c")
    tr.on_live(1, _dg(0, 0, 100, 0.0), 0)
    tr.on_live(1, _dg(0, 2, 300, 2.0), S)
    tr.on_unavailable(1, 0, 1, 1)
    assert tr.stats()["permanent"] == 1
    sent = []
    tr._send_resend = lambda d, h, a, b: sent.append((d, h, a, b))
    asyncio.run(tr.tick(now_ns=100 * S))
    assert sent == []   # permanent gap not re-requested


def test_client_survives_device_id_change():
    # a reconnect gives the same client a new device_id; the gap tracked under the old device's
    # client key is requested from the NEW device.
    sent = []
    tr = Reconciler(GapTracker(grace_ns=S),
                    send_resend=lambda d, h, a, b: sent.append((d, h, a, b)),
                    on_backfilled=lambda *a: None)
    tr.set_device_client(1, "same-client")
    tr.on_live(1, _dg(0, 0, 100, 0.0, device_id=1), 0)
    tr.on_live(1, _dg(0, 2, 300, 2.0, device_id=1), S)  # gap on seq 1
    tr.set_device_client(2, "same-client")              # reconnect: new device_id, same client
    asyncio.run(tr.tick(now_ns=2 * S + 1))
    assert sent == [(2, 0, 1, 1)]                        # requested from the NEW device_id


def test_backfilled_frames_are_stamped_with_their_arrival_time():
    # QA 2026-10-02: backfill was appended to the recording with t_recv=0, i.e. hours before the
    # session started on the monotonic clock live frames use.
    got = []
    tr = Reconciler(GapTracker(grace_ns=S), send_resend=lambda *a: None,
                    on_backfilled=lambda dg, t: got.append(t), clock=lambda: 42 * S)
    tr.set_device_client(1, "client-1")
    tr.on_live(1, _dg(0, 0, 100, 0.0), t_recv_ns=40 * S)
    tr.on_live(1, _dg(0, 2, 300, 2.0), t_recv_ns=41 * S)
    tr.on_backfill(1, 0, [p.encode_frame_b64(p.encode_datagram(_dg(0, 1, 200, 1.0)))])
    assert got == [42 * S]


def _gap_tracker(sent):
    tr = Reconciler(GapTracker(grace_ns=S),
                    send_resend=lambda d, h, a, b: sent.append((d, h, a, b)),
                    on_backfilled=lambda dg, t: None)
    tr.set_device_client(1, "c")
    tr.on_live(1, _dg(0, 0, 100, 0.0), 0)
    tr.on_live(1, _dg(0, 5, 600, 5.0), S)             # seqs 1..4 missing
    return tr


def test_rerequests_after_reconnect():
    # The control link dropped before the phone answered: the request went to a dead session.
    sent = []
    tr = _gap_tracker(sent)
    asyncio.run(tr.tick(now_ns=2 * S + 1))
    assert sent == [(1, 0, 1, 4)]
    tr.set_device_client(2, "c")                        # same phone, new device id
    asyncio.run(tr.tick(now_ns=2 * S + 2))
    assert sent == [(1, 0, 1, 4), (2, 0, 1, 4)]         # asked the new session at once


def test_rerequests_when_no_reply_arrives():
    sent = []
    tr = _gap_tracker(sent)
    asyncio.run(tr.tick(now_ns=2 * S + 1))
    asyncio.run(tr.tick(now_ns=11 * S))                 # waiting for the reply
    assert sent == [(1, 0, 1, 4)]
    asyncio.run(tr.tick(now_ns=13 * S))                 # nothing came back: ask again
    assert sent == [(1, 0, 1, 4), (1, 0, 1, 4)]


def test_remainder_not_rerequested_while_later_chunks_are_in_transit():
    # Backfill replies come in chunks: after the first chunk the rest of the range is still missing
    # but already requested.
    sent = []
    tr = _gap_tracker(sent)
    asyncio.run(tr.tick(now_ns=2 * S + 1))
    frames = [p.encode_frame_b64(p.encode_datagram(_dg(0, s, 100 * (s + 1), float(s)))) for s in (1, 2)]
    tr.on_backfill(1, 0, frames)                        # first chunk: seqs 1, 2
    asyncio.run(tr.tick(now_ns=3 * S))
    assert sent == [(1, 0, 1, 4)]                       # 3..4 are on their way
    asyncio.run(tr.tick(now_ns=13 * S))                 # ...but never came: ask for just those
    assert sent == [(1, 0, 1, 4), (1, 0, 3, 4)]
