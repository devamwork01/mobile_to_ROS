import { useEffect, useRef, useState } from "react";
import { Icons } from "../icons.js";
import { listRecordings, querySignal } from "../lib/recordingsApi.js";
import { signalMeta } from "../telemetry/signals.js";
import HistoryChart from "./HistoryChart.jsx";
import { sendCommand } from "../telemetry/store.js";
import { useLastReportEvent, requestOpenReport } from "../telemetry/insights.js";

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
  const [nextCursor, setNextCursor] = useState(null); // null = no more pages
  const [total, setTotal] = useState(0);
  const [loadingMore, setLoadingMore] = useState(false);
  const [err, setErr] = useState(null);
  const [sel, setSel] = useState(null); // selected recording
  const [handle, setHandle] = useState(null); // selected signal handle
  const [q, setQ] = useState(null); // LOD query result
  const [range, setRange] = useState(null); // current zoom window {start,end} ns; null = full
  const [loadingSig, setLoadingSig] = useState(false);
  const chartWrap = useRef(null);
  const [analysing, setAnalysing] = useState(null); // recording id being analysed
  const [analyseErr, setAnalyseErr] = useState(null);
  const reportEvent = useLastReportEvent();
  // React only to the outcome for *this* recording (other browsers' runs also emit events).
  useEffect(() => {
    if (!reportEvent) return;
    if (reportEvent.ok) {
      setRecs((prev) => prev && prev.map((r) => (r.id === reportEvent.id ? { ...r, hasReport: true } : r)));
      setSel((s) => (s && s.id === reportEvent.id ? { ...s, hasReport: true } : s));
    }
    if (reportEvent.id === analysing) {
      setAnalysing(null);
      setAnalyseErr(reportEvent.ok ? null : reportEvent.message || "Analysis failed");
    }
  }, [reportEvent]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    listRecordings()
      .then((page) => { setRecs(page.items); setNextCursor(page.next_cursor); setTotal(page.total); })
      .catch((e) => { setErr(e.message); setRecs([]); });
  }, []);

  const loadMore = () => {
    if (!nextCursor || loadingMore) return;
    setLoadingMore(true);
    listRecordings({ cursor: nextCursor })
      .then((page) => {
        setRecs((prev) => [...prev, ...page.items]);
        setNextCursor(page.next_cursor);
        setTotal(page.total);
      })
      .catch((e) => setErr(e.message))
      .finally(() => setLoadingMore(false));
  };

  // ~1 bucket per pixel, so zooming into a narrower range yields higher effective resolution.
  const runQuery = (rec, h, r) => {
    setLoadingSig(true);
    setQ(null);
    const buckets = Math.max(200, Math.round(chartWrap.current?.clientWidth || 900));
    querySignal(rec.id, h, { buckets, start: r?.start, end: r?.end })
      .then(setQ)
      .catch((e) => setErr(e.message))
      .finally(() => setLoadingSig(false));
  };
  const openSignal = (rec, h) => { setHandle(h); setRange(null); runQuery(rec, h, null); };
  const zoomTo = (start, end) => { const r = { start, end }; setRange(r); runQuery(sel, handle, r); };
  const resetZoom = () => { setRange(null); runQuery(sel, handle, null); };

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
        <h2 className="text-xs font-semibold uppercase tracking-[0.12em] text-muted mb-3 px-1">Recordings ({recs.length < total ? `${recs.length} of ${total}` : total})</h2>
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
          {nextCursor && (
            <button onClick={loadMore} disabled={loadingMore} className="btn-ghost text-xs justify-center py-2">
              {loadingMore ? "Loading…" : `Load more (${total - recs.length} left)`}
            </button>
          )}
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
              {sel.hasReport ? (
                <button className="btn-ghost text-xs py-1" onClick={() => requestOpenReport(sel.id)}>
                  <Icons.FileText size={13} /> Open report
                </button>
              ) : (
                <button
                  className="btn-ghost text-xs py-1 disabled:opacity-50"
                  disabled={analysing === sel.id}
                  onClick={() => { setAnalysing(sel.id); setAnalyseErr(null); sendCommand({ cmd: "analyse", id: sel.id }); }}
                  title="Compute noise, drift, rate and spectra for this recording (full rate, on the server)"
                >
                  <Icons.Sigma size={13} /> {analysing === sel.id ? "Analysing…" : "Analyse"}
                </button>
              )}
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
                <HistoryChart q={q} onZoom={zoomTo} onReset={resetZoom} zoomed={!!range} />
              )}
            </div>
            {err && <div className="text-xs text-err mt-2">{err}</div>}
            {analyseErr && <div className="text-xs text-err mt-2">Analysis failed: {analyseErr}</div>}
          </>
        )}
      </section>
    </div>
  );
}
