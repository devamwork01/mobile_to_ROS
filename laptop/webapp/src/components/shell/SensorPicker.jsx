import { useEffect, useState } from "react";
import { Icons } from "../../icons.js";
import { IconBtn } from "./Panel.jsx";
import { layout, useLayout } from "../../lib/layout.js";
import { history } from "../../telemetry/liveHistory.js";
import { PIN_3D, pinKeyFor } from "../../telemetry/sensors.js";

const DOT = { ok: "bg-ok", warn: "bg-warn", off: "bg-faint" };

function useTick(ms) {
  const [, setN] = useState(0);
  useEffect(() => {
    const id = setInterval(() => setN((n) => n + 1), ms);
    return () => clearInterval(id);
  }, [ms]);
}

function Row({ r, pinKey, health, hz, onInfo }) {
  const pinned = pinKey != null;
  const canPin = pinned || r.active || r.key === PIN_3D;
  const PinIcon = pinned ? Icons.Pin : Icons.PinOff;
  return (
    <div className={`group grid grid-cols-[8px_1fr_auto_auto_auto] items-center gap-1.5 pl-2 pr-1 py-1 rounded-lg ${pinned ? "bg-surface-2" : "hover:bg-surface-2/60"}`}>
      <span className={`w-1.5 h-1.5 rounded-full ${DOT[health]}`} />
      <span className={`text-xs truncate ${r.active || r.key === PIN_3D ? "text-fg" : "text-faint"}`} title={r.detail}>
        {r.label}
      </span>
      <span className="num text-[10px] text-muted w-8 text-right">{hz}</span>
      {r.handle != null ? (
        <button title="Details" onClick={() => onInfo(r)} className="grid place-items-center w-6 h-6 rounded-md text-faint opacity-0 group-hover:opacity-100 hover:text-fg">
          <Icons.Info size={13} />
        </button>
      ) : (
        <span className="w-6" />
      )}
      <button
        disabled={!canPin}
        title={pinned ? "Unpin" : "Pin"}
        onClick={() => (pinned ? layout.unpin(pinKey) : layout.pin(r.key))}
        className={`grid place-items-center w-6 h-6 rounded-md ${pinned ? "text-accent" : "text-faint hover:text-fg"} disabled:opacity-30 disabled:hover:text-faint`}
      >
        <PinIcon size={13} />
      </button>
    </div>
  );
}

export default function SensorPicker({ rows, onInfo }) {
  const L = useLayout();
  const [q, setQ] = useState("");
  useTick(1000); // live Hz + health refresh
  if (L.pickerCollapsed) {
    return (
      <div className="w-11 shrink-0 border-r border-line bg-surface/60 flex flex-col items-center py-2">
        <IconBtn title="Show sensors" icon="PanelLeftOpen" onClick={layout.togglePicker} />
      </div>
    );
  }
  const now = Date.now();
  const pinKeys = L.pins.map((p) => p.key);
  const keyOf = (r) => (r.key === PIN_3D ? (pinKeys.includes(PIN_3D) ? PIN_3D : null) : pinKeyFor(r, pinKeys));
  const anyOrientation = rows.some((r) => r.active && r.kind === "orientation");
  const threeD = { key: PIN_3D, label: "3D Orientation", detail: "Rotation vector", active: anyOrientation, handle: null, kind: "3d" };
  const f = q.trim().toLowerCase();
  const match = (r) => !f || r.label.toLowerCase().includes(f) || String(r.detail || "").toLowerCase().includes(f);
  const all = [threeD, ...rows].filter(match);
  const groups = [
    ["Pinned", all.filter((r) => keyOf(r) != null)],
    ["Streaming", all.filter((r) => keyOf(r) == null && r.active)],
    ["Not streaming", all.filter((r) => keyOf(r) == null && !r.active)],
  ];
  const healthOf = (r) => (r.key === PIN_3D ? (anyOrientation ? "ok" : "off") : r.active ? history.health(r.handle, now) : "off");
  const hzOf = (r) => (r.handle != null && r.active ? Math.round(history.liveHz(r.handle)) : "–");

  return (
    <aside className="w-60 shrink-0 border-r border-line bg-surface/60 flex flex-col min-h-0">
      <div className="flex items-center gap-1 p-2">
        <label className="flex-1 flex items-center gap-1.5 px-2 h-8 rounded-lg border border-line bg-surface text-faint focus-within:border-accent">
          <Icons.Search size={13} />
          <input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            onKeyDown={(e) => e.key === "Escape" && setQ("")}
            placeholder="Filter sensors"
            className="flex-1 min-w-0 bg-transparent text-xs text-fg outline-none placeholder:text-faint"
          />
        </label>
        <IconBtn title="Hide sensors" icon="PanelLeftClose" onClick={layout.togglePicker} />
      </div>
      <div className="flex-1 overflow-y-auto px-2 pb-3">
        {groups.map(([title, items]) =>
          items.length ? (
            <div key={title} className="mt-2">
              <div className="flex justify-between px-2 mb-1 text-[9px] uppercase tracking-[0.12em] text-faint">
                <span>
                  {title} · {items.length}
                </span>
                <span>Hz</span>
              </div>
              {items.map((r) => (
                <Row key={`${r.key}#${r.handle}`} r={r} pinKey={keyOf(r)} health={healthOf(r)} hz={hzOf(r)} onInfo={onInfo} />
              ))}
            </div>
          ) : null
        )}
        {rows.length === 0 && <div className="px-2 pt-4 text-xs text-faint">No sensors yet. Start streaming from the phone (or run --selftest).</div>}
      </div>
    </aside>
  );
}
