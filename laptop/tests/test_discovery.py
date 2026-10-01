"""Discovery advertisement tests: beacon payload/parse, loopback send-recv, and
mDNS service registration."""

from __future__ import annotations

import socket
import uuid

from sensorstream.discovery import Advertiser, BEACON_MAGIC, beacon_payload, parse_beacon


def test_beacon_payload_roundtrip():
    m = parse_beacon(beacon_payload(8081, 5005))
    assert m["service"] == BEACON_MAGIC
    assert m["control_port"] == 8081
    assert m["udp_port"] == 5005
    assert parse_beacon(b"garbage") is None
    assert parse_beacon(b'{"service":"other"}') is None


def test_beacon_send_recv_loopback():
    rx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    rx.bind(("127.0.0.1", 0))
    rx.settimeout(2.0)
    port = rx.getsockname()[1]
    adv = Advertiser(ip="127.0.0.1", control_port=8081, udp_port=5005, beacon_port=port, broadcast_addr="127.0.0.1")
    try:
        adv.send_beacon()
        data, addr = rx.recvfrom(1024)
        m = parse_beacon(data)
        assert m and m["control_port"] == 8081 and m["udp_port"] == 5005
        assert addr[0] == "127.0.0.1"  # the sender IP the phone would connect to
    finally:
        adv.stop()
        rx.close()


def test_mdns_register_and_unregister():
    # A fixed instance name (the Advertiser's own default, "SensorStream") would collide
    # with a real sensorstream.app already running and advertising under that same name on
    # the network -- mDNS enforces the full service name be unique network-wide, not just
    # within this test's own Zeroconf instance, so registration would fail with
    # NonUniqueNameException. Use a unique name so this test is isolated from anything else
    # on the network, the same way test_beacon_send_recv_loopback uses an ephemeral port.
    instance = f"pytest-{uuid.uuid4().hex[:8]}"
    adv = Advertiser(ip="127.0.0.1", control_port=8081, udp_port=5005, instance=instance)
    try:
        adv.start_mdns()
        assert adv._info is not None
        assert adv._info.port == 8081
        assert adv._info.properties[b"control_port"] == b"8081"
        assert adv._info.properties[b"path"] == b"/phone"
    finally:
        adv.stop()  # must not raise


# --- Servers list: identity in the beacon + per-server ping --------------------------------------

from sensorstream.discovery import ServerIdentity, detect_kind, probe_reply, PROBE_MAGIC


def test_beacon_carries_identity():
    ident = ServerIdentity(name="lab-pc", kind="desktop", os="Linux", session="ab12cd34")
    m = parse_beacon(beacon_payload(8081, 5005, ident))
    assert m["v"] == 2
    assert (m["name"], m["kind"], m["os"], m["id"]) == ("lab-pc", "desktop", "Linux", "ab12cd34")
    assert m["probe_port"] == 8081  # the ping is answered on UDP <control port>


def test_beacon_without_identity_stays_compatible():
    m = parse_beacon(beacon_payload(8081, 5005))
    assert m["control_port"] == 8081 and "name" not in m


def test_identity_defaults_and_custom_name():
    a = ServerIdentity.create()
    b = ServerIdentity.create(name="robot-base")
    assert a.name and a.os and a.kind in ("laptop", "desktop", "raspberry_pi")
    assert len(a.session) == 8 and a.session != b.session
    assert b.name == "robot-base"


def test_detect_kind():
    assert detect_kind(board_model="Raspberry Pi 5 Model B Rev 1.0", has_battery=False) == "raspberry_pi"
    assert detect_kind(board_model=None, has_battery=True) == "laptop"
    assert detect_kind(board_model=None, has_battery=False) == "desktop"
    assert detect_kind(board_model="", has_battery=None) == "desktop"


def test_probe_reply_echoes_seq_with_server_id():
    import json
    out = probe_reply(json.dumps({"service": PROBE_MAGIC, "probe": 7}).encode(), "ab12cd34")
    m = json.loads(out)
    assert m == {"service": PROBE_MAGIC, "probe": 7, "id": "ab12cd34"}
    assert probe_reply(b"garbage", "x") is None
    assert probe_reply(b'{"service":"sensorstream"}', "x") is None  # a beacon, not a probe
    assert probe_reply(b'{"service":"other","probe":1}', "x") is None


def test_probe_responder_answers_over_udp():
    import asyncio, json
    from sensorstream.discovery import start_probe_responder

    async def run():
        transport = await start_probe_responder("127.0.0.1", 0, "ab12cd34")
        port = transport.get_extra_info("sockname")[1]
        c = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        c.setblocking(False)
        try:
            c.sendto(json.dumps({"service": PROBE_MAGIC, "probe": 3}).encode(), ("127.0.0.1", port))
            loop = asyncio.get_running_loop()
            data = await asyncio.wait_for(loop.sock_recv(c, 1024), 2.0)
            return json.loads(data)
        finally:
            c.close()
            transport.close()

    assert asyncio.run(run()) == {"service": PROBE_MAGIC, "probe": 3, "id": "ab12cd34"}


def test_mdns_registers_from_inside_the_server_event_loop():
    # The server runs in asyncio; zeroconf's blocking register call raised EventLoopBlocked
    # there, so mDNS silently never worked (only the UDP beacon did).
    import asyncio

    async def run():
        adv = Advertiser(ip="127.0.0.1", control_port=8081, udp_port=5005, instance=f"pytest-{uuid.uuid4().hex[:8]}")
        try:
            await adv.start_mdns_async()
            return adv._info is not None
        finally:
            await adv.stop_async()

    assert asyncio.run(run())
