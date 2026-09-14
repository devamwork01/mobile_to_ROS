import { useEffect, useRef, useState } from "react";
import { Icons } from "../icons.js";
import { listRecordings, querySignal } from "../lib/recordingsApi.js";
import { signalMeta } from "../telemetry/signals.js";
import HistoryChart from "./HistoryChart.jsx";

const fmtBytes = (b) => (b < 1024 ? `${b} B` : b < 1048576 ? `${(b / 1024).toFixed(0)} KB` : `${(b / 1048576).toFixed(1)} MB`);
const fmtDur = (ms) => {
  if (ms == null) return "—";
  const s = ms / 1000;
  if (s < 60) return `${s.toFixed(1)}s`;
  return `${Math.floor(s / 60)}m ${Math.round(s % 60)}s`;
};
const fmtTime = (ms) => new Date(ms).toLocaleString([], { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
const sensorLabel = (s) => signalMeta(s.type).name || s.name || `Type ${s.type}`;

export default function RecordingsView() {
  const [recs, setRecs] = useState(null); // null = loading
  const [err, setErr] = useState(null);
  const [sel, setSel] = useState(null); // selected recording
  const [handle, setHandle] = useState(null); // selected signal handle
  const [q, setQ] = useState(null); // LOD query result
  const [loadingSig, setLoadingSig] = useState(false);
  const chartWrap = useRef(null);

  useEffect(() => {
    listRecordings().then(setRecs).catch((e) => { setErr(e.message); setRecs([]); });
  }, []);

  const openSignal = (rec, h) => {
    setHandle(h);
    setLoadingSig(true);
    setQ(null);
    const buckets = Math.max(200, Math.round(chartWrap.current?.clientWidth || 900));
    querySignal(rec.id, h, { buckets })
      .then(setQ)
      .catch((e) => setErr(e.message))
      .finally(() => setLoadingSig(false));
  };

  if (recs === null) {
    return <div className="panel p-8 grid place-items-center text-sm text-muted">Loading recordings…</div>;
  }
  if (recs.length === 0) {
    return (
      <div className="panel p-8 grid place-items-center text-center">
        <div className="text-faint">
          <Icons.Database size={28} className="mx-auto mb-2 opacity-50" />
          <div className="text-sm font-medium text-muted">No recordings yet</div>
          <div className="text-xs">Hit Record while a phone streams; sessions appear here.</div>
          {err && <div className="text-xs text-err mt-2">{err}</div>}
        </div>
      </div>
    );
  }

  return (
    <div className="grid grid-cols-1 lg:grid-cols-[minmax(0,320px)_minmax(0,1fr)] gap-4 items-start">
      {/* Recordings list */}
      <section className="panel p-3 max-h-[70vh] overflow-y-auto">
        <h2 className="text-xs font-semibold uppercase tracking-[0.12em] text-muted mb-3 px-1">Recordings ({recs.length})</h2>
        <div className="flex flex-col gap-2">
          {recs.map((r) => (
            <button
              key={r.id}
              onClick={() => { setSel(r); setHandle(null); setQ(null); }}
              className={`card p-3 text-left transition-colors ${sel?.id === r.id ? "border-accent" : "hover:border-line2"}`}
            >
              <div className="text-sm font-medium text-fg truncate">{r.model || r.id.replace(/^session_/, "")}</div>
              <div className="text-[11px] text-faint num mt-0.5">{fmtTime(r.modified)} · {fmtDur(r.durationMs)} · {fmtBytes(r.sizeBytes)}</div>
              <div className="text-[11px] text-muted mt-1">{r.sensors.length} signals · {r.frames.toLocaleString()} frames</div>
            </button>
          ))}
        </div>
      </section>

      {/* Detail */}
      <section className="panel p-4 min-h-[360px]">
        {!sel ? (
          <div className="min-h-[300px] grid place-items-center text-sm text-faint">Select a recording to inspect its signals.</div>
        ) : (
          <>
            <div className="flex items-baseline justify-between flex-wrap gap-2 mb-3">
              <h2 className="text-sm font-semibold">{sel.model || sel.id}</h2>
              <span className="text-[11px] text-faint num">{fmtTime(sel.modified)} · {fmtDur(sel.durationMs)} · {sel.frames.toLocaleString()} frames{sel.android ? ` · Android ${sel.android}` : ""}</span>
            </div>
            <div className="flex items-center gap-1.5 flex-wrap mb-4">
              {sel.sensors.map((s) => (
                <button
                  key={s.handle}
                  onClick={() => openSignal(sel, s.handle)}
                  className={`px-3 py-1.5 rounded-lg text-xs transition-colors ${handle === s.handle ? "bg-accent-soft text-accent" : "text-muted hover:text-fg bg-surface-2"}`}
                >
                  {sensorLabel(s)}
                </button>
              ))}
            </div>
            <div ref={chartWrap}>
              {handle == null ? (
                <div className="min-h-[300px] grid place-items-center text-sm text-faint">Pick a signal to plot its full-resolution history (server-side LOD).</div>
              ) : loadingSig ? (
                <div className="min-h-[300px] grid place-items-center text-sm text-muted">Aggregating…</div>
              ) : (
                <HistoryChart q={q} />
              )}
            </div>
            {err && <div className="text-xs text-err mt-2">{err}</div>}
          </>
        )}
      </section>
    </div>
  );
}
