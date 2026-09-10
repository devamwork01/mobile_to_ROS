"""Dashboard transport: a WebSocket server the browser subscribes to, plus a
static file server for the ``web/`` assets.

Two ports keep concerns simple: the page is served over HTTP; live data arrives
over WebSocket. Both default to ``0.0.0.0`` so the dashboard can be opened from
another device on the LAN. ``broadcast`` is fire-and-forget (via
``websockets.broadcast``) so a slow browser tab never stalls the pipeline.
"""

from __future__ import annotations

import functools
import http.server
import json
import socketserver
import threading
from typing import Awaitable, Callable, Optional, Set

import websockets


def _ws_path(ws) -> str:
    req = getattr(ws, "request", None)
    if req is not None:
        return getattr(req, "path", "/")
    return getattr(ws, "path", "/")


class _QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *args, **kwargs):  # silence per-request stderr logging
        pass


class _ReusableTCPServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


class DashboardServer:
    def __init__(self, web_dir: str, ws_host: str, ws_port: int, http_host: str, http_port: int):
        self.web_dir = web_dir
        self.ws_host = ws_host
        self.ws_port = ws_port
        self.http_host = http_host
        self.http_port = http_port
        self.clients: Set = set()
        # Set by app.py: coroutine handling a "/phone" control connection.
        self.control_handler: Optional[Callable[[object], Awaitable[None]]] = None
        # Set by app.py: callback(dict) for {"cmd": ...} messages from the dashboard.
        self.on_ui_command: Optional[Callable[[dict], None]] = None
        self._ws_server: Optional[websockets.Server] = None
        self._httpd: Optional[_ReusableTCPServer] = None

    async def start(self) -> None:
        self._ws_server = await websockets.serve(
            self._handler, self.ws_host, self.ws_port, ping_interval=20, ping_timeout=20, max_queue=64
        )
        # Resolve the actually-bound port (supports ws_port=0 in tests).
        for sock in self._ws_server.sockets:
            self.ws_port = sock.getsockname()[1]
            break
        self._start_http()

    def _start_http(self) -> None:
        handler = functools.partial(_QuietHandler, directory=self.web_dir)
        self._httpd = _ReusableTCPServer((self.http_host, self.http_port), handler)
        self.http_port = self._httpd.server_address[1]
        threading.Thread(target=self._httpd.serve_forever, name="http-static", daemon=True).start()

    async def _handler(self, ws) -> None:
        # Route the phone control channel to the control server; everything else
        # is a dashboard subscriber.
        if self.control_handler is not None and _ws_path(ws).startswith("/phone"):
            await self.control_handler(ws)
            return
        self.clients.add(ws)
        try:
            async for raw in ws:  # dashboard may send {"cmd": ...} (e.g. record toggle)
                if self.on_ui_command is None:
                    continue
                try:
                    msg = json.loads(raw)
                except (ValueError, TypeError):
                    continue
                if isinstance(msg, dict) and "cmd" in msg:
                    try:
                        self.on_ui_command(msg)
                    except Exception:
                        pass
        except Exception:
            pass
        finally:
            self.clients.discard(ws)

    def broadcast(self, obj: dict) -> None:
        if not self.clients:
            return
        websockets.broadcast(self.clients, json.dumps(obj, separators=(",", ":")))

    async def stop(self) -> None:
        if self._ws_server is not None:
            self._ws_server.close()
            await self._ws_server.wait_closed()
        if self._httpd is not None:
            self._httpd.shutdown()
            self._httpd.server_close()
