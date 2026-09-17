import { useState, useEffect, lazy, Suspense } from "react";
import { Icons } from "./icons.js";
import NavRail from "./components/NavRail.jsx";
import SignalCard from "./components/SignalCard.jsx";
import SensorModal from "./components/SensorModal.jsx";
import PerfOverlay from "./components/PerfOverlay.jsx";
import ThemeToggle from "./components/ThemeToggle.jsx";
import { getStoredTheme, setTheme } from "./lib/theme.js";
import { netLoss } from "./lib/metrics.js";
// Heavy deps (three.js, uPlot) are code-split so the dashboard shell paints fast.
const Phone3D = lazy(() => import("./components/Phone3D.jsx"));
const GraphPanel = lazy(() => import("./components/GraphPanel.jsx"));
const RecordingsView = lazy(() => import("./components/RecordingsView.jsx"));

function Loading({ label = "Loading…" }) {
  return <div className="min-h-[200px] grid place-items-center text-sm text-faint">{label}</div>;
}
import { useTelemetry, sendCommand } from "./telemetry/store.js";
import { signalMeta } from "./telemetry/signals.js";

function Clock() {
  const [t, setT] = useState(() => new Date());
  useEffect(() => {
    const id = setInterval(() => setT(new Date()), 1000);
    return () => clearInterval(id);
  }, []);
  return <span className="num text-xs text-muted">{t.toLocaleTimeString([], { hour12: false })}</span>;
}

function StatusPill({ meta }) {
  const connected = !!meta.device;
  const wsOk = meta.status === "connected";
  const label = connected ? "Connected" : wsOk ? "Waiting for device" : "Disconnected";
  const color = connected ? "text-ok" : wsOk ? "text-warn" : "text-err";
  const dot = connected ? "bg-ok" : wsOk ? "bg-warn" : "bg-err";
  return (
    <div className="flex items-center gap-2 px-3 py-1.5 rounded-full bg-surface-2 border border-line">
      <span className={`w-2 h-2 rounded-full ${dot} ${connected ? "animate-pulsedot" : ""}`} />
      <span className={`text-xs font-medium ${color}`}>{label}</span>
    </div>
  );
}

function TopBar({ meta, view }) {
  const rec = meta.recording?.active;
  return (
    <header className="h-14 shrink-0 flex items-center gap-3 px-5 border-b border-line bg-surface/70 backdrop-blur">
      <h1 className="text-sm font-semibold">{view}</h1>
      <div className="ml-auto flex items-center gap-3">
        <button
          onClick={() => sendCommand({ cmd: rec ? "record_stop" : "record_start" })}
          className={`btn text-xs ${rec ? "bg-err/15 text-err border border-err/40" : "btn-ghost"}`}
        >
          <span className={`w-2 h-2 rounded-full bg-err ${rec ? "animate-pulsedot" : ""}`} />
          {rec ? `Stop · ${meta.recording.rows}` : "Record"}
        </button>
        <StatusPill meta={meta} />
        <Clock />
      </div>
    </header>
  );
}

function Panel({ title, children, className = "" }) {
  return (
    <section className={`panel p-4 ${className}`}>
      {title && <h2 className="text-xs font-semibold uppercase tracking-[0.12em] text-muted mb-3">{title}</h2>}
      {children}
    </section>
  );
}

function Empty({ icon, title, sub }) {
  const I = Icons[icon] || Icons.CircleDot;
  return (
    <div className="min-h-[200px] grid place-items-center text-center">
      <div className="text-faint">
        <I size={28} className="mx-auto mb-2 opacity-50" />
        <div className="text-sm font-medium text-muted">{title}</div>
        {sub && <div className="text-xs">{sub}</div>}
      </div>
    </div>
  );
}

function LiveGrid({ meta, onSelect, cols = "sm:grid-cols-2" }) {
  if (meta.active.length === 0)
    return <Empty icon="Radar" title="No sensors streaming" sub="Start streaming from the phone (or run --selftest)." />;
  return (
    <div className={`grid grid-cols-1 ${cols} gap-3`}>
      {meta.active.map(({ handle, type }) => (
        <SignalCard key={handle} handle={handle} type={type} onSelect={() => onSelect({ handle, type })} />
      ))}
    </div>
  );
}

