import { useInsightStats } from "../../telemetry/insights.js";
import { AXIS } from "../../telemetry/signals.js";
import { fmtSig } from "../../lib/insightsFormat.js";

const COLORS = [AXIS.X, AXIS.Y, AXIS.Z, "#b57edc"];

// Full-rate, server-computed noise and rate for one sensor (updates ~1 Hz).
export default function InsightFooter({ handle, n }) {
  const s = useInsightStats(handle);
  return (
    <div className="flex items-center gap-3 px-3 py-1 border-t border-line num text-[11px] min-h-[26px]">
      {s ? (
        <>
          {s.axes.slice(0, n).map((a, i) => (
            <span key={i} style={{ color: COLORS[i] }} title={a.fstd != null ? "σ raw → filtered, last 10 s (full rate)" : "σ over the last 10 s (full rate)"}>
              σ {fmtSig(a.std)}{a.fstd != null ? ` → ${fmtSig(a.fstd)}` : ""}
            </span>
          ))}
          <span className="ml-auto text-muted whitespace-nowrap">
            {s.rate_hz ? `${s.rate_hz.toFixed(1)} Hz` : "—"} · jitter {s.jitter_ms != null ? `${s.jitter_ms.toFixed(2)} ms` : "—"}
            {s.gaps ? <span className="text-warn"> · {s.gaps} gaps</span> : null}
          </span>
        </>
      ) : (
        <span className="text-faint">σ / rate appear once data flows</span>
      )}
    </div>
  );
}
