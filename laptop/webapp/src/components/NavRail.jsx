import { Icons } from "../icons.js";

const NAV = [
  ["Dashboard", "LayoutDashboard"],
  ["Sensors", "Radar"],
  ["Recordings", "Database"],
  ["Diagnostics", "Activity"],
  ["Settings", "Settings"],
];

const fmtRate = (bps) => {
  if (!bps) return "—";
  if (bps < 1024) return `${bps | 0} B/s`;
  if (bps < 1048576) return `${(bps / 1024).toFixed(0)} KB/s`;
  return `${(bps / 1048576).toFixed(2)} MB/s`;
};

function Stat({ label, value }) {
  return (
    <div className="flex items-center justify-between text-xs">
      <span className="text-faint">{label}</span>
      <span className="num text-fg">{value}</span>
    </div>
  );
}

export default function NavRail({ view, onView, meta }) {
  const d = meta.debug || {};
  const s = meta.stats || {};
  const connected = !!meta.device;
  const latency = d.latency_ms_p50 != null ? `${d.latency_ms_p50} ms` : "—";

  return (
    <aside className="w-60 shrink-0 h-full flex flex-col bg-surface border-r border-line">
      <div className="px-4 py-4 flex items-center gap-2.5">
        <div className="grid place-items-center w-9 h-9 rounded-xl bg-accent-soft text-accent shadow-glow">
          <Icons.Box size={18} />
        </div>
        <div>
          <div className="font-semibold leading-tight">
            SensorStream <span className="text-accent">Pro</span>
          </div>
          <div className="text-[10px] text-faint">Real-Time Sensor Telemetry</div>
        </div>
      </div>

      <nav className="px-2.5 py-1 flex flex-col gap-0.5">
        {NAV.map(([label, icon]) => {
          const I = Icons[icon] || Icons.CircleDot;
          const on = view === label;
          return (
            <button
              key={label}
              onClick={() => onView(label)}
              className={`flex items-center gap-3 px-3 py-2 rounded-xl text-sm transition-colors ${
                on ? "bg-accent-soft text-accent" : "text-muted hover:text-fg hover:bg-surface-2"
              }`}
            >
              <I size={18} strokeWidth={2} />
              {label}
            </button>
          );
        })}
      </nav>

      <div className="mt-auto p-3">
        <div className="card p-3.5 flex flex-col gap-2.5">
          <div className="flex items-center gap-2">
            <span className={`w-2.5 h-2.5 rounded-full ${connected ? "bg-ok animate-pulsedot" : "bg-faint"}`} />
            <span className={`text-sm font-medium ${connected ? "text-ok" : "text-muted"}`}>
              {connected ? "Connected" : "No device"}
            </span>
          </div>
          {connected && (
            <div className="text-[11px] text-faint -mt-1">
              {meta.device.model} {meta.device.addr ? `· ${meta.device.addr.split(":")[0]}` : ""}
            </div>
          )}
          <div className="h-px bg-line my-0.5" />
          <Stat label="Latency" value={latency} />
          <Stat label="Packet Loss" value={`${d.loss_pct ?? 0} %`} />
          <Stat label="Network In" value={fmtRate(s.bps)} />
          <Stat label="Packet Rate" value={s.pps != null ? `${Math.round(s.pps)}/s` : "—"} />
          <Stat label="Active Sensors" value={String(meta.active.length)} />
        </div>
      </div>
    </aside>
  );
}
