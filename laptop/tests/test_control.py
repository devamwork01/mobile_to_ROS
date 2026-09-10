"""Control-channel end-to-end test: a simulated phone connects to ``/phone`` and
runs the full handshake, while a dashboard client on ``/ui`` verifies every phone
event is mirrored to it. Ephemeral ports; driven with asyncio.run.
"""

from __future__ import annotations

import asyncio
import json
import os

import websockets

from sensorstream.control import ControlServer
from sensorstream.dashboard import DashboardServer

WEB_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "web"))


async def _read_until(ws, kind: str, timeout: float = 3.0) -> dict:
    loop = asyncio.get_event_loop()
    end = loop.time() + timeout
    while True:
        remaining = end - loop.time()
        if remaining <= 0:
            raise asyncio.TimeoutError(f"no dashboard event of kind {kind!r}")
        msg = json.loads(await asyncio.wait_for(ws.recv(), timeout=remaining))
        if msg.get("kind") == kind:
            return msg


def test_control_handshake_and_dashboard_mirroring():
    async def run():
        dash = DashboardServer(WEB_DIR, "127.0.0.1", 0, "127.0.0.1", 0)
        await dash.start()
        control = ControlServer(udp_port=5005, on_event=dash.broadcast)
        dash.control_handler = control.handle

        r: dict = {}
        async with websockets.connect(f"ws://127.0.0.1:{dash.ws_port}/ui") as ui:
            for _ in range(200):
                if dash.clients:
                    break
                await asyncio.sleep(0.01)
            assert dash.clients, "dashboard client never registered"

            async with websockets.connect(f"ws://127.0.0.1:{dash.ws_port}/phone") as phone:
                await phone.send(json.dumps({
                    "type": "hello", "model": "SM-S938B", "android": "15", "app_version": "0.1.0",
                    "sensors": [{"handle": 0, "name": "LSM6DSO Accelerometer", "type": 1, "units": "m/s²"}],
                }))
                r["ack"] = json.loads(await asyncio.wait_for(phone.recv(), timeout=3))
                device_id = r["ack"]["device_id"]

                r["connected"] = await _read_until(ui, "phone_connected")

                await phone.send(json.dumps({"type": "heartbeat", "seq": 7}))
                r["hb"] = json.loads(await asyncio.wait_for(phone.recv(), timeout=3))

                await phone.send(json.dumps({"type": "clock_ping", "t0": 123456}))
                r["pong"] = json.loads(await asyncio.wait_for(phone.recv(), timeout=3))

                await phone.send(json.dumps({"type": "stats", "sent": 10, "dropped": 0}))
                r["stats"] = await _read_until(ui, "phone_stats")

                r["cfg_ok"] = await control.configure(
                    device_id, {"sensors": [{"handle": 0, "enabled": True, "period_us": 10000}]}
                )
                r["cfg_msg"] = json.loads(await asyncio.wait_for(phone.recv(), timeout=3))
                r["session_present"] = device_id in control.sessions

            r["disconnected"] = await _read_until(ui, "phone_disconnected")

        await dash.stop()
        return r

    r = asyncio.run(run())

    assert r["ack"]["type"] == "hello_ack"
    assert r["ack"]["device_id"] >= 1
    assert r["ack"]["udp_port"] == 5005

    assert r["connected"]["model"] == "SM-S938B"
    assert r["connected"]["sensors"][0]["name"] == "LSM6DSO Accelerometer"

    assert r["hb"]["type"] == "heartbeat_ack" and r["hb"]["seq"] == 7

    assert r["pong"]["type"] == "clock_pong"
    assert r["pong"]["t0"] == 123456
    assert "t_server_ns" in r["pong"]

    assert r["stats"]["stats"]["sent"] == 10

    assert r["cfg_ok"] is True
    assert r["cfg_msg"]["type"] == "configure"
    assert r["cfg_msg"]["sensors"][0]["period_us"] == 10000
    assert r["session_present"] is True

    assert r["disconnected"]["device_id"] == r["ack"]["device_id"]
