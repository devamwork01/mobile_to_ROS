import { useState, useEffect } from "react";
import { Icons } from "./icons.js";
import NavRail from "./components/NavRail.jsx";
import SignalCard from "./components/SignalCard.jsx";
import { useTelemetry, sendCommand } from "./telemetry/store.js";

const TABS = ["Live View", "Graphs", "Data Table", "Diagnostics", "Recording"];

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

function TopBar({ meta, tab, onTab }) {
  const rec = meta.recording?.active;
  return (
    <header className="h-14 shrink-0 flex items-center gap-1 px-4 border-b border-line bg-surface/70 backdrop-blur">
      <div className="flex items-center gap-1">
        {TABS.map((t) => (
          <button
            key={t}
            onClick={() => onTab(t)}
            className={`px-3 py-1.5 rounded-lg text-sm transition-colors ${
              tab === t ? "bg-surface-2 text-fg" : "text-muted hover:text-fg"
            }`}
          >
            {t}
          </button>
        ))}
      </div>
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

function Panel({ title, action, children, className = "" }) {
  return (
    <section className={`panel p-4 ${className}`}>
      {(title || action) && (
        <div className="flex items-center justify-between mb-3">
          {title && <h2 className="text-xs font-semibold uppercase tracking-[0.12em] text-muted">{title}</h2>}
          {action}
        </div>
      )}
      {children}
    </section>
  );
}

function Placeholder({ icon, label }) {
  const I = Icons[icon] || Icons.CircleDot;
  return (
    <div className="h-full min-h-[260px] grid place-items-center text-center text-faint">
      <div>
        <I size={30} className="mx-auto mb-2 opacity-50" />
        <div className="text-sm">{label}</div>
      </div>
    </div>
  );
}

function LiveGrid({ meta }) {
  if (meta.active.length === 0) {
    return (
      <div className="min-h-[200px] grid place-items-center text-center">
        <div className="text-faint">
          <Icons.Radar size={28} className="mx-auto mb-2 opacity-50" />
          <div className="text-sm font-medium text-muted">No sensors streaming</div>
          <div className="text-xs">Start streaming from the phone to see live signals.</div>
        </div>
      </div>
    );
  }
  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
      {meta.active.map(({ handle, type }) => (
        <SignalCard key={handle} handle={handle} type={type} />
      ))}
    </div>
  );
}

export default function App() {
  const meta = useTelemetry();
  const [view, setView] = useState("Dashboard");
  const [tab, setTab] = useState("Live View");

  return (
    <div className="flex h-full bg-ink">
      <NavRail view={view} onView={setView} meta={meta} />
      <main className="flex-1 min-w-0 flex flex-col">
        <TopBar meta={meta} tab={tab} onTab={setTab} />
        <div className="flex-1 overflow-auto p-4 bg-hero-grad">
          <div className="grid grid-cols-1 xl:grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)] gap-4">
            <Panel title="3D Device Visualization" className="min-h-[380px]">
              <Placeholder icon="Box" label="Premium 3D phone — arriving next stage" />
            </Panel>
            <Panel title="Live Sensor Data">
              <LiveGrid meta={meta} />
            </Panel>
          </div>
          <Panel title="Real-Time Graphs" className="mt-4">
            <Placeholder icon="LineChart" label="Premium real-time graphs — arriving next stage" />
          </Panel>
        </div>
      </main>
    </div>
  );
}
