import { useEffect, useRef, useState } from "react";
import { sendCommand } from "../../telemetry/store.js";
import { netLoss } from "../../lib/metrics.js";
import Sparkline from "./Sparkline.jsx";

const DASH = "—";
const fmtBytes = (bps) => (!bps ? DASH : bps < 1048576 ? `${(bps / 1024).toFixed(0)} KB/s` : `${(bps / 1048576).toFixed(2)} MB/s`);
function fmtDuration(ms) {
  const s = Math.max(0, Math.floor(ms / 1000));
  return [Math.floor(s / 3600), Math.floor((s % 3600) / 60), s % 60].map((n) => String(n).padStart(2, "0")).join(":");
}

function useNow(ms = 1000) {
  const [n, setN] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setN(Date.now()), ms);
    return () => clearInterval(id);
  }, [ms]);
  return n;
}

// Wall-clock ms when `on` last became true; null while false.
function useSince(on) {
  const [since, setSince] = useState(null);
  useEffect(() => setSince(on ? Date.now() : null), [on]);
  return since;
}

// Append `value` each time `stamp` (a message object) changes; keep the last `cap` values.
function useSeries(value, stamp, cap = 60) {
  const [series, setSeries] = useState([]);
  useEffect(() => {
    if (value != null) setSeries((s) => [...s, value].slice(-cap));
  }, [stamp]); // eslint-disable-line react-hooks/exhaustive-deps
  return series;
}

// Records/s from the cumulative `records` counter in successive stats messages.
function useRate(count, stamp) {
  const prev = useRef(null);
  const [rate, setRate] = useState(null);
  useEffect(() => {
    const now = Date.now();
    const p = prev.current;
    if (p && count != null && now > p.t && count >= p.c) setRate(((count - p.c) * 1000) / (now - p.t));
    if (count != null) prev.current = { c: count, t: now };
  }, [stamp]); // eslint-disable-line react-hooks/exhaustive-deps
  return rate;
}

function Metric({ label, tone = "text-fg", children }) {
  return (
    <div className="px-3 border-l border-line leading-tight shrink-0">
      <div className="text-[9px] uppercase tracking-[0.12em] text-faint">{label}</div>
      <div className={`num text-[13px] flex items-center gap-1.5 ${tone}`}>{children}</div>
    </div>
  );
}

export default function KpiStrip({ meta, snapshot }) {
  const now = useNow();
  const d = meta.debug || {};
  const s = meta.stats || {};
  const connected = !!meta.device;
  const latency = useSeries(d.latency_ms_p50, meta.debug);
  const rps = useRate(s.records, meta.stats);
  const since = useSince(connected);
  const rec = !!meta.recording?.active;
  const recSince = useSince(rec);
  const loss = netLoss(d, s);

  return (
    <header className="h-14 shrink-0 flex items-center gap-1 px-3 border-b border-line bg-surface/80 backdrop-blur">
      {/* Metrics scroll on narrow windows; Snapshot/Record stay pinned on the right. */}
      <div className="flex-1 min-w-0 flex items-center gap-1 overflow-x-auto">
      <div className="flex items-center gap-2 pl-2 pr-3 py-1.5 mr-1 rounded-xl bg-surface-2 shrink-0">
        <span className={`w-2 h-2 rounded-full ${connected ? "bg-ok animate-pulsedot" : meta.status === "connected" ? "bg-warn" : "bg-err"}`} />
        {connected ? (
          <div className="text-xs whitespace-nowrap">
            <span className="font-semibold">{meta.device.model}</span>
            <span className="text-muted"> · Android {meta.device.android}</span>
          </div>
        ) : (
          <div className="text-xs text-muted whitespace-nowrap">
            {meta.status === "connected" ? `Waiting for device · ${location.host}` : "Server offline"}
          </div>
        )}
      </div>
      <Metric label="Latency p50">
        {connected && d.latency_ms_p50 != null ? `${d.latency_ms_p50} ms` : DASH}
        {connected && <Sparkline values={latency} />}
      </Metric>
      <Metric label="Jitter">{connected && d.jitter_ms != null ? `${d.jitter_ms} ms` : DASH}</Metric>
      <Metric label="Loss" tone={!connected ? "text-fg" : loss.pct > 0.1 ? "text-warn" : "text-ok"}>
        {connected ? `${loss.pct.toFixed(3)} %` : DASH}
      </Metric>
      <Metric label="Throughput">{connected && rps != null ? `${Math.round(rps)} rec/s · ${fmtBytes(s.bps)}` : DASH}</Metric>
      <Metric label="Session">{since ? fmtDuration(now - since) : DASH}</Metric>
      </div>
      <div className="flex items-center gap-2 pl-3 shrink-0">
        {snapshot}
        <button
          onClick={() => sendCommand({ cmd: rec ? "record_stop" : "record_start" })}
          className={`btn text-xs py-1.5 ${rec ? "bg-err/15 text-err border border-err/40" : "btn-ghost"}`}
        >
          <span className={`w-2 h-2 rounded-full bg-err ${rec ? "animate-pulsedot" : ""}`} />
          {rec ? `Stop · ${recSince ? fmtDuration(now - recSince) : "00:00:00"} · ${(meta.recording.rows || 0).toLocaleString()} rows` : "Record"}
        </button>
      </div>
    </header>
  );
}
