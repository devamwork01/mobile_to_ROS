import asyncio
import json
import types

from sensorstream.control import ControlServer


class FakeWs:
    def __init__(self):
        self.sent = []

    async def send(self, text):
        self.sent.append(json.loads(text))


def test_send_filters_to_every_connected_phone():
    cs = ControlServer(5005, on_event=lambda ev: None)
    a, b = FakeWs(), FakeWs()
    cs._sessions = {1: types.SimpleNamespace(ws=a), 2: types.SimpleNamespace(ws=b)}
    cfg = {"1:Acc": {"lowpass": {"hz": 5.0, "order": 4}, "notches": []}}
    n = asyncio.run(cs.send_filters(cfg))
    assert n == 2
    assert a.sent[-1] == {"type": "filters", "configs": cfg} and b.sent[-1]["type"] == "filters"


def test_send_filters_survives_a_broken_socket():
    class Broken(FakeWs):
        async def send(self, text):
            raise ConnectionError("gone")

    cs = ControlServer(5005, on_event=lambda ev: None)
    ok = FakeWs()
    cs._sessions = {1: types.SimpleNamespace(ws=Broken()), 2: types.SimpleNamespace(ws=ok)}
    assert asyncio.run(cs.send_filters({})) == 1


def _closed_ws():
    import websockets

    class Closed(FakeWs):
        async def send(self, text):
            raise websockets.exceptions.ConnectionClosedError(None, None)
    return Closed()


def test_backfill_ack_and_resend_to_a_vanished_phone_do_not_raise():
    # QA 2026-10-02: a phone killed mid-session made send_backfill_ack raise ConnectionClosedError,
    # which escaped the reconcile loop and took the whole server down.
    cs = ControlServer(5005, on_event=lambda ev: None)
    cs._sessions = {7: types.SimpleNamespace(ws=_closed_ws())}
    assert asyncio.run(cs.send_backfill_ack(7, [(0, 100)])) is False
    assert asyncio.run(cs.send_resend(7, 0, 5, 9)) is False
