import { useEffect } from "react";
import { Icons } from "../icons.js";
import { useSignal } from "../telemetry/store.js";
import { signalMeta, AXIS } from "../telemetry/signals.js";

const ACC = { 3: "High", 2: "Medium", 1: "Low", 0: "Unreliable", "-1": "No contact" };

function Row({ label, value, mono = true }) {
  return (
    <div className="flex items-center justify-between py-1.5 border-b border-line/60 last:border-0">
      <span className="text-xs text-muted">{label}</span>
      <span className={`text-sm text-fg ${mono ? "num" : ""}`}>{value ?? "—"}</span>
    </div>
  );
}

export default function SensorModal({ sensor, catalog, onClose }) {
  const rec = useSignal(sensor.handle);
  const cat = (catalog || []).find((s) => s.handle === sensor.handle) || {};
  const meta = signalMeta(sensor.type, cat.stringType);
  const Icon = Icons[meta.icon] || Icons.CircleDot;
  const v = rec?.v || [];
  const isVec = meta.kind === "vector" || meta.kind === "orientation";
  const labels = meta.kind === "orientation" ? ["qx", "qy", "qz", "qw"] : ["X", "Y", "Z", "W"];
  const colors = [AXIS.X, AXIS.Y, AXIS.Z, "#b57edc"];

  useEffect(() => {
    const onKey = (e) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div className="fixed inset-0 z-50 grid place-items-center p-4 bg-black/60 backdrop-blur-sm animate-fade-in" onClick={onClose}>
      <div className="panel shadow-panel w-full max-w-lg p-5 max-h-[90vh] overflow-auto" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-start justify-between mb-4">
          <div className="flex items-center gap-3">
            <div className="grid place-items-center w-11 h-11 rounded-xl bg-accent-soft text-accent">
              <Icon size={22} />
            </div>
            <div>
              <div className="text-lg font-semibold">{meta.name}</div>
              <div className="text-xs text-faint">
                {meta.sub} · <span className="num">{meta.type}</span> · type {sensor.type}
              </div>
            </div>
          </div>
          <button onClick={onClose} className="text-muted hover:text-fg">
            <Icons.CircleDot size={0} />
            <span className="text-xl leading-none">×</span>
          </button>
        </div>

        {isVec && (
          <div className="card p-3 mb-4">
            <div className="text-[11px] uppercase tracking-wider text-muted mb-2">Current Values</div>
            <div className="grid grid-cols-2 gap-x-6 gap-y-1.5">
              {v.map((val, i) => (
                <div key={i} className="flex items-center justify-between">
                  <span className="flex items-center gap-2 text-xs text-muted">
                    <span className="w-2 h-2 rounded-full" style={{ background: colors[i] || "#8b95a4" }} />
                    {labels[i] || `v${i}`}
                  </span>
                  <span className="num text-sm">
                    {val.toFixed(3)} <span className="text-faint text-[11px]">{meta.unit}</span>
                  </span>
                </div>
              ))}
            </div>
          </div>
        )}

        <div className="grid sm:grid-cols-2 gap-x-6">
          <div>
            <div className="text-[11px] uppercase tracking-wider text-muted mb-1">Live</div>
            <Row label="Rate" value={rec?.hz ? `${rec.hz.toFixed(1)} Hz` : "—"} />
            <Row label="Accuracy" value={rec ? `${ACC[rec.acc] ?? rec.acc} (${rec.acc})` : "—"} mono={false} />
            <Row label="Sequence" value={rec?.seq} />
            <Row label="Values" value={v.length || "—"} />
            <Row label="Unit" value={meta.unit || "—"} mono={false} />
          </div>
          <div>
            <div className="text-[11px] uppercase tracking-wider text-muted mb-1 mt-4 sm:mt-0">Hardware</div>
            <Row label="Vendor" value={cat.vendor} mono={false} />
            <Row label="Resolution" value={cat.resolution != null ? cat.resolution : undefined} />
            <Row label="Max Range" value={cat.maxRange != null ? `${cat.maxRange} ${meta.unit}` : undefined} />
            <Row label="Power" value={cat.power != null ? `${cat.power} mA` : undefined} />
            <Row label="Max Rate" value={cat.maxHz ? `${Math.round(cat.maxHz)} Hz` : undefined} />
            <Row label="Wake-up" value={cat.isWakeUp != null ? (cat.isWakeUp ? "Yes" : "No") : undefined} mono={false} />
          </div>
        </div>
        {!cat.vendor && (
          <div className="mt-3 text-[11px] text-faint">Hardware details appear when a phone is connected (from its sensor catalog).</div>
        )}
      </div>
    </div>
  );
}
