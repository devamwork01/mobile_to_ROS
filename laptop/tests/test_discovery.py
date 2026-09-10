"""Discovery advertisement tests: beacon payload/parse, loopback send-recv, and
mDNS service registration."""

from __future__ import annotations

import socket

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
    adv = Advertiser(ip="127.0.0.1", control_port=8081, udp_port=5005)
    try:
        adv.start_mdns()
        assert adv._info is not None
        assert adv._info.port == 8081
        assert adv._info.properties[b"control_port"] == b"8081"
        assert adv._info.properties[b"path"] == b"/phone"
    finally:
        adv.stop()  # must not raise
