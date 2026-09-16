"""Asyncio UDP telemetry receiver.

Binds a datagram socket and decodes each packet on arrival, stamping the
laptop-side receive time (monotonic ns) for latency estimation. Decoding
failures are counted and reported but never crash the receiver — UDP is lossy
and we may occasionally see a corrupt or partial datagram.
"""

from __future__ import annotations

import asyncio
import socket
import time
from typing import Callable, Optional, Tuple

from .protocol import Datagram, ProtocolError, decode_datagram

# on_datagram(datagram, addr, t_recv_ns)
DatagramCallback = Callable[[Datagram, Tuple[str, int], int], None]
ErrorCallback = Callable[[Exception, Tuple[str, int], bytes], None]

_DEFAULT_RCVBUF = 1 << 20  # 1 MiB kernel receive buffer for high-rate bursts


class TelemetryReceiver(asyncio.DatagramProtocol):
    def __init__(self, on_datagram: DatagramCallback, on_error: Optional[ErrorCallback] = None):
        self._on_datagram = on_datagram
        self._on_error = on_error
        self.transport: Optional[asyncio.DatagramTransport] = None
        # counters (read by the stats task)
        self.packets = 0
        self.bytes = 0
        self.records = 0
        self.decode_errors = 0

    def connection_made(self, transport: asyncio.BaseTransport) -> None:
        self.transport = transport  # type: ignore[assignment]

    def datagram_received(self, data: bytes, addr: Tuple[str, int]) -> None:
        t_recv_ns = time.monotonic_ns()
        self.packets += 1
        self.bytes += len(data)
        try:
            dg = decode_datagram(data)
        except ProtocolError as exc:
            self.decode_errors += 1
            if self._on_error is not None:
                self._on_error(exc, addr, data)
            return
        self.records += len(dg.records)
        self._on_datagram(dg, addr, t_recv_ns)

    def error_received(self, exc: Exception) -> None:  # pragma: no cover - platform dependent
        # On Windows an ICMP "port unreachable" can surface here; ignore.
        pass


async def start_receiver(
    host: str,
    port: int,
    on_datagram: DatagramCallback,
    on_error: Optional[ErrorCallback] = None,
    rcvbuf: int = _DEFAULT_RCVBUF,
) -> Tuple[asyncio.DatagramTransport, TelemetryReceiver]:
    """Bind and start a UDP receiver. Pass ``port=0`` for an ephemeral port
    (read it back via ``transport.get_extra_info('socket').getsockname()``).
    """
    loop = asyncio.get_running_loop()
    transport, protocol = await loop.create_datagram_endpoint(
        lambda: TelemetryReceiver(on_datagram, on_error),
        local_addr=(host, port),
    )
    sock = transport.get_extra_info("socket")
    if sock is not None:
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, rcvbuf)
        except OSError:
            pass
        # Best-effort QoS: mark this socket's outbound datagrams Expedited Forwarding (DSCP 46 ->
        # ToS 0xB8), symmetric with the phone's telemetry class. Honored on Linux; Windows userspace
        # generally ignores IP_TOS (the laptop is mostly a receiver, so this is a minor add).
        try:
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_TOS, 0xB8)
        except OSError:
            pass
    return transport, protocol  # type: ignore[return-value]
