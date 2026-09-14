# ADR-001 — Dashboard performance architecture

Status: **Accepted** (2026-09-14) · Supersedes the generic checklist in
[`../High-Frequency Sensor Streaming — Performance Architecture Directions.md`](../High-Frequency%20Sensor%20Streaming%20—%20Performance%20Architecture%20Directions.md),
which was revised to match this decision.

## Context

The dashboard showed size-dependent lag and scroll-freeze under many active sensors.
A proposal suggested a browser-side high-frequency data plane (WebSocket→Worker,
client ring buffers of ~1M samples, client-side LOD, list virtualization of 1M rows,
and later OffscreenCanvas / SharedArrayBuffer / WebGPU).

Key fact that reframes the problem: **the high-frequency pipeline already lives in the
Python backend.** `laptop/sensorstream/` decodes, syncs, and records the raw stream at
full rate; `DashboardSink` **decimates per-sensor to `max_ui_hz` (30 Hz)** before the
browser sees anything (`sinks.py`), and the raw stream flows **unthrottled to the
loggers** (`.ssbin` + `.csv`). The browser has never received raw data. Raw fidelity is
preserved server-side.

The recent lag was therefore not a data-volume problem in the browser; it was:
1. a **layout** bug (the sensor-card column stretched the WebGL canvas to ~3.7 MP), and
2. **paint/re-render** cost (every card re-rendering at stream rate inside a scroll
   container, all cards painted each scroll frame).

Both are fixed (bounded canvas ~0.52 MP + 30 fps; per-card re-render throttle ~10 Hz +
`content-visibility:auto`).

## Decision

Keep the browser a **thin presentation layer** and put scalability work at the
**server boundary**, not in a duplicated browser data plane.

**Architectural fork — "does the browser need full-rate raw data?" → No.**
30 Hz live + server-side recordings is sufficient. Therefore we will **not** build:
WebSocket→Worker, client 1M-sample buffers, SharedArrayBuffer, OffscreenCanvas,
multi-worker pipelines, or WebGPU — unless a *foreground* DevTools trace later proves a
specific browser bottleneck (revisit trigger below).

We **will** implement, in priority order:

1. **(done)** Preserve the server/browser data boundary. No raw data in React state;
   external store + selective/imperative subscriptions.
2. **(done / ongoing)** Eliminate unnecessary UI/layout/paint work (re-renders, DOM
   size, `content-visibility`, bounded canvas).
3. **Server-side LOD** — range/aggregation queries for historical & replay graphs.
4. **Efficient Recordings UI** — server-side listing + pagination; client virtualization
   only if a measured need appears.
5. **Profile again** (foreground trace) before any further optimization.
6. **Advanced browser architecture** — only if step 5 justifies it.

## Server API (new)

HTTP, served by the existing dashboard HTTP server (JSON; read-only; filesystem-backed,
so it runs off the asyncio loop safely):

- `GET /api/recordings`
  → `[{ id, name, sizeBytes, modified, durationMs?, device?, sensors:[{handle,type,name,unit}] }]`
  Cheap: filesystem stat + `.meta.json`; sensors fall back to peeking the first datagram
  when no meta file exists.

- `GET /api/recordings/{id}/signals/{handle}?start={ns}&end={ns}&buckets={n}`
  → `{ handle, type, unit, start, end, buckets, points:[[t, min, max, first, last, avg], …] }`
  Server reads the `.ssbin`, buckets the requested range into `n` buckets (n chosen by the
  client from graph pixel width), and returns **min/max/first/last/avg per bucket** so
  transient spikes survive aggregation (never a bare average). Zooming issues a new query
  with a narrower range → higher effective resolution; fully zoomed in returns raw points.

Resolution rule (client): `buckets ≈ min(rawCountInRange, graphWidthPx)`.

## Delivery-semantics contract (documented, §10)

| Stream | Guarantee |
|---|---|
| Raw recording (`.ssbin`/`.csv`) | **Lossless** |
| Server processing / sync | **Lossless** |
| Dashboard cards | **Latest value** (~10 Hz UI refresh) |
| Live graphs | **Coalesced** at presentation rate (server decimates to `max_ui_hz`) |
| Historical graphs | **Server-side LOD** (min/max preserved) |

The dashboard WebSocket broadcast is **fire-and-forget** with `max_queue` on the server;
a slow browser drops **presentation** updates — never raw samples. Diagnostics will
surface: stream latency, packet loss (raw, from seq gaps), coalesced/decimated ratio,
recording status, connection state — and must not conflate dropped presentation updates
with lost raw data.

## Consequences

- The browser never holds a whole recording; it holds the live window (~6 k points) and
  whatever LOD buckets the current view needs (~a few thousand).
- The Python backend gains a small read-only query surface over its own log files
  (leverages the format it already owns; no new storage system).
- We accept blocking file reads in the HTTP worker threads for now; if large recordings
  make queries slow, add an index/cache — profile first.

## Revisit trigger

Reopen the "raw in browser" fork (ADR-002) only if a concrete requirement appears —
live zoom-to-raw, in-browser signal processing, or offline raw analysis — **and** a
foreground DevTools trace shows the 30 Hz path is the bottleneck. At that point the
worker + typed-array/ring-buffer plane becomes justified.
