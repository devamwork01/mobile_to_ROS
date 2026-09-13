import { useState, useEffect } from "react";
import { Icons } from "./icons.js";
import NavRail from "./components/NavRail.jsx";
import SignalCard from "./components/SignalCard.jsx";
import Phone3D from "./components/Phone3D.jsx";
import GraphPanel from "./components/GraphPanel.jsx";
import SensorModal from "./components/SensorModal.jsx";
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
  return (
    <div className="grid md:grid-cols-2 gap-4">
      <Panel title="Connection">
        <DiagRow label="Status" value={meta.device ? "Connected" : "No device"} tone={meta.device ? "text-ok" : "text-muted"} />
        <DiagRow label="Device" value={meta.device ? `${meta.device.model} (Android ${meta.device.android})` : "—"} />
        <DiagRow label="Network latency (p50 / p95)" value={`${d.latency_ms_p50 ?? "—"} / ${d.latency_ms_p95 ?? "—"} ms`} />
        <DiagRow label="Jitter" value={`${d.jitter_ms ?? "—"} ms`} />
        <DiagRow label="Phone latency (acq→send)" value={`${d.phone_latency_ms_p50 ?? "—"} / ${d.phone_latency_ms_p95 ?? "—"} ms`} />
        <DiagRow label="Packet loss" value={`${d.loss_pct ?? 0} %`} tone={(d.loss_pct || 0) > 1 ? "text-warn" : "text-fg"} />
        <DiagRow label="Throughput" value={rate} />
      </Panel>
      <Panel title="Sensor Pipeline">
        <DiagRow label="Active sensors" value={meta.active.length} />
        <DiagRow label="Packets received" value={s.packets ?? "—"} />
        <DiagRow label="Records received" value={d.received ?? s.records ?? "—"} />
        <DiagRow label="Lost samples" value={d.lost ?? "—"} />
        <DiagRow label="Reordered" value={d.reordered ?? "—"} />
        <DiagRow label="Decode errors" value={s.decode_errors ?? "—"} />
        <DiagRow label="Recording" value={meta.recording?.active ? `Yes · ${meta.recording.rows} rows` : "No"} />
      </Panel>
    </div>
  );
}

export default function App() {
  const meta = useTelemetry();
  const [view, setView] = useState("Dashboard");
  const [selected, setSelected] = useState(null);

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
                  <Phone3D />
                </Panel>
                <Panel title="Live Sensor Data" className="max-h-[460px] overflow-y-auto">
                  <LiveGrid meta={meta} onSelect={setSelected} />
                </Panel>
              </div>
              <Panel title="Real-Time Graphs" className="mt-4">
                <GraphPanel sensors={graphSensors} />
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
            <Panel title="Recordings">
              <Empty icon="Database" title="Recording browser" sub="Use Record above to capture; replay/list UI coming here." />
            </Panel>
          )}

          {view === "Settings" && (
            <Panel title="Settings">
              <Empty icon="Settings" title="Settings" sub="Theme, ports and preferences will live here." />
            </Panel>
          )}
        </div>
      </main>

      {selected && <SensorModal sensor={selected} catalog={meta.catalog} onClose={() => setSelected(null)} />}
    </div>
  );
}
