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