function DiagRow({ label, value, tone }) {
  return (
    <div className="flex items-center justify-between py-2 border-b border-line/60 last:border-0">
      <span className="text-sm text-muted">{label}</span>
      <span className={`num text-sm ${tone || "text-fg"}`}>{value}</span>
    </div>
  );
}

function Diagnostics({ meta }) {
  const d = meta.debug || {};
  const s = meta.stats || {};
  const rate = s.bps ? (s.bps < 1048576 ? `${(s.bps / 1024).toFixed(0)} KB/s` : `${(s.bps / 1048576).toFixed(2)} MB/s`) : "—";
  const inN = s.ui_records_in, outN = s.ui_records_out;
  const coalesced = inN ? Math.round((1 - outN / inN) * 100) : null;
  return (
    <div className="grid md:grid-cols-2 gap-4">
      <Panel title="Connection">
        <DiagRow label="Status" value={meta.device ? "Connected" : "No device"} tone={meta.device ? "text-ok" : "text-muted"} />
        <DiagRow label="Device" value={meta.device ? `${meta.device.model} (Android ${meta.device.android})` : "—"} />
        <DiagRow label="Network latency (p50 / p95)" value={`${d.latency_ms_p50 ?? "—"} / ${d.latency_ms_p95 ?? "—"} ms`} />
        <DiagRow label="Jitter" value={`${d.jitter_ms ?? "—"} ms`} />
        <DiagRow label="Phone latency (acq→send)" value={`${d.phone_latency_ms_p50 ?? "—"} / ${d.phone_latency_ms_p95 ?? "—"} ms`} />
        <DiagRow label="Packet loss (net of backfill)" value={`${netLoss(d, s).pct} %`} tone={netLoss(d, s).netLost > 0 ? "text-warn" : "text-ok"} />
        <DiagRow label="Throughput" value={rate} />
      </Panel>
      <Panel title="Raw pipeline (recorded losslessly)">
        <DiagRow label="Active sensors" value={meta.active.length} />
        <DiagRow label="Packets received" value={s.packets ?? "—"} />
        <DiagRow label="Records received" value={d.received ?? s.records ?? "—"} />
        <DiagRow label="Lost samples (wire)" value={d.lost ?? "—"} tone={(d.lost || 0) > 0 ? "text-warn" : "text-fg"} />
        <DiagRow label="Reordered" value={d.reordered ?? "—"} />
        <DiagRow label="Decode errors" value={s.decode_errors ?? "—"} tone={(s.decode_errors || 0) > 0 ? "text-warn" : "text-fg"} />
        <DiagRow label="Recording" value={meta.recording?.active ? `Yes · ${meta.recording.rows} rows` : "No"} />
      </Panel>
      <Panel title="Presentation (browser stream)">
        <DiagRow label="UI rate cap" value={s.ui_hz ? `${s.ui_hz} Hz` : "—"} />
        <DiagRow label="Coalesced" value={coalesced != null ? `${coalesced} %` : "—"} tone="text-muted" />
        <DiagRow label="Forwarded to browser" value={outN != null ? outN.toLocaleString() : "—"} />
        <DiagRow label="Raw records seen" value={inN != null ? inN.toLocaleString() : "—"} />
        <div className="text-[11px] text-faint pt-2 leading-snug">
          Coalesced = presentation updates intentionally dropped to hold the UI rate — not lost data.
          Raw is recorded in full; wire loss is recovered by backfill (see below).
        </div>
      </Panel>
      <Panel title="Backfill (recording integrity)">
        <DiagRow label="Backfilled records" value={(s.backfilled ?? 0).toLocaleString()} tone={(s.backfilled || 0) > 0 ? "text-ok" : "text-fg"} />
        <DiagRow label="Permanent gaps" value={s.permanent_gaps ?? 0} tone={(s.permanent_gaps || 0) > 0 ? "text-warn" : "text-ok"} />
        <div className="text-[11px] text-faint pt-2 leading-snug">
          Backfilled = samples the live UDP stream missed that the phone resent from its on-phone
          recording, merged into the session (deduped by seq). Permanent gaps = ranges the phone
          could no longer serve (aged out of its ring) — the only irrecoverable data loss.
        </div>
      </Panel>
    </div>
  );
}

