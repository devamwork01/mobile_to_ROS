"""End-to-end laptop pipeline test: UDP datagram -> receiver -> DashboardSink ->
WebSocket broadcast -> browser-style client, plus a static HTTP fetch of the
dashboard page. Uses ephemeral ports so it never collides with a running app.
"""

from __future__ import annotations

import asyncio
import json
import os
import socket
import urllib.request

import websockets

from sensorstream import protocol as p
from sensorstream.dashboard import DashboardServer
from sensorstream.receiver import start_receiver
from sensorstream.sinks import DashboardSink

WEB_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "web"))


def test_pipeline_udp_to_websocket_and_static_page():
    async def run():
        dash = DashboardServer(WEB_DIR, "127.0.0.1", 0, "127.0.0.1", 0)
        await dash.start()
        sink = DashboardSink(dash.broadcast, max_ui_hz=1000.0)
        transport, proto = await start_receiver(
            "127.0.0.1", 0, lambda dg, addr, t: sink.on_datagram(dg, addr, t)
        )
        udp_port = transport.get_extra_info("socket").getsockname()[1]

        result = {}
        try:
            async with websockets.connect(f"ws://127.0.0.1:{dash.ws_port}") as client:
                # Wait until the server-side handler has registered this client.
                for _ in range(200):
                    if dash.clients:
                        break
                    await asyncio.sleep(0.01)
                assert dash.clients, "client never registered"

                s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                for i in range(5):
                    dg = p.Datagram(
                        device_id=0xABCD,
                        records=[p.Record(1, 0, i, 1_000_000_000 + i * 10_000_000, 3, [0.1, 9.8, 0.2])],
                    )
                    s.sendto(p.encode_datagram(dg), ("127.0.0.1", udp_port))
                s.close()

                msg = json.loads(await asyncio.wait_for(client.recv(), timeout=3.0))
                result["ws"] = msg

            # Static page served over HTTP.
            loop = asyncio.get_running_loop()
            body = await loop.run_in_executor(
                None, lambda: urllib.request.urlopen(f"http://127.0.0.1:{dash.http_port}/index.html", timeout=3).read()
            )
            result["http"] = body
        finally:
            transport.close()
            await dash.stop()
        return result

    result = asyncio.run(run())
    msg = result["ws"]
    assert msg["kind"] == "data"
    assert msg["device_id"] == 0xABCD
    assert msg["records"][0]["type"] == 1
    assert msg["records"][0]["v"][1] == 9.8
    assert b"PHONE SENSOR TELEMETRY" in result["http"]


def test_dashboard_ui_command_dispatch():
    """A dashboard client's {"cmd": ...} message reaches on_ui_command (Record-button plumbing)."""
    async def run():
        dash = DashboardServer(WEB_DIR, "127.0.0.1", 0, "127.0.0.1", 0)
        got = []
        dash.on_ui_command = lambda m: got.append(m)
        await dash.start()
        try:
            async with websockets.connect(f"ws://127.0.0.1:{dash.ws_port}/ui") as client:
                for _ in range(200):
                    if dash.clients:
                        break
                    await asyncio.sleep(0.01)
                await client.send(json.dumps({"cmd": "record_start", "extra": 1}))
                for _ in range(200):
                    if got:
                        break
                    await asyncio.sleep(0.01)
        finally:
            await dash.stop()
        return got

    got = asyncio.run(run())
    assert got and got[0]["cmd"] == "record_start"


def test_page_is_revalidated_but_hashed_assets_are_cached(tmp_path):
    # After a dashboard update the browser must fetch the new index.html (it names the new
    # hashed bundles); the hashed files themselves never change and can be cached for good.
    (tmp_path / "index.html").write_text("<html></html>")
    (tmp_path / "assets").mkdir()
    (tmp_path / "assets" / "index-abc123.js").write_text("x")

    async def run():
        dash = DashboardServer(str(tmp_path), "127.0.0.1", 0, "127.0.0.1", 0)
        await dash.start()
        try:
            loop = asyncio.get_running_loop()
            get = lambda path: urllib.request.urlopen(f"http://127.0.0.1:{dash.http_port}{path}", timeout=3)
            root = await loop.run_in_executor(None, lambda: get("/"))
            page = await loop.run_in_executor(None, lambda: get("/index.html"))
            asset = await loop.run_in_executor(None, lambda: get("/assets/index-abc123.js"))
            return root.headers.get("Cache-Control"), page.headers.get("Cache-Control"), asset.headers.get("Cache-Control")
        finally:
            await dash.stop()

    root, page, asset = asyncio.run(run())
    assert root == "no-cache"
    assert page == "no-cache"
    assert asset == "public, max-age=31536000, immutable"


def test_page_tells_the_dashboard_its_own_websocket_port(tmp_path):
    # QA 2026-10-02: the page always connected to ws :8081 unless ?ws= was given, so a server on
    # other ports served a dashboard that talked to a different server.
    (tmp_path / "index.html").write_text("<html><head><title>x</title></head><body></body></html>")

    async def run():
        dash = DashboardServer(str(tmp_path), "127.0.0.1", 0, "127.0.0.1", 0)
        await dash.start()
        try:
            loop = asyncio.get_running_loop()
            get = lambda path: urllib.request.urlopen(f"http://127.0.0.1:{dash.http_port}{path}", timeout=3)
            root = await loop.run_in_executor(None, lambda: get("/"))
            page = await loop.run_in_executor(None, lambda: get("/index.html?ws=1"))
            return dash.ws_port, root.read().decode(), root.headers.get("Cache-Control"), page.read().decode()
        finally:
            await dash.stop()

    port, root, cache, page = asyncio.run(run())
    tag = f"<script>window.__SS_WS_PORT__={port}</script></head>"
    assert tag in root and tag in page
    assert cache == "no-cache"
