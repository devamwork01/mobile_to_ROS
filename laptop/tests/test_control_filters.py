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


def _run_phone(cs, messages):
    """Feed one phone connection the given JSON messages through ControlServer.handle."""
    class Ws(FakeWs):
        remote_address = ("1.2.3.4", 5555)

        def __init__(self, msgs):
            super().__init__()
            self._msgs = [json.dumps(m) for m in msgs]

        def __aiter__(self):
            return self

        async def __anext__(self):
            if not self._msgs:
                raise StopAsyncIteration
            return self._msgs.pop(0)
    ws = Ws(messages)
    asyncio.run(cs.handle(ws))
    return ws


def test_hello_caps_and_tune_messages_are_forwarded():
    events = []
    cs = ControlServer(5005, on_event=events.append)
    _run_phone(cs, [{"type": "hello", "model": "SM-S938B", "sensors": [], "caps": ["tune"]},
                    {"type": "tune_window", "id": 3, "windows": [{"handle": 0, "from_ns": 1, "to_ns": 2}]},
                    {"type": "tune_request"}])
    kinds = [e["kind"] for e in events]
    assert kinds[:3] == ["phone_connected", "tune_window", "tune_request"]
    assert events[0]["caps"] == ["tune"]
    assert events[1]["msg"]["id"] == 3 and events[1]["device_id"] == events[0]["device_id"]


def test_hello_without_caps_has_empty_caps():
    events = []
    _run_phone(ControlServer(5005, on_event=events.append), [{"type": "hello", "model": "x", "sensors": []}])
    assert events[0]["caps"] == []


def test_send_json_to_one_phone():
    cs = ControlServer(5005, on_event=lambda ev: None)
    a = FakeWs()
    cs._sessions = {7: types.SimpleNamespace(ws=a)}
    assert asyncio.run(cs.send_json(7, {"type": "tune_prompt", "id": 1})) is True
    assert a.sent[-1] == {"type": "tune_prompt", "id": 1}
    assert asyncio.run(cs.send_json(8, {"type": "x"})) is False
