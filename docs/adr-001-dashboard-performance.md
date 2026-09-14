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
full rate; `DashboardSink` **decimates per-sensor to `max_ui_hz`** (default now 60 Hz,
originally 30) before the browser sees anything (`sinks.py`), and the raw stream flows
**unthrottled to the loggers** (`.ssbin` + `.csv`). The browser has never received raw
data. Raw fidelity is preserved server-side.

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
2. **(done)** Eliminate unnecessary UI/layout/paint work (re-renders, DOM size,
   `content-visibility`, bounded canvas, code-split heavy deps).
3. **(done)** Server-side LOD — range/aggregation queries for historical & replay graphs.
4. **(partial)** Efficient Recordings UI — server-side listing + LOD chart done;
   pagination/virtualization deferred until the list is large enough to need it.
5. **(ongoing)** Profile with a foreground trace before any further optimization.
6. **(not started)** Advanced browser architecture — only if step 5 justifies it.

See **Implementation status** below for what shipped and the profiling that drove it.

## Server API (new)

HTTP, served by the existing dashboard HTTP server (JSON; read-only; filesystem-backed,
so it runs off the asyncio loop safely):

- `GET /api/recordings`
  → `[{ id, name, sizeBytes, modified, durationMs?, device?, sensors:[{handle,type,name,unit}] }]`
  Cheap: filesystem stat + `.meta.json`; sensors fall back to peeking the first datagram
  when no meta file exists.

- `GET /api/recordings/{id}/signals/{handle}?start={ns}&end={ns}&buckets={n}`
  → column-oriented for uPlot (as built):
  `{ handle, type, ncomp, raw, buckets, start, end,
     t:[sec from range start], avg:[[…per comp]], min:[[…]], max:[[…]] }`
  Server reads the `.ssbin`, buckets the requested range into `n` buckets (n chosen by the
  client from graph pixel width), and keeps **min/max/avg per component per bucket** so
  transient spikes survive aggregation (never a bare average). When the range holds fewer
  raw samples than `n`, each bucket is one sample (raw resolution). `first`/`last` are
  computed internally and can be added to the response if a view needs them.

Resolution rule (client): `buckets ≈ min(rawCountInRange, graphWidthPx)`. Zooming issues a
new query with a narrower range → higher effective resolution (see follow-ups: server
**re-query on zoom** is still pending; native visual zoom works on loaded buckets today).

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

## Implementation status (updated 2026-09-14)

Shipped:

- **Server LOD API** — `recordings.py` (`list_recordings` + `query_signal` with
  min/max/avg buckets), wired as read-only `GET /api/…` routes on the dashboard HTTP
  server; `.meta.json` (device + catalog) now written on record start so handles map to
  names. Verified via curl (6,812 raw → N buckets; 404 on unknown; static unaffected).
- **Recordings view** — `RecordingsView.jsx` + `HistoryChart.jsx` (uPlot: avg line per
  component X=red/Y=green/Z=blue with a min/max envelope band), consuming the API. The
  browser never downloads raw samples.
- **Dashboard perf** — bounded 3D canvas (~0.52 MP, 30 fps, 512 shadow maps), per-card
  re-render throttle (~10 Hz), `content-visibility:auto` on cards, off-screen render pause
  (IntersectionObserver), and **code-splitting** (three.js / uPlot lazy-loaded → initial
  JS 716 KB → 174 KB).
- **Visualization scheduler** (`renderBudget.js`) — lightweight shared budget: one
  capture-phase scroll listener → `frameIntervalMs('3d'|'plot')`, the single place FPS
  targets live. Supports scroll-degradation (drop during scroll, never freeze), but
  foreground testing showed **constant 60 fps scrolls smoothly on the test hardware**, so
  the validated config is 60 idle **and** during scroll (no degradation); the degrade path
  is one line away if a weaker GPU needs it. Off-screen viz still fully paused (§8). Plots
  reach 60 because `max_ui_hz` is now **60** (server default raised from 30 after validation;
  raw logging stays full-rate). Dev HUD behind `?perf`; §7 guard warns past ~0.6 MP.

Deferred (P4/P5): server **re-query on zoom** (higher-res on zoom-in), recordings
**pagination/virtualization** (only once the list is large), and **health metrics** in
Diagnostics (§10: coalesced-vs-lost, queue depth).

## Profiling findings & follow-through

- **The "scroll freeze" was two different things.** (1) The initial "freeze" was the
  716 KB bundle (three.js ≈ 85%) parsing on the main thread for ~0.6 s on load — a single
  long task that froze everything, scroll included. Fixed by code-splitting. (2) Genuine
  scroll stutter was **paint/compositing-bound, not JS** — a foreground-style measurement
  showed **zero long tasks and 2.8 ms reflow while scrolling**; the cost was the WebGL/uPlot
  canvases redrawing while the compositor scrolled.
- **A during-scroll render pause was tried and reverted, then replaced by the scheduler.**
  Fully pausing canvas redraw during scroll made scrolling smooth but **froze the visible
  3D/plot** — a worse trade. The scheduler replaced it with adjustable per-viz frame rates.
  Empirically, this hardware scrolls smoothly at **constant 60** (no degradation needed), so
  that's the shipped setting; the scheduler keeps the degrade-during-scroll path one line
  away for weaker GPUs. Off-screen viz is still fully paused (different from freezing visible
  content). `max_ui_hz` was raised 30 → 60 so plots match the 60 fps 3D.
- **Measurement caveat.** Chrome throttles `requestAnimationFrame` on backgrounded/occluded
  tabs, so automation-tab FPS is unreliable; foreground DevTools Performance traces (or
  buffered `longtask`/navigation entries) are the source of truth. This is why §11/P5 is
  "profile in the foreground."

## Revisit trigger

Reopen the "raw in browser" fork (ADR-002) only if a concrete requirement appears —
live zoom-to-raw, in-browser signal processing, or offline raw analysis — **and** a
foreground DevTools trace shows the 30 Hz path is the bottleneck. At that point the
worker + typed-array/ring-buffer plane becomes justified.
