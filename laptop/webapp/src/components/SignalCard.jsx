import { Icons } from "../icons.js";
import { useSignal } from "../telemetry/store.js";
import { signalMeta, AXIS, magnitude } from "../telemetry/signals.js";

const fmt = (n, d = 2) => (n >= 0 ? "+" : "") + n.toFixed(d);

function AxisRow({ label, color, value, unit }) {
  return (
    <div className="flex items-center justify-between">
      <span className="flex items-center gap-2 text-xs text-muted">
        <span className="w-2 h-2 rounded-full" style={{ background: color }} />
        {label}
      </span>
      <span className="num text-sm text-fg">
        {value} <span className="text-faint text-[11px]">{unit}</span>
      </span>
    </div>
  );
}

function VectorValues({ v, unit }) {
  const labels = ["X", "Y", "Z"];
  const colors = [AXIS.X, AXIS.Y, AXIS.Z];
  const mag = magnitude(v);
  return (
    <div className="flex flex-col gap-1.5">
      {labels.map((l, i) => (
        <AxisRow key={l} label={l} color={colors[i]} value={v[i] != null ? fmt(v[i]) : "—"} unit={unit} />
      ))}
      <div className="h-px bg-line my-1" />
      <div className="flex items-center justify-between">
        <span className="text-xs text-muted">|magnitude|</span>
        <span className="num text-base font-semibold text-fg">
          {isNaN(mag) ? "—" : mag.toFixed(2)} <span className="text-faint text-[11px] font-normal">{unit}</span>
        </span>
      </div>
    </div>
  );
}

function QuatValues({ v }) {
  const labels = ["qx", "qy", "qz", "qw"];
  return (
    <div className="grid grid-cols-2 gap-x-4 gap-y-1.5">
      {labels.map((l, i) => (
        <div key={l} className="flex items-center justify-between">
          <span className="text-xs text-muted">{l}</span>
          <span className="num text-sm text-fg">{v[i] != null ? v[i].toFixed(3) : "—"}</span>
        </div>
      ))}
    </div>
  );
}

function ScalarValue({ v, unit }) {
  return (
    <div className="flex items-baseline gap-2 py-1">
      <span className="num text-3xl font-semibold text-fg">{v[0] != null ? v[0].toFixed(2) : "—"}</span>
      <span className="text-sm text-faint">{unit}</span>
    </div>
  );
}

export default function SignalCard({ handle, type, onSelect }) {
  const rec = useSignal(handle);
  const meta = signalMeta(type);
  const Icon = Icons[meta.icon] || Icons.CircleDot;
  const v = rec?.v || [];
  const active = !!rec;

  return (
    <div
      onClick={onSelect}
      className="card p-4 flex flex-col gap-3 bg-surface-grad cursor-pointer hover:border-line2 transition-colors [content-visibility:auto] [contain-intrinsic-size:auto_180px]"
    >
      <div className="flex items-start justify-between">
        <div className="flex items-center gap-3 min-w-0">
          <div className="grid place-items-center w-9 h-9 rounded-lg bg-accent-soft text-accent shrink-0">
            <Icon size={18} strokeWidth={2} />
          </div>
          <div className="min-w-0">
            <div className="text-sm font-semibold text-fg truncate">{meta.name}</div>
            <div className="text-[11px] text-faint truncate">{meta.sub}</div>
          </div>
        </div>
        <span className={`w-2 h-2 rounded-full mt-1 ${active ? "bg-ok animate-pulsedot" : "bg-faint"}`} />
      </div>

      {meta.kind === "vector" ? (
        <VectorValues v={v} unit={meta.unit} />
      ) : meta.kind === "orientation" ? (
        <QuatValues v={v} />
      ) : (
        <ScalarValue v={v} unit={meta.unit} />
      )}

      <div className="flex items-center justify-between text-[11px] text-muted pt-1">
        <span className="num">{rec?.hz ? rec.hz.toFixed(0) + " Hz" : "—"}</span>
        <span className={active ? "text-ok" : "text-faint"}>{active ? "ACTIVE" : "IDLE"}</span>
      </div>
    </div>
  );
}