export default function App() {
  const meta = useTelemetry();
  const [view, setView] = useState("Dashboard");
  const [selected, setSelected] = useState(null);
  const [theme, setThemeState] = useState(() => getStoredTheme());
  const changeTheme = (p) => setThemeState(setTheme(p));
  const showPerf = typeof location !== "undefined" && new URLSearchParams(location.search).has("perf");

  const graphSensors = [];
  const seen = new Set();
  meta.active.forEach(({ type }) => {
    const k = signalMeta(type).kind;
    if ((k === "vector" || k === "orientation") && !seen.has(type)) {
      seen.add(type);
      graphSensors.push({ type, name: signalMeta(type).name });
    }
  });

  return (
    <div className="flex h-full bg-ink">
      <NavRail view={view} onView={setView} meta={meta} />
      <main className="flex-1 min-w-0 flex flex-col">
        <TopBar meta={meta} view={view} />
        <div className="flex-1 overflow-auto p-4 bg-hero-grad">
          {view === "Dashboard" && (
            <>
              <div className="grid grid-cols-1 xl:grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)] gap-4 items-start">
                <Panel title="3D Device Visualization" className="h-[460px] flex flex-col">
                  <Suspense fallback={<Loading label="Loading 3D…" />}>
                    <Phone3D />
                  </Suspense>
                </Panel>
                <Panel title="Live Sensor Data" className="max-h-[460px] overflow-y-auto">
                  <LiveGrid meta={meta} onSelect={setSelected} />
                </Panel>
              </div>
              <Panel title="Real-Time Graphs" className="mt-4">
                <Suspense fallback={<Loading label="Loading graphs…" />}>
                  <GraphPanel sensors={graphSensors} />
                </Suspense>
              </Panel>
            </>
          )}

          {view === "Sensors" && (
            <Panel title="Streaming Signals">
              <LiveGrid meta={meta} onSelect={setSelected} cols="sm:grid-cols-2 xl:grid-cols-3" />
            </Panel>
          )}

          {view === "Diagnostics" && <Diagnostics meta={meta} />}

          {view === "Recordings" && (
            <Suspense fallback={<Loading label="Loading recordings…" />}>
              <RecordingsView />
            </Suspense>
          )}

          {view === "Settings" && (
            <div className="grid md:grid-cols-2 gap-4 max-w-3xl">
              <Panel title="Appearance">
                <div className="flex items-center justify-between gap-4 flex-wrap">
                  <div>
                    <div className="text-sm text-fg">Theme</div>
                    <div className="text-xs text-muted">System follows your OS setting.</div>
                  </div>
                  <ThemeToggle value={theme} onChange={changeTheme} />
                </div>
              </Panel>
              <Panel title="Connection">
                <DiagRow label="Dashboard host" value={typeof location !== "undefined" ? location.host : "—"} />
                <DiagRow label="Phone connected" value={meta.device ? "Yes" : "No"} tone={meta.device ? "text-ok" : "text-muted"} />
                <DiagRow label="UI update cap" value={meta.stats?.ui_hz ? `${meta.stats.ui_hz} Hz` : "—"} />
                <DiagRow label="Recording" value={meta.recording?.active ? `Yes · ${meta.recording.rows} rows` : "No"} />
                <div className="text-[11px] text-faint pt-2 leading-snug">
                  Ports and capture options are set with server flags (see README). This panel is read-only.
                </div>
              </Panel>
            </div>
          )}
        </div>
      </main>

      {selected && <SensorModal sensor={selected} catalog={meta.catalog} onClose={() => setSelected(null)} />}
      {showPerf && <PerfOverlay />}
    </div>
  );
}
