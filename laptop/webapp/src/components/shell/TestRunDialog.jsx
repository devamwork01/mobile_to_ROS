import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { Icons } from "../../icons.js";
import { Segmented } from "./Panel.jsx";
import { sendCommand } from "../../telemetry/store.js";
import { DURATIONS } from "../../lib/insightsFormat.js";

// Start a test run: preset, duration, sensors (default = pinned vector sensors).
// Rendered through a portal: the KPI strip uses backdrop-filter, which would otherwise become the
// containing block of this fixed overlay and trap it inside the 56 px header.
export default function TestRunDialog({ rows, defaultHandles, onClose }) {
  const [preset, setPreset] = useState("still");
  const [seconds, setSeconds] = useState(300);
  const [picked, setPicked] = useState(() => new Set(defaultHandles));
  const box = useRef(null);
  useEffect(() => {
    const k = (e) => e.key === "Escape" && onClose();
    document.addEventListener("keydown", k);
    return () => document.removeEventListener("keydown", k);
  }, [onClose]);
  const active = rows.filter((r) => r.active);
  const toggle = (h) => setPicked((s) => { const n = new Set(s); n.has(h) ? n.delete(h) : n.add(h); return n; });
  const start = () => {
    sendCommand({ cmd: "testrun_start", preset, seconds, handles: [...picked] });
    onClose();
  };
  return createPortal(
    <div className="fixed inset-0 z-40 grid place-items-center bg-black/50" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div ref={box} className="panel w-[420px] max-w-[92vw] p-4 flex flex-col gap-4">
        <div className="flex items-center gap-2">
          <Icons.FlaskConical size={16} className="text-accent" />
          <h2 className="text-sm font-semibold">New test run</h2>
        </div>
        <div>
          <div className="text-[10px] uppercase tracking-[0.12em] text-faint mb-1">Preset</div>
          <Segmented options={[["still", "Still"], ["capture", "Capture"]]} value={preset} onChange={setPreset} />
          <p className="text-[11px] text-faint mt-1.5 leading-snug">
            {preset === "still"
              ? "Phone (and robot) lying perfectly still. Reports bias vs gravity / zero rate, noise, drift; Allan deviation for 2 min or longer."
              : "No reference values - noise, rate and drift of whatever the sensors see (e.g. while moving)."}
          </p>
        </div>
        <div>
          <div className="text-[10px] uppercase tracking-[0.12em] text-faint mb-1">Duration</div>
          <Segmented options={DURATIONS} value={seconds} onChange={setSeconds} />
        </div>
        <div>
          <div className="text-[10px] uppercase tracking-[0.12em] text-faint mb-1">Sensors (all streaming sensors are recorded; these are highlighted)</div>
          <div className="flex flex-wrap gap-1.5">
            {active.map((r) => (
              <button key={r.handle} onClick={() => toggle(r.handle)}
                className={`px-2 py-1 rounded-lg text-xs border ${picked.has(r.handle) ? "border-accent text-accent bg-accent-soft" : "border-line text-muted"}`}>
                {r.label}
              </button>
            ))}
          </div>
        </div>
        <div className="flex justify-end gap-2">
          <button onClick={onClose} className="btn-ghost text-xs py-1.5">Cancel</button>
          <button onClick={start} disabled={picked.size === 0} className="btn-accent text-xs py-1.5 disabled:opacity-40">
            <Icons.Timer size={14} /> Start
          </button>
        </div>
      </div>
    </div>
  , document.body);
}
